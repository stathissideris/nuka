(ns nuka.exec
  (:require [clojure.string :as string]
            [clojure.core.async :refer [go >! <! <!! >!! go-loop chan alts! alts!! close! timeout] :as async])
  (:import [java.io InputStreamReader BufferedReader]))

(defrecord SystemProcess [out err out-reader err-reader result-channel control])

(comment
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

 (defn read-line-channel [reader]
   (spool (line-seq reader))))

(defn read-line-channel [reader]
  (let [c (chan 1024)]
    (go-loop []
      (if-let [line (.readLine reader)]
        (do
          (>! c line)
          (recur))
        (do
          (.close reader)
          (close! c))))
    c))

(defn- try-exit-value [p]
  (try (.exitValue p)
       (catch Exception _ nil)))

(defn process-control-channel [p]
  (let [c (chan)]
    (go-loop []
      (when-let [message (<! c)]
        (when (= :kill message)
          (.destroy p)
          (close! c))))
    c))

(defn process-exit-channel [p]
  (let [c (chan)]
    (go-loop []
      (if-let [v (try-exit-value p)]
        (>! c v)
        (do
          (<! (timeout 50))
          (recur))))
    c))

(defn run-command [elements]
  (let [p (-> (Runtime/getRuntime) (.exec (into-array String elements)))
        out-reader (->> p .getInputStream InputStreamReader. BufferedReader.)
        out (read-line-channel out-reader)
        err-reader (->> p .getErrorStream InputStreamReader. BufferedReader.)
        err (read-line-channel err-reader)]
    (map->SystemProcess
     {:cmd (pr-str elements)
      :out out
      :err err
      :out-reader out-reader
      :err-reader err-reader
      :result-channel (process-exit-channel p)
      :control (process-control-channel p)})))

(defn kill-process
  "Attempts to kill the external process. Blocking."
  [{:keys [control]}]
  (>!! control :kill))

(defn exit-code
  "Attempts to retrieve the exit-code from the external
  process. Blocking."
  [{:keys [result-channel]}]
  (<!! result-channel))

(defn clean-up
  [{:keys [out err out-reader err-reader result-channel control]}]
  (doseq [c [out err result-channel control]]
    (close! c))
  (doseq [c [out-reader err-reader]]
    (.close c)))

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
