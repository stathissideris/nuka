(ns nuka.exec
  (:require [clojure.string :as string]
            [clojure.core.async :refer [go >! <! <!! >!! go-loop chan alts! alts!! close! timeout] :as async]
            [nuka.script :as script :refer [script call q chain-and raw script? call?]]
            [nuka.script.java]
            [nuka.script.bash]
            [clojure.term.colors :as c])
  (:import [java.io InputStreamReader BufferedReader OutputStreamWriter BufferedWriter PrintWriter]))

(defrecord SystemProcess [out err out-reader err-reader result-channel control])

(def java-render nuka.script.java/render)
(def bash-render nuka.script.bash/render)

(defmacro try-or-nil [& code]
  `(try
     ~@code
     (catch Exception ~'_ nil)))

(defn read-line-channel [reader]
  (let [c (chan 1024)]
    (go-loop []
      (if-let [line (try-or-nil (.readLine reader))]
        (do
          (>! c line)
          (recur))
        (do
          (.close reader)
          (close! c))))
    c))

(defn write-line-channel [writer]
  (let [c (chan 1024)]
    (go-loop []
      (if-let [line (<! c)]
        (do
          (.println writer line)
          (.flush writer)
          (recur))
        (do
          (.close writer)
          (close! c))))
    c))

(defn- try-exit-value [p]
  (try-or-nil (.exitValue p)))

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
  (let [elements   (cond (sequential? cmd) cmd
                         (call? cmd)       (-> cmd script java-render)
                         (script? cmd)     (-> cmd java-render)
                         :else             (throw (ex-info (str "Could not process passed command of type "
                                                                (.getName (type cmd)))
                                                           {:script cmd})))
        p          (-> (Runtime/getRuntime) (.exec (into-array String elements)))
        out-reader (-> p .getInputStream InputStreamReader. BufferedReader.)
        out        (read-line-channel out-reader)
        err-reader (-> p .getErrorStream InputStreamReader. BufferedReader.)
        err        (read-line-channel err-reader)
        in-writer  (-> p .getOutputStream OutputStreamWriter. PrintWriter.)
        ;;in-writer  (-> p .getOutputStream)
        in         (write-line-channel in-writer)]
    (map->SystemProcess
     {:cmd            elements
      :in             in
      :out            out
      :err            err
      :in-writer      in-writer
      :out-reader     out-reader
      :err-reader     err-reader
      :result-channel (process-exit-channel p)
      :control        (process-control-channel p)})))

(def exec run-command)

(defn clean-up
  [{:keys [in out err in-writer out-reader err-reader result-channel control]}]
  (doseq [c [in out err result-channel control]]
    (close! c))
  (doseq [c [in-writer out-reader err-reader]]
    (.close c)))

(defn kill
  "Attempts to kill the external process. Returns false if the process
  had already been killed. Blocking."
  [{:keys [control] :as process}]
  (let [result (>!! control :kill)]
    (clean-up process)
    result))

(defn exit-code
  "Attempts to retrieve the exit-code from the external
  process. Blocking."
  [{:keys [result-channel]}]
  (<!! result-channel))

(defn wait
  ([proc]
   (wait-for proc {:zero-throw false}))
  ([proc {:keys [zero-throw]}]
   (if-not (sequential? proc)
     (wait-for [proc])
     (loop [cs proc
            vs []]
       (let [[v p]   (async/alts!! (map :result-channel cs))
             process (as-> cs $
                       (filter #(= p (:result-channel %)) $)
                       (first $)
                       (assoc $ :exit-code v))
             _       (when (and zero-throw (not (zero? v)))
                       (throw (ex-info "Process failed!" process)))
             cs      (filterv #(not= p (:result-channel %)) cs)
             vs      (conj vs process)]
         (if (seq cs)
           (recur cs vs)
           vs))))))

(defn run-script [scr]
  (let [scr          (if (string? scr) scr (bash-render scr))
        local-script "/tmp/script" ;;TODO make unique
        ]
    (spit local-script (str scr "\n"))
    (wait-for (run-command (call :chmod (raw "+x") local-script)))
    (println "Running script:")
    (println scr)
    (let [process (run-command (call local-script))]
      (run-command (call :rm local-script))
      process)))

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

(defn >print
  ([process]
   (>print process nil))
  ([{:keys [cmd out err] :as process}
    {:keys [color] :or {color true}}]
   (println (c/magenta "CMD:" cmd))
   (let [m (tagged-merge [out err])]
     (go-loop []
       (if-let [[line c] (<! m)]
         (do
           (if (= c out)
             (println (c/white line))
             (println (c/red line)))
           (recur))
         (println (c/magenta "END:" cmd)))))
   process))

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
                          {:error err :cmd cmd}) ;;don't throw, just create it, otherwise it will be silent
               :else lines))))]
    (if (ex-data val)
      (throw val)
      val)))
