(ns nuka.script.java
  (:require [clojure.string :as string]
            [nuka.script :refer [render-flag-name]])
  (:import [nuka.script
            SingleQuotedArg
            DoubleQuotedArg
            NumericArg
            Flag
            NamedArg
            Call
            EmbeddedCall
            Script
            Raw
            Pipe
            ChainAnd
            ChainOr
            Reference
            Loop
            InlineBlock]))

(defn mangle-id [x]
  (if (string? x)
    x
    (string/replace (name x) "-" "_")))

(defmulti render class)
(defmethod render nil [_] ::remove)
(defmethod render Script [{:keys [commands]}] (map render commands))
(defmethod render InlineBlock [{:keys [commands]}]
  (str "{" (apply str (interleave (map render commands) (repeat "; "))) "}"))
(defmethod render Pipe [{:keys [commands]}] (flatten (interpose "|" (map render commands))))
(defmethod render ChainAnd [{:keys [commands]}] (flatten (interpose "&&" (map render commands))))
(defmethod render ChainOr [{:keys [commands]}] (flatten (interpose "||" (map render commands))))
(defmethod render Call [{:keys [cmd args]}] (cons (mangle-id cmd) (remove #(= % ::remove) (flatten (map render args)))))
(defmethod render EmbeddedCall [{:keys [cmd]}] ["$(" (render cmd) ")"])
(defmethod render Reference [{:keys [val]}] (str "$" val))
(defmethod render Loop [{:keys [binding coll commands]}]
  (format "for %s in %s; do\n%s\ndone"
          binding
          (render coll)
          (string/join "\n" (map #(str "  " (render %)) commands))))
(defmethod render SingleQuotedArg [{:keys [val]}] val)
(defmethod render DoubleQuotedArg [{:keys [val]}] val)
(defmethod render NumericArg [{:keys [val]}] (str val))
(defmethod render Raw [{:keys [val]}] val)
(defmethod render Flag [{:keys [val]}] (render-flag-name val))
(defmethod render NamedArg [{:keys [name val]}] [(render-flag-name name) (render val)])
