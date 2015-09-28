(ns nuka.script.java-test
  (:require [nuka.script.java :refer :all]
            [nuka.script :refer [script call pipe chain-and chain-or block] :as s]
            [clojure.test :refer :all]))

(deftest test-render
  (is (= [["ls" "-i"]] (-> (call :ls :i) script render))))

(deftest test-render
  (is (= [["ls" "-F"]] (render (script (call :ls [:F])))))
  (is (= [["ls" "-F"]] (render (script (call :ls {:F true})))))
  (is (= [["ls" "-F"]] (render (script (call :ls {:F true :X false})))))
  (is (= [["ls" "-F" "-l" "-a"]] (render (script (call :ls {:F true :X false} nil :l nil :a)))))
  (is (= [["ls" "-F" "my-folder"]] (render (script (call :ls [:F] "my-folder")))))
  ;; (is (= "ls $(call :echo 'src/')" (render (script (call :ls (call :echo "src/"))))))
  ;; (is (= "ls -F $(call :echo $(call :echo 'src/'))" (render (script (call :ls :F (call :echo (call :echo "src/")))))))
  (is (= [["ls" "src/"]] (render (let [dir "src/"] (script (call :ls dir))))))
  (is (= [["ls" "|" "grep" "foo"]] (render (script (pipe (call :ls) (call :grep "foo"))))))
  (is (= [["ls" "&&" "grep" "foo"]] (render (script (chain-and (call :ls) (call :grep "foo"))))))
  (is (= [["ls" "||" "grep" "foo"]] (render (script (chain-or (call :ls) (call :grep "foo"))))))
  (is (= [["ping" "-o" "-t" "2" "54.76.218.80"]] (render (script (call :ping {:o true :t 2} "54.76.218.80")))))
  (is (= [["ssh" "-i" "id-file" "user@hehe.com" "ls"]]
         (render (script (call :ssh {:i "id-file"} "user@hehe.com" "ls")))))
  (comment
    (is (= "for x in $(ls); do\n  echo $x\ndone" (render (script (s/for ['x (call :ls)] (call :echo 'x))))))
    (is (= "for x in $(ls); do\n  echo \"foo: $x\"\ndone"
           (render (script (s/for [x (call :ls)] (call :echo (qq "foo: $x")))))))
    (is (= "for x in $(ls -F); do\n  echo 'foo:'\n  echo $x\ndone"
           (render
            (script
             (s/for ['x (call :ls :F)]
               (call :echo "foo:")
               (call :echo 'x))))))
    (is (= "rm 'file' || {echo 'Could not delete file!'; exit 1; }"
           (render (script
                    (chain-or (call :rm "file")
                              (block (call :echo "Could not delete file!") (call :exit 1)))))))))
