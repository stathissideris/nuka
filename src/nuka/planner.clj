(ns nuka.planner
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [loom.alg-generic :as generic]
            [loom.io]
            [loom.attr :as attr]
            [clojure.core.async :as a]
            [clojure.set :as set])
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

(defn strict-merge
  "Merge that fails on common keys. Adapted from merge-with."
  [& maps]
  (when (some identity maps)
    (let [merge-entry (fn [m e]
			                  (let [k (key e)
                              v (val e)]
			                    (if (contains? m k)
			                      (throw (ex-info (str "Map already contains key " (pr-str k)) {:m m :k k :new-value v}))
			                      (assoc m k v))))
          merge2 (fn [m1 m2]
		               (reduce merge-entry (or m1 {}) (seq m2)))]
      (reduce merge2 maps))))

(defn submit! [pool out results {:keys    [id in]
                                 function :fn
                                 :as      task}]
  (if-not function
    (do
      (println "Dummy task" id "-- skipping")
      (a/>!! out (merge task {:result {}})))
    (let [input (strict-merge results in)]
      (.submit pool
               (fn []
                 (try
                   (a/>!! out (merge task {:result (function input)}))
                   (catch Exception e
                     (a/>!! out (merge task {:exception e})))))))))

(defn submit-all! [pool out results tasks]
  (when (seq tasks)
    (println "Starting" (pr-str (map :id tasks)))
    (doseq [t tasks]
      (submit! pool out results t))))

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

(defn execute
  ([plan]
   (execute plan nil))
  ([plan {:keys [threads]
          :or   {threads 4}}]
   (validate! plan)
   (let [graph (tasks->graph plan)
         _     (when (has-cycles? graph)
                 (throw (ex-info "Task graph has cycles" plan))) ;;TODO more validation
         pool  (Executors/newFixedThreadPool threads)
         out   (a/chan (* threads 4))]
     (loop [g           graph
            in-progress #{}
            results     {}]
       (if (= (set (keys results)) (graph/nodes graph))
         (do
           (println "Task graph done!")
           results)
         (let [free (->> (set/difference
                          (-> g free-tasks set)
                          in-progress)
                         (take threads)
                         (map (partial task-spec g)))]
           (submit-all! pool out results free)
           (let [{:keys [id result exception] :as msg} (a/<!! out)]
             (if exception
               (do
                 (println "Task" id "failed")
                 (println "Stopping thread pool...")
                 (.shutdown pool)
                 (throw (ex-info (str "Task " id " failed") {:msg         (.getMessage exception)
                                                             :failed-task id
                                                             :results     results} exception)))
               (do
                 (println "Task" id "done")
                 (recur
                  (next-graph g [id])
                  (set/union in-progress (set (map :id free)))
                  (assoc results id result)))))))))))

(defn view [tasks]
  (loom.io/view (tasks->graph tasks)))





