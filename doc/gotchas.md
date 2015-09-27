# Gotchas

## alts! does not play well if a channel is closed early

Because of that, there were several failed attempts to implement >print such as:

```
(defn >print [{:keys [cmd out err]}]
  (loop [limit 20]
    (let [[v c] (alts!! [out err] :default ::done)]
      (print c)
      (if (and (not= c :default) (> limit 0))
        (do
          (if (= c out)
            (println "OUT:" v)
            (println "ERR:" v))
          (recur (dec limit))))))
  nil)
```

Notice the `limit` to prevent an infinite loop. This was resolved by
implementing a variant of `core.async/merge` called `tagged-merge`.

Also see https://groups.google.com/forum/#!topic/clojure/TbRB5YWyLW0

