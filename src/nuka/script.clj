(ns nuka.script
  (:require [clojure.walk :as walk]))

(defrecord SingleQuotedArg [val])
(defrecord DoubleQuotedArg [val])
(defrecord NumericArg [val])
(defrecord Flag [val])
(defrecord NamedArg [name val])
(defrecord Call [cmd args])
(defrecord EmbeddedCall [cmd])
(defrecord Script [commands])
(defrecord Raw [val])
(defrecord Pipe [commands])
(defrecord ChainAnd [commands])
(defrecord ChainOr [commands])
(defrecord Reference [val])
(defrecord Loop [binding coll commands])
(defrecord IfThenElse [test then else])
(defrecord Assignment [name value])
(defrecord Function [name args commands])
(defrecord InlineBlock [commands])

(defn render-flag-name [f]
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
  (fn [x] (cond (record? x)      (class x)
                (map? x)         :map
                (or (seq? x)
                    (vector? x)) :seq
                (string? x)      :string
                (keyword? x)     :keyword
                (number? x)      :number
                (symbol? x)      :symbol
                :else            :default)))

(defmethod parse-arg-set :default [x] x)
(defmethod parse-arg-set Call [x] (->EmbeddedCall x))
(defmethod parse-arg-set :symbol [x] (->Reference x))
(defmethod parse-arg-set :string [s] (SingleQuotedArg. s))
(defmethod parse-arg-set :number [x] (NumericArg. x))
(defmethod parse-arg-set :keyword [k] (Flag. k))
(defmethod parse-arg-set :seq [v] (map parse-arg-set v))
(defmethod parse-arg-set :map [m]
  (mapcat (fn [[k v]] (cond (true? v)   [(Flag. k)] ;;keys with true values are just flags
                            (false? v)  [] ;;keys with false values are skipped
                            (keyword v) [(NamedArg. k (parse-arg-set (name v)))]
                            :else       [(NamedArg. k (parse-arg-set v))])) m))

(defn raw [x] (->Raw x))

(defn call [cmd & arg-sets]
  (->Call cmd (mapcat (fn [x]
                        (let [parsed (parse-arg-set x)]
                          (if (seq? parsed) parsed [parsed]))) arg-sets)))
(def cmd call)

(defn for* [[binding coll] & commands]
  (->Loop binding (->EmbeddedCall coll) commands))

(defn function [name args & commands]
  (->Function name args commands))

(defn splice-vector-commands [coll]
  (mapcat #(if (sequential? %) % [%]) coll))

(defn block [& commands]
  (->InlineBlock (splice-vector-commands commands)))

(defn bind [bindings & commands]
  (let [bindings   (partition 2 bindings)
        block-name (gensym "bind_block_")]
    [(apply function block-name (mapv first bindings) commands)
     (call block-name (mapv second bindings))]))

(defn if* [test then & [else]]
  (->IfThenElse test then else))

(defn assign [name value]
  (->Assignment name (parse-arg-set value)))

(defn pipe [& commands]
  (->Pipe commands))

(defn chain-and [& commands]
  (->ChainAnd commands))

(defn chain-or [& commands]
  (->ChainOr commands))

(defn q [s]
  (->SingleQuotedArg s))

(defn qq [s]
  (->DoubleQuotedArg s))

(defn raw [s]
  (->Raw s))

(defn script [& commands]
  (->Script (splice-vector-commands commands)))

(defn single-quote [s]
  (str "'" s "'"))

(defn double-quote [s]
  (str "\"" s "\""))

(defn script? [x]
  (instance? Script x))

(defn call? [x]
  (instance? Call x))
