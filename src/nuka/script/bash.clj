(ns nuka.script.bash
  (:require [clojure.string :as string]
            [nuka.script :refer [single-quote double-quote render-flag-name]])
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

(defmulti render class)
(defmethod render nil [_] ::remove)
(defmethod render Script [{:keys [commands]}] (str (string/join "\n" (map render commands)) "\n"))
(defmethod render InlineBlock [{:keys [commands]}]
  (str "{" (apply str (interleave (map render commands) (repeat "; "))) "}"))
(defmethod render Pipe [{:keys [commands]}] (string/join " | " (map render commands)))
(defmethod render ChainAnd [{:keys [commands]}] (string/join " && " (map render commands)))
(defmethod render ChainOr [{:keys [commands]}] (string/join " || " (map render commands)))
(defmethod render Call [{:keys [cmd args]}] (let [args (string/join " " (remove #(= % ::remove) (map render args)))]
                                                 (str cmd (when-not (empty? args) (str " " args)))))
(defmethod render EmbeddedCall [{:keys [cmd]}] (str "$(" (render cmd) ")"))
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
