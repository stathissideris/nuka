(ns nuka.script.bash-test
  (:require [nuka.script.bash :refer :all]
            [nuka.script :refer [script]]
            [clojure.test :refer :all]))

(deftest test-render
  (is (= "ls -F" (render (script (ls [:F])))))
  (is (= "ls -F" (render (script (ls {:F true})))))
  (is (= "ls -F" (render (script (ls {:F true :X false})))))
  (is (= "ls -F 'my-folder'" (render (script (ls [:F] "my-folder")))))
  (is (= "ls $(echo 'src/')" (render (script (ls (echo "src/"))))))
  (is (= "ls -F $(echo $(echo 'src/'))" (render (script (ls :F (echo (echo "src/")))))))
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
                            (block (echo "Could not delete file!") (exit 1))))))))
