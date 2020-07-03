(ns nuka.plan
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [loom.alg-generic :as generic]
            [loom.io]
            [loom.attr :as attr]
            [clojure.core.async :as a]
            [clojure.set :as set]
            [nuka.util :as util]
            [clojure.spec.alpha :as s])
  (:import [java.util.concurrent Executors]))

(defn has-cycles? [g]
  (not (alg/topsort g)))

(defn plan->graph [{:keys [tasks]}]
  (let [g (->> tasks
               (map (fn [[k {:keys [deps]}]] [k deps]))
               (into {})
               graph/digraph)]
    (reduce (fn [g [k task]]
              (attr/add-attr g k ::def task))
            g
            (map vector (keys tasks)
                 (vals tasks)))))

(defn graph->plan [graph]
  ;;filter out deps that are no longer nodes in the graph
  (let [filter-deps #(update % :deps (comp vec (partial filter (partial graph/has-node? graph))))]
    {:tasks (->> graph graph/nodes (map (fn [x] [x (filter-deps (attr/attr graph x ::def))])) (into {}))}))

(defn subplan [plan nodes]
  (let [g (plan->graph plan)]
    (graph->plan (graph/subgraph g nodes))))

(defn for-target [plan target]
  (let [g (plan->graph plan)]
    (graph->plan
     (graph/subgraph
      g
      (conj (alg/pre-traverse g target) target)))))

(defn for-tags [{:keys [tasks] :as plan} tags]
  (let [tags (set tags)]
    (subplan plan (map key (filter #(set/intersection tags (:tags %)) tasks)))))

(defn dry [plan]
  (update plan :tasks
          (partial reduce-kv
                   (fn [m k v]
                     (assoc m k (assoc v :fn (fn random-sleep [_] (Thread/sleep (rand-int 1000))))))
                   {})))

(defn free-tasks
  "Find all the tasks in the graph that have no dependencies and can
  be performed right now."
  [g]
  (remove (comp not-empty (graph/successors g)) (graph/nodes g)))

(defn next-graph
  "Returns a new graph after having removed the done-tasks."
  ([g done-tasks]
   (apply graph/remove-nodes g done-tasks)))

(defn empty-graph? [g]
  (empty? (graph/nodes g)))

(defn task-def [g id]
  (assoc (attr/attr g id ::def)
         :id id))

(defn select
  "Uses `select-fn` to populate a map of values out of `env`, by going through
  each map entry of `select-def` and putting the result of (select-fn env
  selector) in the resulting map. If a value is not found via the selector in
  `env`, the corresponding key is not included at all in the resulting
  map (instead of including it with a nil value).

  Example:

  > (select {:a {:b 10} :aa {:bb 7}}
            {:y [:a :b]}
            get-in-env)
  {:y 10}"
  [env select-def select-fn]
  (reduce-kv (fn [result k selector]
               (if-let [resolved (select-fn env selector)]
                 (assoc result k resolved)
                 result)) {} select-def))

(defn- now []
  (System/currentTimeMillis))

(defn task-input
  "Calculate what the input map of a task will be, based in the current `env`, the
  task's `:select` and `:in` keys and the `opts` that were passed to
  `execute` (relevant for custom `:select-fn`).

  The rationale for this order is that `env` is the overall state os it comes
  first, `:in` carries some static values that are specific to this task, but if
  `:select` finds the same dynamic value from `env` it may overwrite the default
  from the `:in` map."
  [task env opts]
  (let [{:keys      [in]
         select-def :select} task
        {:keys [select-fn]}  opts]
    (util/deep-merge env in (when select-def (select env select-def select-fn)))))

(defn submit! [{:keys [pool out env task opts]}]
  (let [{:keys    [id]
         function :fn} task]
    (if-not function
      (do
        ((-> opts :logging :log-fn) "Dummy task" id "-- skipping")
        (future (a/>!! out (merge task {:result {}})))) ;; don't do it in the planning thread, that would be a race condition
      (let [input (task-input task env opts)]
        (.submit pool
                 (fn []
                   (try
                     (let [result (function input)]
                       (a/>!! out (merge task {:input  input
                                               :result result
                                               :end-tm (now)})))
                     (catch Exception e
                       (a/>!! out (merge task {:input     input
                                               :exception e}))))))
        ;;(future (a/>!! out (merge task {:start-tm (now)})))
        ))))

(defn submit-all! [{:keys [pool out env tasks opts] :as args}]
  (when (seq tasks)
    ((-> opts :logging :log-fn) "Starting" (pr-str (map :id tasks)))
    (doseq [t tasks]
      (submit! {:pool pool :out out :env env :task t :opts opts}))))

(defn- validate-all-deps-resolved! [{:keys [tasks]}]
  (let [declared-tasks (-> tasks keys set)
        unknown-deps   (->> (for [[task-name {:keys [deps]}] tasks]
                              [task-name (set/difference (set deps) declared-tasks)])
                            (remove (comp empty? second))
                            (into {}))]
    (when-not (empty? unknown-deps)
      (throw (ex-info "Plan contains unresolved deps" unknown-deps)))))

(defn validate! [plan]
  (validate-all-deps-resolved! plan))

(defn get-in-env [m path]
  (if (coll? path)
    (get-in m path)
    (get m path)))

(defn- handle-exception [{:keys [id result exception start-tm] :as msg}
                         {:keys [env state pool]}
                         {:keys [logging] :as opts}]
  (let [{:keys [log-error-fn]} logging]
    (log-error-fn "Task" id "failed")
    (log-error-fn "Stopping thread pool..."))
  (.shutdown pool)
  (throw (ex-info (str "Task " id " failed")
                  {:msg         (.getMessage exception)
                   :failed-task id
                   :env         env
                   :state       (update state id merge msg)} exception)))

(defn- handle-started [{:keys [id] :as msg}
                       stuff
                       {:keys [logging]}]
  (let [{:keys [log-fn]} logging]
    (log-fn "Task" id "started"))
  (assoc-in stuff [:state id] msg))

(defn- handle-success [{:keys [id result] :as msg}
                       {:keys [graph env state in-progress submitted]}
                       {:keys [logging] :as opts}]
  (let [{:keys [log-fn]} logging]
    (if (:results logging)
      (log-fn "Task" id "done =>" (pr-str result))
      (log-fn "Task" id "done.")))
  {:graph       (next-graph graph [id])
   :in-progress (set/union in-progress (set (map :id submitted)))
   :env         (util/deep-merge env result)
   :state       (update state id merge msg)})

(defn- done? [graph]
  (empty? (graph/nodes graph)))

(def default-opts
  {:threads   4
   :select-fn get-in-env
   :env       {}
   :logging   {:log-fn       println
               :log-error-fn println
               :results      true
               :clashes      true}})

(s/def ::selector any?)
(s/def ::select (s/map-of keyword? ::selector))
(s/def ::in map?)
(s/def ::deps (s/coll-of keyword?))
(s/def ::fn fn?)
(s/def ::task (s/keys :opt-un [::fn ::deps ::select]))
(s/def ::tasks (s/map-of keyword? ::task))
(s/def ::plan (s/keys :req-un [::tasks]))

(defn execute
  ([plan]
   (execute plan nil))
  ([plan opts]
   (when-not (s/valid? ::plan plan)
     (throw (ex-info "Plan not valid" (s/explain-data ::plan plan))))
   (validate! plan)
   (let [{:keys [state-atom logging threads env]
          :as opts}       (merge default-opts opts)
         {:keys [log-fn]} logging
         graph            (plan->graph plan)
         _                (when (has-cycles? graph)
                            (throw (ex-info "Task graph has cycles" plan)))
         pool             (Executors/newFixedThreadPool threads)
         out              (a/chan)]
     (loop [stuff {:graph       graph
                   :in-progress #{}
                   :env         env
                   :state       {}}]
       (let [{:keys [graph in-progress env state]} stuff]
         (when state-atom (reset! state-atom state))
         (if (done? graph)
           (do
             (.shutdown pool)
             (log-fn "Plan done!")
             state)
           (let [to-submit (->> (set/difference
                                 (-> graph free-tasks set)
                                 in-progress)
                                (take threads)
                                (map (partial task-def graph)))]
             (submit-all! {:pool  pool
                           :out   out
                           :env   env
                           :tasks to-submit
                           :opts  opts})
             (let [{:keys [id result exception start-tm] :as msg} (a/<!! out)]
               (cond exception (handle-exception msg (assoc stuff :pool pool) opts)
                     ;;start-tm  (recur (handle-started msg stuff opts))
                     :else     (recur (handle-success msg (assoc stuff :submitted to-submit) opts)))))))))))

(defn view [tasks]
  (loom.io/view (plan->graph tasks)))
