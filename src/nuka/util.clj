(ns nuka.util)

(defn deep-merge
  [& vals]
  (if (every? (some-fn map? nil?) vals)
    (apply merge-with deep-merge vals)
    (last vals)))
