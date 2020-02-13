(ns nuka.plan
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [loom.alg-generic :as generic]
            [loom.io]
            [loom.attr :as attr]
            [clojure.core.async :as a]
            [clojure.set :as set]
            [nuka.util :as util])
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

(defn select [env select-def select-fn]
  (reduce-kv (fn [m k v] (assoc m k (select-fn env v))) {} select-def))

(defn- now []
  (System/currentTimeMillis))

(defn submit! [{:keys [pool out env task opts]}]
  (let [{:keys      [id in]
         function   :fn
         select-def :select} task
        {:keys [select-fn]}  opts]
    (if-not function
      (do
        ((-> opts :logging :log-fn) "Dummy task" id "-- skipping")
        (future (a/>!! out (merge task {:result {}})))) ;; don't do it in the planning thread, that would be a race condition
      (let [input (util/deep-merge env in (when select-def (select env select-def select-fn)))]
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

(defn execute
  ([plan]
   (execute plan nil))
  ([plan opts]
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
