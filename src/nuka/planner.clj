(ns nuka.planner
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [loom.alg-generic :as generic]
            [loom.io :refer [view]]
            [loom.attr :as attr]
            [clojure.core.async :as a])
  (:import [java.util.concurrent Executors]))

(def g1 (graph/digraph
         [1 2]
         [2 3]
         {3 [4]
          5 [6 7]}
         [7 10]
         [6 10]
         7 8 9))

(def c1 (graph/digraph [1 2] [2 3] [3 4] [4 1]))

(defn has-cycles? [g]
  (not (alg/topsort g)))

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

(defn rand-task
  "Picks a random task out of the tasks that can be executed (one that
  has no dependencies)."
  [g]
  (let [l (free-tasks g)]
    (when (seq l)
      (rand-nth l))))

(defn dummy-fn [msg]
  (fn [in]
    (let [x (rand-int 2000)]
      (Thread/sleep x)
      (println msg "done")
      x)))

(defn task-spec [g id]
  (assoc (attr/attr g id ::spec)
         :id id
         :fn (dummy-fn id)))

(defn submit! [pool out results {:keys    [id]
                                function :fn
                                :as      task}]
  (println "Running task" id)
  (.submit pool (fn [] (a/>!! out (merge task {:result (function results)})))))

(defn submit-all! [pool out results tasks]
  (doseq [t tasks]
    (submit! pool out results t)))

(defn drain! [ch n]
  (when (pos? n)
    (prn (a/<!! ch))
    (recur ch (dec n))))

(defn chan-to-vec [ch n]
  (loop [res []
         n   n]
    (if (pos? n)
      (recur (conj res (a/<!! ch)) (dec n))
      res)))

(defn execute [g {:keys [threads]}]
  (let [pool (Executors/newFixedThreadPool threads)
        out  (a/chan)]

    (loop [g       g
           results {}]
      (prn {:results results})
      (let [free (map (partial task-spec g) (take threads (free-tasks g)))]
        (if (empty-graph? g)
          (println "Done!")
          (do
            (submit-all! pool out results free)
            ;;(drain! out (count free))
            (recur (next-graph g (map :id free))
                   (->> (chan-to-vec out (count free))
                        (map (juxt :id :result))
                        (into {})
                        (merge results)))))))))

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
