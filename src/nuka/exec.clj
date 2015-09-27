(ns nuka.exec
  (:require [clojure.string :as string]
            [clojure.core.async :refer [go >! <! <!! >!! go-loop chan alts! alts!! close! timeout] :as async])
  (:import [java.io InputStreamReader BufferedReader]))

(defn spool
  "Take a sequence and puts each value on a channel and returns the channel.
   If no channel is provided, an unbuffered channel is created. If the
   sequence ends, the channel is closed."
  ([s c]
     (async/go
      (loop [[f & r] s]
        (if f
          (do
            (async/>! c f)
            (recur r))
          (async/close! c))))
     c)
  ([s]
     (spool s (async/chan))))

(defn line-channel [reader]
  (spool (line-seq reader)))

(defn run-command [command]
  (let [p (-> (Runtime/getRuntime) (.exec command))
        out (->> p .getInputStream InputStreamReader. BufferedReader. line-channel)
        err (->> p .getErrorStream InputStreamReader. BufferedReader. line-channel)]
    {:cmd command
     :out out
     :err err}))

(defn tagged-merge
  "Takes a collection of source channels and returns a channel which
  contains all values taken from them. Each value of the new channels
  is retrieved \"tagged\" by original channel it came from, as [v c],
  similarly to alts!. The returned channel will be unbuffered by
  default, or a buf-or-n can be supplied. The channel will close after
  all the source channels have closed."
  ([chs] (tagged-merge chs nil))
  ([chs buf-or-n]
     (let [out (chan buf-or-n)]
       (go-loop [cs (vec chs)]
         (if (pos? (count cs))
           (let [[v c] (alts! cs)]
             (if (nil? v)
               (recur (filterv #(not= c %) cs))
               (do (>! out [v c])
                   (recur cs))))
           (close! out)))
       out)))

(defn >print [{:keys [cmd out err]}]
  (println "CMD:" cmd)
  (let [m (tagged-merge [out err])]
    (go-loop []
      (if-let [[line c] (<! m)]
        (do
          (if (= c out)
            (println "OUT:" line)
            (println "ERR:" line))
          (recur))
        (println "END:" cmd))))
  nil)

(defn >slurp [{:keys [cmd out err]}]
  (let [m (tagged-merge [out err])]
    (<!!
     (go-loop [lines []]
       (let [[line c] (<! m)]
         (cond
           (= c out) (recur (conj lines line))
           (= c err) (throw (ex-info (str "Error while running system command:" line) {:error err :cmd cmd}))
           :else lines))))))
