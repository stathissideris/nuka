(ns nuka.exec
  (:require [clojure.string :as string]
            [clojure.core.async :refer [go >! <! <!! >!! go-loop chan alts! alts!! close! timeout] :as async]
            [nuka.script :as script :refer [script call q chain-and raw script? call?]]
            nuka.script.java)
  (:import [java.io InputStreamReader BufferedReader]))

(defrecord SystemProcess [out err out-reader err-reader result-channel control])

(def java-render nuka.script.java/render)

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

(defn run-command [cmd]
  (let [elements (cond (sequential? cmd) cmd
                       (call? cmd)       (-> cmd script java-render first)
                       (script? cmd)     (-> cmd java-render first)
                       :else             (throw (ex-info (str "Could not process passed command of type "
                                                              (.getName (type cmd)))
                                                         {:script cmd})))
        p (-> (Runtime/getRuntime) (.exec (into-array String elements)))
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

(defn kill
  "Attempts to kill the external process. Blocking."
  [{:keys [control]}]
  (>!! control :kill))

(defn exit-code
  "Attempts to retrieve the exit-code from the external
  process. Blocking."
  [{:keys [result-channel]}]
  (<!! result-channel))

(def wait-for exit-code)

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

(defn >no-err [process]
  (close! (:err process))
  process)

(defn closed-chan []
  (let [c (chan)]
    (close! c)
    c))

(defn >err->out [process]
  (assoc process
         :out (async/merge [(:out process) (:err process)])
         :err (closed-chan)))

(defn >out->err [process]
  (assoc process
         :out (closed-chan)
         :err (async/merge [(:err process) (:out process)])))

(defn >slurp [{:keys [cmd out err]}]
  (let [m (tagged-merge [out err])
        val
        (<!!
         (go-loop [lines []]
           (let [[line c] (<! m)]
             (cond
               (= c out) (recur (conj lines line))
               (= c err) (ex-info
                          (str "Error while running system command: " line)
                          {:error err :cmd cmd}) ;;don't throw, just create it
               :else lines))))]
    (if (ex-data val)
      (throw val)
      val)))
