(ns nuka.planner
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

(defn tasks->graph [{:keys [tasks]}]
  (let [g (->> tasks
               (map (fn [[k {:keys [deps]}]] [k deps]))
               (into {})
               graph/digraph)]
    (reduce (fn [g [k task]]
              (attr/add-attr g k ::spec task))
            g
            (map vector (keys tasks)
                 (vals tasks)))))

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

(defn task-spec [g id]
  (assoc (attr/attr g id ::spec)
         :id id))

(defn select [env select-def select-fn]
  (reduce-kv (fn [m k v] (assoc m k (select-fn env v))) {} select-def))

(defn now []
  (Thread/imeInMillis))

(defn submit! [{:keys [pool out results task opts]}]
  (let [{:keys      [id in]
         function   :fn
         select-def :select} task
        {:keys [select-fn]}  opts]
    (if-not function
      (do
        (println "Dummy task" id "-- skipping")
        (future (a/>!! out (merge task {:result {}})))) ;; don't do it in the planning thread, that would be a race condition
      (let [input (util/deep-merge results in (select results select-def select-fn))]
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
        (future (a/>!! out (merge task {:start-tm (now)})))))))

(defn submit-all! [{:keys [pool out results tasks opts] :as args}]
  (when (seq tasks)
    (println "Starting" (pr-str (map :id tasks)))
    (doseq [t tasks]
      (submit! {:pool pool :out out :results results :task t :opts opts}))))

(defn- key-set [m]
  (-> m keys set))

(defn- validate-all-deps-resolved! [{:keys [tasks]}]
  (let [declared-tasks (key-set tasks)
        unknown-deps   (->> (for [[task-name {:keys [deps]}] tasks]
                              [task-name (set/difference (set deps) declared-tasks)])
                            (remove (comp empty? second))
                            (into {}))]
    (when-not (empty? unknown-deps)
      (throw (ex-info "Plan contains unresolved deps" unknown-deps)))))

(defn- validate-task-in-map [task-name {:keys [in deps]} declared-tasks]
  (let [in       (key-set in)
        deps     (set deps)
        not-deps (set/difference declared-tasks deps)]
    [(when (seq (set/intersection in deps))
       {:task           task-name
        :type           :certain-clash
        :offending-keys (set/intersection in deps)})
     (when (seq (set/intersection in not-deps))
       {:task          task-name
        :type          :probable-clash
        :offending-keys (set/intersection in not-deps)})]))

(defn- validate-input-clashes! [{:keys [tasks]}]
  (let [declared-tasks (key-set tasks)
        clashing-inputs (->> tasks
                             (mapcat (fn [[n task]] (validate-task-in-map n task declared-tasks)))
                             (remove nil?)
                             (vec))]
    (when-not (empty? clashing-inputs)
      (throw (ex-info "Plan contains tasks whose :in map will/may clash with results from other tasks"
                      {:clashes clashing-inputs})))))

(defn validate! [plan]
  (validate-all-deps-resolved! plan)
  (validate-input-clashes! plan))

(defn get-in-env [m path]
  (if (coll? path)
    (get-in m path)
    (get m path)))

(def default-opts
  {:threads   4
   :select-fn get-in-env
   :env       {}
   :logging   {:results true
               :clashes true}})

(defn execute
  ([plan]
   (execute plan nil))
  ([plan {:keys [state-atom logging] :as opts}]
   (validate! plan)
   (let [{:keys [threads env]
          :as opts} (merge default-opts opts)
         graph      (tasks->graph plan)
         _          (when (has-cycles? graph)
                      (throw (ex-info "Task graph has cycles" plan)))
         pool       (Executors/newFixedThreadPool threads)
         out        (a/chan)]
     (loop [stuff {:graph       graph
                   :in-progress #{}
                   :env         env
                   :state       {}}]
       (let [{:keys [graph in-progress env state]} stuff]
         (when state-atom (reset! state-atom state))
         (if (= (set (keys state)) (graph/nodes graph))
           (do
             (println "Task graph done!")
             state)
           (let [free (->> (set/difference
                            (-> graph free-tasks set)
                            in-progress)
                           (take threads)
                           (map (partial task-spec graph)))]
             (submit-all! {:pool    pool
                           :out     out
                           :results results
                           :tasks   free
                           :opts    opts})
             (let [{:keys [id result exception start-tm] :as msg} (a/<!! out)]
               (cond exception
                     (do
                       (println "Task" id "failed")
                       (println "Stopping thread pool...")
                       (.shutdown pool)
                       (throw (ex-info (str "Task " id " failed")
                                       {:msg         (.getMessage exception)
                                        :failed-task id
                                        :env         env
                                        :state       (update state ig merge msg)} exception)))

                     start-tm
                     (do
                       (println "Task" id "started")
                       (recur (assoc-in stuff [:state id] msg)))

                     :else
                     (do
                       (if (:results logging)
                         (println "Task" id "done. Result: " (pr-str result))
                         (println "Task" id "done."))
                       (recur
                        {:graph       (next-graph graph [id])
                         :in-progress (set/union in-progress (set (map :id free)))
                         :env         (util/deep-merge env result)
                         :state       (update state ig merge msg)})))))))))))

(defn view [tasks]
  (loom.io/view (tasks->graph tasks)))
