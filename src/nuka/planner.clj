(ns nuka.planner
  (:require [loom.graph :as graph]
            [loom.alg :as alg]
            [loom.alg-generic :as generic]
            [loom.io :refer [view]]))

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

(defn leaves
  "Find all the leaves of a DAG graph (all the ones that don't have
  successors). This will find all the tasks that have no
  dependencies."
  [g]
  (remove (comp not-empty (graph/successors g)) (graph/nodes g)))

(defn next-phase [g]
  (apply graph/remove-nodes g (leaves g)))

(def g1-next (next-phase g1))

(->> g1-next graph/nodes (map (graph/successors g1-next)))

(-> g1 next-phase next-phase next-phase next-phase)

{:aliases [[systems.deploy :as d]]
 :data    {:taz    {...}
           :ingest {...}}
 :tasks
 {;; stop and migrate
  :stop-taz        {:impl d/stop-service
                    :args #ref :taz}

  :stop-ingest     {:impl d/stop-service
                    :args #ref :ingest}


  :migrate-db      {:impl d/migrate-db
                    :deps [:stop-taz :stop-ingest]}

  ;; taz
  :checkout-taz    {:impl d/checkout
                    :args #ref :taz}

  :jar-taz         {:impl d/uberjar
                    :args #ref :taz
                    :deps [:checkout-taz]}

  :copy-jar        {:impl d/copy-jar
                    :args #ref :taz
                    :deps [:jar-taz]}

  :start-taz       {:impl d/start-service
                    :args #ref :taz
                    :deps [:copy-taz :migrate-db]}

  ;; ingest
  :checkout-ingest {:impl d/checkout
                    :args #ref :ingest}

  :jar-ingest      {:impl d/uberjar
                    :args #ref :ingest
                    :deps [:checkout-ingest]}

  :copy-jar        {:impl d/copy-jar
                    :args #ref :ingest
                    :deps [:jar-ingest]}

  :start-ingest    {:impl d/start-service
                    :args #ref :ingest
                    :deps [:copy-ingest :migrate-db]}

  ;; dummy
  :deploy          {:deps [:start-taz :start-ingest]}}}
