(ns nuka.script-test
  (:require [nuka.script :refer :all]
            [clojure.test :refer :all]))

;;(def interpret-flag @#'nuka.script/interpret-flag)

(deftest test-command
  (is (= (->Command "ls" [(->Flag :F)]) (command "ls" [:F])))
  (is (= (->Command "ls" [(->SingleQuotedArg "-F")]) (command "ls" ["-F"])))
  (is (= (->Command "ls" [(->Flag :F)]) (command "ls" {:F true})))
  (is (= (command "ls" {:F true :X true})
         (command "ls" {:F true} {:X true})))
  (is (= (command "ls" {:F true :X false})
         (command "ls" {:F true})))
  (is (= (->Command "ls" [(->Flag "F")])  (command "ls" {"F" true})))
  (is (= (->Command "tar" [(->NamedArg :X (->SingleQuotedArg "exclusion-file"))])
         (command "tar" {:X "exclusion-file"})))
  (is (= (->Command "tar" [(->NamedArg "X" (->SingleQuotedArg "exclusion-file"))])
         (command "tar" {"X" "exclusion-file"})))
  (is (= (->Command "tar" [(->NamedArg "X" (->SingleQuotedArg "exclusion-file"))
                           (->NamedArg "Y" (->NumericArg 4))])
         (command "tar" {"X" "exclusion-file" "Y" 4})))
  (is (= (->Command "ls" [(->Flag :F)
                          (->SingleQuotedArg "my-folder")])
         (command "ls" [:F] "my-folder")))
  (is (= (->Command "ls" [(->EmbeddedCommand
                           (->Command "echo" [(->SingleQuotedArg "src/")]))])
         (command "ls" (command "echo" "src/"))))
  (is (= (->Pipe [(->Command "ls" [])
                  (->Command "grep" [(->SingleQuotedArg "foo")])])
         (pipe (command "ls")
               (command "grep" ["foo"])))))

(deftest test-script
  (is (= '(nuka.script/script* (nuka.script/pipe (nuka.script/command "ls") (nuka.script/command "grep" "foo")))
         (macroexpand '(script (pipe (ls) (grep "foo"))))))
  (is (= '(nuka.script/script* (nuka.script/command "ls" dir))
         (macroexpand '(script (ls ~dir)))))
  (is (= '(nuka.script/script* (nuka.script/command "ls" dir))
         (macroexpand '(script (ls (clj dir))))))
  (is (= (->Script
          [(->Pipe [(->Command "ls" [])
                    (->Command "grep" [(->SingleQuotedArg "foo")])])])
         (script (pipe (ls) (grep "foo")))))
  (is (= (->Script
          [(->Loop 'x (->EmbeddedCommand (->Command "ls" [])) [(->Command "echo" [(->Reference 'x)])])])
         (script (doseq [x (ls)] (echo x))))))

(deftest test-render
  (let [r (fn [& commands] (render (apply script* commands)))]
    (is (= "ls -F" (r (command "ls" [:F]))))
    (is (= "ls -F" (r (command "ls" {:F true}))))
    (is (= "ls -F" (r (command "ls" {:F true :X false}))))
    (is (= "ls -F 'my-folder'" (r (command "ls" [:F] "my-folder"))))
    (is (= "ls $(echo 'src/')" (r (command "ls" (command "echo" "src/")))))
    (is (= "ls -F $(echo $(echo 'src/'))" (render (script (ls :F (echo (echo "src/")))))))
    (is (= "ls -F $(echo $(echo 'src/'))" (render
                                           (let [dir "src/"]
                                             (script*
                                              (command "ls" :F (command "echo" (command "echo" dir))))))))
    (is (= "ls 'src/'" (render (let [dir "src/"] (script (ls ~dir)))))) ;;unquoting supported!
    (is (= "ls 'src/'" (render (let [dir "src/"] (script (ls (clj dir))))))) ;;also with clj
    (is (= "ls | grep 'foo'" (render (script (pipe (ls) (grep "foo"))))))
    (is (= "ls && grep 'foo'" (render (script (chain-and (ls) (grep "foo"))))))
    (is (= "ls || grep 'foo'" (render (script (chain-or (ls) (grep "foo"))))))
    (is (= "for x in $(ls); do\n  echo $x\ndone" (render (script (doseq [x (ls)] (echo x))))))
    (is (= "for x in $(ls); do\n  echo \"foo: $x\"\ndone"
           (render (script (doseq [x (ls)] (echo (qq "foo: $x")))))))
    (is (= "for x in $(ls -F); do\n  echo 'foo:'\n  echo $x\ndone"
           (render
            (script
             (doseq [x (ls :F)]
               (echo "foo:")
               (echo x))))))
    (is (= "rm 'file' || {echo 'Could not delete file!'; exit 1; }"
           (render (script
                    (chain-or (rm "file")
                              (block (echo "Could not delete file!") (exit 1))))))))) 