(comment
  (defn dummy-fn [msg]
    (fn dummy-inner [in]
      (let [x (+ 1000 (rand-int 2000))]
        (Thread/sleep x)
        {:duration x
         :in in})))

  (def g2
    {:tasks
     {:a {:deps [:b :c]
          :in   {:I_GET_AN_EXTRA_KEY true
                 :f                  10
                 :b                  10}
          :fn   (dummy-fn :a)}
      :b {:deps [:d]
          :fn   (dummy-fn :b)}
      :c {:deps [:d]
          :fn   (dummy-fn :c)}
      :d {:fn (dummy-fn :d)}

      :e {:deps [:f]
          :fn   (dummy-fn :e)}
      :f {:deps [:g]
          :fn   (dummy-fn :f)
          :in   {:c 10}}
      :g {:deps [:h]
          :fn   (dummy-fn :g)}
      :h {:fn (dummy-fn :h)}

      :i {:fn (dummy-fn :i)}
      :j {:fn (dummy-fn :j)}}})

  (def g3
    {:tasks
     {:a {:deps [:b :c]
          :in   {:I_GET_AN_EXTRA_KEY true}
          :fn   (dummy-fn :a)}
      :b {:deps [:d]
          :fn   (dummy-fn :b)}
      :c {:deps [:d]
          :fn   (dummy-fn :c)}
      :d {:fn (dummy-fn :d)}

      :e {:deps [:f]
          :fn   (dummy-fn :e)}
      :f {:deps [:g]
          :fn   (dummy-fn :f)}
      :g {:deps [:h]
          :fn   (dummy-fn :g)}
      :h {:fn (dummy-fn :h)}

      :i {:fn (dummy-fn :i)}
      :j {:fn (dummy-fn :j)}}})

  (def g4
    {:tasks
     {:a {:deps [:b :c]
          :fn   (dummy-fn :a)}
      :b {:deps [:d]
          :fn   (dummy-fn :b)}
      :c {:deps [:d]
          :fn   (dummy-fn :c)}
      :d {:fn (dummy-fn :d)}

      :e {:deps [:f]
          :fn   (dummy-fn :e)}
      :f {:deps [:g]
          :fn   (dummy-fn :f)}
      :g {:deps [:h]
          :fn   (fn [_] (throw (ex-info "I fail" {})))}
      :h {:fn (dummy-fn :h)}

      :i {:fn (dummy-fn :i)}
      :j {:fn (dummy-fn :j)}}})

  (def g5
    {:tasks
     {:a {:deps [:b :c]
          :fn   (dummy-fn :a)}
      :b {:deps [:d]
          :fn   (dummy-fn :b)}
      :c {:deps [:d]
          ;;:fn   (dummy-fn :c)
          }
      :d {:fn (dummy-fn :d)}

      :e {:deps [:f]
          :fn   (dummy-fn :e)}
      :f {:deps [:g]
          :fn   (dummy-fn :f)}
      :g {:deps [:h]
          :fn   (dummy-fn :g)}
      :h {:fn (dummy-fn :h)}

      :i {:fn (dummy-fn :i)}
      :j {:fn (dummy-fn :j)}}})

  (execute g2))


;; (comment
;;   (def g1-next (next-phase g1))
;;
;;   (->> g1-next graph/nodes (map (graph/successors g1-next)))
;;
;;   (-> g1 next-phase next-phase next-phase next-phase)
;;
;;   {:aliases [[systems.deploy :as d]]
;;    :data    {:taz    {... ...}
;;              :ingest {... ...}}
;;    :tasks
;;    {;; stop and migrate
;;     :stop-taz        {:impl d/stop-service
;;                       :args #ref :taz}
;;
;;     :stop-ingest     {:impl d/stop-service
;;                       :args #ref :ingest}
;;
;;
;;     :migrate-db      {:impl d/migrate-db
;;                       :deps [:stop-taz :stop-ingest]}
;;
;;     ;; taz
;;     :checkout-taz    {:impl d/checkout
;;                       :args #ref :taz}
;;
;;     :jar-taz         {:impl d/uberjar
;;                       :args #ref :taz
;;                       :deps [:checkout-taz]}
;;
;;     :copy-jar        {:impl d/copy-jar
;;                       :args #ref :taz
;;                       :deps [:jar-taz]}
;;
;;     :start-taz       {:impl d/start-service
;;                       :args #ref :taz
;;                       :deps [:copy-taz :migrate-db]}
;;
;;     ;; ingest
;;     :checkout-ingest {:impl d/checkout
;;                       :args #ref :ingest}
;;
;;     :jar-ingest      {:impl d/uberjar
;;                       :args #ref :ingest
;;                       :deps [:checkout-ingest]}
;;
;;     :copy-jar        {:impl d/copy-jar
;;                       :args #ref :ingest
;;                       :deps [:jar-ingest]}
;;
;;     :start-ingest    {:impl d/start-service
;;                       :args #ref :ingest
;;                       :deps [:copy-ingest :migrate-db]}
;;
;;     ;; dummy
;;     :deploy          {:deps [:start-taz :start-ingest]}}})
