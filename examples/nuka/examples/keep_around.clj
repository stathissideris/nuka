(ns nuka.examples.keep-around
  (:require [clojure.core.async :refer [<!! >!!] :as async]
            [nuka.exec :as exec :refer [run-command run-script >no-err >print kill exit-code]]
            [nuka.script :as script :refer [script call q raw pipe]]))

;;The weird options in awk and grep are there because they buffer
;;their output and you wouldn't get anything printed.
;;
;;See http://www.perkin.org.uk/posts/how-to-fix-stdio-buffering.html

(defn awk [code]
  (run-command (script (call :awk (q (format "{%s; system(\"\")}" code))))))

(defn grep [match]
  (run-command (script (call :grep {:line-buffered true} (q match)))))

(defn cat-stdin []
  (run-script (call :cat (raw "-"))))

(defn give-take [process input]
  (>!! (:in process) input)
  (<!! (:out process)))

(comment
  (do
    (def cat-process (cat-stdin))
    (>print cat-process)
    (>!! (:in cat-process) "fooo")
    (>!! (:in cat-process) "baaaar")
    (kill cat-process)
    )
  (do
    (def awk-process (awk "print \"---> \" $2"))
    (>print awk-process)
    (>!! (:in awk-process) "foo bar")
    (kill awk-process)
    )
  (do
    (def awk-process (awk "print \"---> \" $2"))
    (give-take awk-process "column1 column2 column3")
    (kill awk-process)
    )
  (do
    (def grep-process (grep "bar"))
    (>print grep-process)
    (>!! (:in grep-process) "foo bar")
    (>!! (:in grep-process) "foo does not match")
    (kill grep-process)
    )
  (do
    (def awk-process (awk "{print $2}"))
    ;;(println (format "awk says: '%s'" (give-take awk-process "a b")))
    (println (format "awk says:"))
    (println (give-take awk-process "a b"))
    (kill awk-process)
    (println "awk killed")
    )
  )
