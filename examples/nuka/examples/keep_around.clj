(ns nuka.examples.keep-around
  (:require [clojure.core.async :refer [<!! >!!] :as async]
            [nuka.exec :as exec :refer [run-command run-script >no-err >print kill exit-code]]
            [nuka.script :as script :refer [script call q raw pipe]]))

;;You can keep processes such as awk and grep around and use them to
;;process individual lines of input on demand

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
  (do ;; *** awk ***

    ;;start the process
    (def awk-process (awk "print \"---> \" $2"))

    ;;attach a print consumer to it
    (>print awk-process)

    ;;send stuff to its STDIN and watch as awk prints out the output
    (>!! (:in awk-process) "foo bar")

    ;;... you can keep the process running for as long as you like ...

    (>!! (:in awk-process) "column1 column2 column3")
    
    ;;kill it when you are done
    (kill awk-process)
    )

  (do

    ;;same as above
    (def awk-process (awk "print \"---> \" $2"))

    ;;give it one line via STDIN, get one line via STDOUT
    ;;it's like an awk repl!
    (give-take awk-process "column1 column2 column3")
    (kill awk-process)
    )

  (do ;; *** grep ***
    (def grep-process (grep "bar"))
    (>print grep-process)
    
    (>!! (:in grep-process) "foo bar") ;;should be printed
    (>!! (:in grep-process) "foo does not match") ;;should not print anything

    (kill grep-process)
    )
  
  (do ;; *** cat ***
    (def cat-process (cat-stdin))
    (>print cat-process)

    (>!! (:in cat-process) "fooo")
    (>!! (:in cat-process) "baaaar")

    (kill cat-process)
    )
  )
