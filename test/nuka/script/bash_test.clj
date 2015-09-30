(ns nuka.script.bash-test
  (:require [clojure.string :as string]
            [nuka.script.bash :refer :all]
            [nuka.script :refer [script qq call pipe chain-and chain-or block for* if* assign function bind] :as s]
            [clojure.test :refer :all]))

(defn ex [& coll]
  (str (lines coll)))

(def r (comp render script))

(deftest test-render
  (is (= "ls -F" (r (call :ls [:F]))))
  (is (= "ls -F" (r (call :ls {:F true}))))
  (is (= "ls -F" (r (call :ls {:F true :X false}))))
  (is (= "ls -F -l -a" (r (call :ls {:F true :X false} nil :l nil :a))))
  (is (= "ls -F 'my-folder'" (r (call :ls [:F] "my-folder"))))
  (is (= "ls -F 'my-folder'" (r (call :ls [:F] (list "my-folder")))))
  (is (= "ls -F 'my-folder'" (r (call :ls {:F true} (list "my-folder")))))
  (is (= "ls -F 'my-folder'" (r (call :ls (map identity [:F]) (list "my-folder")))))
  (is (= "ls $(echo 'src/')" (r (call :ls (call :echo "src/")))))
  (is (= "ls -F $(echo $(echo 'src/'))" (r (call :ls :F (call :echo (call :echo "src/"))))))
  (is (= "ls 'src/'" (render (let [dir "src/"] (script (call :ls dir))))))
  (is (= "ls | grep 'foo'" (r (pipe (call :ls) (call :grep "foo")))))
  (is (= "ls && grep 'foo'" (r (chain-and (call :ls) (call :grep "foo")))))
  (is (= "ls || grep 'foo'" (r (chain-or (call :ls) (call :grep "foo")))))
  (is (= (ex
          "local x=5"
          "echo \"$x\"")
         (r (assign 'x 5) (call :echo 'x))))
  (is (= (ex
          "for x in $(ls); do"
          "  echo \"$x\""
          "done")
         (r (for* ['x (call :ls)] (call :echo 'x)))))
  (is (= (ex
          "for x in $(ls); do"
          "  echo \"foo: $x\""
          "done")
         (r (for* ['x (call :ls)] (call :echo (qq "foo: $x"))))))
  (is (= (ex
          "for x in $(ls -F); do"
          "  echo 'foo:'"
          "  echo \"$x\""
          "done")
         (r
          (for* ['x (call :ls :F)]
                (call :echo "foo:")
                (call :echo 'x)))))
  (is (= (ex
          "foo() {"
          "  local a=\"$1\""
          "  local b=\"$2\""
          "  local x=\"$3\""
          "  echo \"$a\""
          "  echo \"$b\""
          "  echo \"$x\""
          "}"
          "foo 1 2 3")
         (r (function :foo ['a 'b 'x]
              (call :echo 'a)
              (call :echo 'b)
              (call :echo 'x))
            (call :foo 1 2 3))))
  (is (= (ex
          "bind_block_000() {"
          "  local a=\"$1\""
          "  local b=\"$2\""
          "  echo \"$a\""
          "  echo \"$b\""
          "}"
          "bind_block_000 5 6")
         (with-redefs [gensym (constantly "bind_block_000")]
           (r (bind ['a 5 'b 6]
                    (call :echo 'a)
                    (call :echo 'b))))))
  (is (= "rm 'file' || {echo 'Could not delete file!'; exit 1; }"
         (r (chain-or (call :rm "file")
                      (block (call :echo "Could not delete file!") (call :exit 1))))))
  )
