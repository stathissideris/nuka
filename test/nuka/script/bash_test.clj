(ns nuka.script.bash-test
  (:require [nuka.script.bash :refer :all]
            [nuka.script :refer [script qq call pipe chain-and chain-or block for' if'] :as s]
            [clojure.test :refer :all]))

(deftest test-render
  (is (= "ls -F\n" (render (script (call :ls [:F])))))
  (is (= "ls -F\n" (render (script (call :ls {:F true})))))
  (is (= "ls -F\n" (render (script (call :ls {:F true :X false})))))
  (is (= "ls -F -l -a\n" (render (script (call :ls {:F true :X false} nil :l nil :a)))))
  (is (= "ls -F 'my-folder'\n" (render (script (call :ls [:F] "my-folder")))))
  (is (= "ls $(echo 'src/')\n" (render (script (call :ls (call :echo "src/"))))))
  (is (= "ls -F $(echo $(echo 'src/'))\n" (render (script (call :ls :F (call :echo (call :echo "src/")))))))
  (is (= "ls 'src/'\n" (render (let [dir "src/"] (script (call :ls dir))))))
  (is (= "ls | grep 'foo'\n" (render (script (pipe (call :ls) (call :grep "foo"))))))
  (is (= "ls && grep 'foo'\n" (render (script (chain-and (call :ls) (call :grep "foo"))))))
  (is (= "ls || grep 'foo'\n" (render (script (chain-or (call :ls) (call :grep "foo"))))))
  (is (= "for x in $(ls); do\n  echo \"$x\"\ndone\n" (render (script (for' ['x (call :ls)] (call :echo 'x))))))
  (is (= "for x in $(ls); do\n  echo \"foo: $x\"\ndone\n"
         (render (script (for' ['x (call :ls)] (call :echo (qq "foo: $x")))))))
  (is (= "for x in $(ls -F); do\n  echo 'foo:'\n  echo \"$x\"\ndone\n"
         (render
          (script
           (for' ['x (call :ls :F)]
             (call :echo "foo:")
             (call :echo 'x))))))
  (is (= "rm 'file' || {echo 'Could not delete file!'; exit 1; }\n"
         (render (script
                  (chain-or (call :rm "file")
                            (block (call :echo "Could not delete file!") (call :exit 1))))))))
