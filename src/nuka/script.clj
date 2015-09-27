(ns nuka.script
  (:require [clojure.string :as string]
            [clojure.walk :as walk]))

(defrecord SingleQuotedArg [val])
(defrecord DoubleQuotedArg [val])
(defrecord NumericArg [val])
(defrecord Flag [val])
(defrecord NamedArg [name val])
(defrecord Command [cmd args])
(defrecord EmbeddedCommand [cmd])
(defrecord Script [commands])
(defrecord Raw [val])
(defrecord Pipe [commands])
(defrecord ChainAnd [commands])
(defrecord ChainOr [commands])
(defrecord Reference [val])
(defrecord Loop [binding coll commands])
(defrecord InlineBlock [commands])

(defn- render-flag-name [f]
  (when f
    (if (string? f) f
        (let [s (name f)]
          (if (or (.startsWith s "--")
                  (.startsWith s "-"))
            s
            (if (= 1 (count s))
              (str "-" s)
              (str "--" s)))))))

(defmulti parse-arg-set
  (fn [x] (cond (record? x) (class x)
                (map? x) :map
                (vector? x) :vector
                (string? x) :string
                (keyword? x) :keyword
                (number? x) :number
                (symbol? x) :symbol
                :else :default)))

(defmethod parse-arg-set :default [x] x)
(defmethod parse-arg-set Command [x] (->EmbeddedCommand x))
(defmethod parse-arg-set :symbol [x] (->Reference x))
(defmethod parse-arg-set :string [s] (SingleQuotedArg. s))
(defmethod parse-arg-set :number [x] (NumericArg. x))
(defmethod parse-arg-set :keyword [k] (Flag. k))
(defmethod parse-arg-set :vector [v] (map parse-arg-set v))
(defmethod parse-arg-set :map [m]
  (mapcat (fn [[k v]] (cond (true? v)   [(Flag. k)] ;;keys with true values are just flags
                            (false? v)  [] ;;keys with false values are skipped
                            (keyword v) [(NamedArg. k (parse-arg-set (name v)))]
                            :else       [(NamedArg. k (parse-arg-set v))])) m))

(defn raw [x] (->Raw x))

(defn command [cmd & arg-sets]
  (->Command cmd (mapcat (fn [x]
                           (let [parsed (parse-arg-set x)]
                             (if (seq? parsed) parsed [parsed]))) arg-sets)))

(defn pipe [& commands]
  (->Pipe commands))

(defn chain-and [& commands]
  (->ChainAnd commands))

(defn chain-or [& commands]
  (->ChainOr commands))

(defn block [& commands]
  (->InlineBlock commands))

(defn q [s]
  (->SingleQuotedArg s))

(defn qq [s]
  (->DoubleQuotedArg s))

(defn script* [& commands]
  (->Script commands))

(defn- unquote-form?
  "Tests whether the form is (clj ...) or (unquote ...) or ~expr."
  [form]
 (or (and (seq? form)
          (symbol? (first form))
          (= (symbol (name (first form))) 'clj))
     (and (seq? form) (= (first form) `unquote))))

(def special-instructions
  #{'pipe 'chain-and 'chain-or 'q 'qq 'block})

(defn- special-instruction-form? [form]
  (and (list? form) (some? (special-instructions (first form)))))

(defn- loop-form? [form]
  (and (list? form) (= 'doseq (first form))))

(defn- command-form?
  [form]
  (and (not (unquote-form? form))
       (not (special-instruction-form? form))
       (list? form)
       (symbol? (first form))))

(defmacro script [& commands]
  `(script*
    ~@(walk/prewalk
       (fn [form]
         (cond
           (unquote-form? form) (second form)
           (special-instruction-form? form) form
           (loop-form? form) (let [[_ [b coll] & commands] form]
                               `(->Loop '~b (->EmbeddedCommand ~coll) ~(vec commands)))
           (command-form? form) (concat (list 'command (str (first form)))
                                        (map (fn [x] (if (symbol? x) '(clj (quote ~x)) x)) (rest form)))
           :else form)) (vec commands))))

(defn single-quote [s]
  (str "'" s "'"))

(defn double-quote [s]
  (str "\"" s "\""))

(defmulti render class)
(defmethod render Script [{:keys [commands]}] (string/join "\n" (map render commands)))
(defmethod render InlineBlock [{:keys [commands]}]
  (str "{" (apply str (interleave (map render commands) (repeat "; "))) "}"))
(defmethod render Pipe [{:keys [commands]}] (string/join " | " (map render commands)))
(defmethod render ChainAnd [{:keys [commands]}] (string/join " && " (map render commands)))
(defmethod render ChainOr [{:keys [commands]}] (string/join " || " (map render commands)))
(defmethod render Command [{:keys [cmd args]}] (let [args (string/join " " (map render args))]
                                                 (str cmd (when-not (empty? args) (str " " args)))))
(defmethod render EmbeddedCommand [{:keys [cmd]}] (str "$(" (render cmd) ")"))
(defmethod render Reference [{:keys [val]}] (str "$" val))
(defmethod render Loop [{:keys [binding coll commands]}]
  (format "for %s in %s; do\n%s\ndone"
          binding
          (render coll)
          (string/join "\n" (map #(str "  " (render %)) commands))))
(defmethod render SingleQuotedArg [{:keys [val]}] (single-quote val))
(defmethod render DoubleQuotedArg [{:keys [val]}] (double-quote val))
(defmethod render NumericArg [{:keys [val]}] (str val))
(defmethod render Raw [{:keys [val]}] val)
(defmethod render Flag [{:keys [val]}] (render-flag-name val))
(defmethod render NamedArg [{:keys [name val]}] (str (render-flag-name name) " " (render val)))
