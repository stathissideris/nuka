(ns nuka.exec-test
  (:require [nuka.exec :refer :all]
            [clojure.test :refer :all]))

(deftest test-seq
  (is (= ["1" "2" "3"] (>slurp (run-command ["seq" "3"])))))
