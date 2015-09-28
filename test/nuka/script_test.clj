(ns nuka.script-test
  (:require [nuka.script :refer :all]
            [clojure.test :refer :all]))

(def process-script @#'nuka.script/process-script)

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

(deftest test-process-script
  (is (= '[script (nuka.script/pipe (nuka.script/command "ls") (nuka.script/command "grep" "foo"))]
         (process-script '(script (pipe (ls) (grep "foo"))))))
  (is (= '[script (nuka.script/command "ls" dir)]
         (process-script '(script (ls ~dir)))))
  (is (= '[script (nuka.script/command "ls" dir)]
         (process-script '(script (ls (clj dir))))))
  (is (= (->Script
          [(->Pipe [(->Command "ls" [])
                    (->Command "grep" [(->SingleQuotedArg "foo")])])])
         (script (pipe (ls) (grep "foo")))))
  (is (= (->Script
          [(->Loop 'x (->EmbeddedCommand (->Command "ls" [])) [(->Command "echo" [(->Reference 'x)])])])
         (script (doseq [x (ls)] (echo x)))))
  (is (= '[script (nuka.script/command "/tmp/script")] (process-script '(script ("/tmp/script")))))
  (is (= '[script (nuka.script/command s)] (process-script '(script (~s)))))
  (is (= '[script (nuka.script/command
                   "scp"
                   (merge {:i id-file} options)
                   (scp-file src)
                   (scp-file dest))]
         (process-script '(script (scp ~(merge {:i id-file} options) ~(scp-file src) ~(scp-file dest)))))))
