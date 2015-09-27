(ns nuka.script-test
  (:require [nuka.script :refer :all]
            [clojure.test :refer :all]))

;;(def interpret-flag @#'nuka.script/interpret-flag)

(deftest test-command
  (is (= (->Command "ls" [(->Flag :F)]) (command "ls" [:F])))
  (is (= (->Command "ls" [(->TextArg "-F")]) (command "ls" ["-F"])))
  (is (= (->Command "ls" [(->Flag :F)]) (command "ls" {:F true})))
  (is (= (command "ls" {:F true :X true})
         (command "ls" {:F true} {:X true})))
  (is (= (command "ls" {:F true :X false})
         (command "ls" {:F true})))
  (is (= (->Command "ls" [(->Flag "F")])  (command "ls" {"F" true})))
  (is (= (->Command "tar" [(->NamedArg :X (->TextArg "exclusion-file"))])
         (command "tar" {:X "exclusion-file"})))
  (is (= (->Command "tar" [(->NamedArg "X" (->TextArg "exclusion-file"))])
         (command "tar" {"X" "exclusion-file"})))
  (is (= (->Command "tar" [(->NamedArg "X" (->TextArg "exclusion-file"))
                           (->NamedArg "Y" (->NumericArg 4))])
         (command "tar" {"X" "exclusion-file" "Y" 4})))
  (is (= (->Command "ls" [(->Flag :F)
                          (->TextArg "my-folder")])
         (command "ls" [:F] "my-folder")))
  (is (= (->Command "ls" [(->EmbeddedCommand
                           (->Command "echo" [(->TextArg "src/")]))])
         (command "ls" (command "echo" "src/")))))

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
    (is (= "ls 'src/'" (render (let [dir "src/"] (script (ls ~dir)))))))) ;;unquoting supported!
