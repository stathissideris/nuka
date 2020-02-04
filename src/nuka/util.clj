(ns nuka.util)

(defn deep-merge [& maps]
  (apply
    (fn m [& maps]
      (if (every? map? maps)
        (apply merge-with m maps)
        (apply merge maps)))
    maps))
