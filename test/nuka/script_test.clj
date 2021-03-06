(ns nuka.script-test
  (:require [nuka.script :refer :all]
            [clojure.test :refer :all]))

(deftest test-call
  (is (= (->Call :ls [(->Flag :F)]) (call :ls [:F])))
  (is (= (->Call :ls [(->SingleQuotedArg "-F")]) (call :ls ["-F"])))
  (is (= (->Call :ls [(->Flag :F)]) (call :ls {:F true})))
  (is (= (call :ls {:F true :X true})
         (call :ls {:F true} {:X true})))
  (is (= (call :ls {:F true :X false})
         (call :ls {:F true})))
  (is (= (->Call :ls [(->Flag "F")])  (call :ls {"F" true})))
  (is (= (->Call :tar [(->NamedArg :X (->SingleQuotedArg "exclusion-file"))])
         (call :tar {:X "exclusion-file"})))
  (is (= (->Call :tar [(->NamedArg "X" (->SingleQuotedArg "exclusion-file"))])
         (call :tar {"X" "exclusion-file"})))
  (is (= (->Call :tar [(->NamedArg "X" (->SingleQuotedArg "exclusion-file"))
                           (->NamedArg "Y" (->NumericArg 4))])
         (call :tar {"X" "exclusion-file" "Y" 4})))
  (is (= (->Call :ls [(->Flag :F)
                          (->SingleQuotedArg "my-folder")])
         (call :ls [:F] "my-folder")))
  (is (= (->Call :ls [(->EmbeddedCall
                           (->Call :echo [(->SingleQuotedArg "src/")]))])
         (call :ls (call :echo "src/"))))
  (is (= (->Pipe [(->Call :ls [])
                  (->Call :grep [(->SingleQuotedArg "foo")])])
         (pipe (call :ls)
               (call :grep ["foo"])))))
