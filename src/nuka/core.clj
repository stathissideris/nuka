(ns nuka.core
  (:require [nuka.exec :as exec :refer [exec >print >slurp >no-err >err->out kill exit-code wait]]
            [nuka.script :as script :refer [script call q chain-and raw]]
            [nuka.network :refer [ping scp]]
            [nuka.remote-exec :as remote :refer [script-on]]))

(def ssh-no-authenticity
  [:o "UserKnownHostsFile=/dev/null"
   :o "StrictHostKeyChecking=no"])

(def base-machine
  {:user    "ubuntu"
   :id-file "eu-ec2-keypair-new.pem"
   :cms-dir "/home/ubuntu/chauhan/cms/"})

(def dev-box
  (merge
   base-machine
   {:name "dev"
    :host "1.2.3.4"}))

(def prod-box
  (merge
   base-machine
   {:name "prod"
    :host "4.5.6.7"}))

(def testing-box
  {:user    "ubuntu"
   :id-file (str "/Users/sideris/devel/nuka/testing.pem")
   :name    "testing"
   :host    "ec2-1-1-1-1.eu-west-1.compute.amazonaws.com"})

(comment
  ;;local execution

  (-> (call :ls :i) exec >print)
  (-> (call :whoami) exec >slurp first)
  (-> (call :seq 3) exec >slurp)

  (-> (script (call :ls :i)) exec >print)

  (-> (call :ls :l :F) exec >slurp)

  (ping testing-box 1)

  (def slee (-> (call :sleep 10) exec))
  (>print slee)
  (kill slee)
  (exit-code slee)

  ;;remote execution

  (def re (remote/exec testing-box (call :ls {:l true :a true})))
  (>print re)

  (>print (remote/exec testing-box (call :ls {:l true :a true})))
  (first (>slurp (remote/exec testing-box (call :pwd))))

  ;;merge in some ssh settings
  (>print (remote/exec testing-box (call :pwd) ssh-no-authenticity))

  ;;>slurp fails if there is anything in stderr, and the no-auth
  ;;options can print a warning to stderr, so we ignore it before
  ;;piping the process to >slurp
  (-> (remote/exec testing-box (call :ls :l :a) ssh-no-authenticity) >no-err >slurp)

  ;;...or you can redirect err to out and collect all lines:
  (-> (remote/exec testing-box (call :ls :l :a) ssh-no-authenticity) >err->out >slurp)

  (>print (scp "/Users/sideris/Downloads/example.pdf" [testing-box "~/example3.pdf"]))
  (>print (scp [testing-box "~/example3.pdf"] "/Users/sideris/Downloads/example4.pdf"))

  (def cc (script-on testing-box
                     (script
                      (call :touch "/tmp/foo1")
                      (call :touch "/tmp/foo2"))))

  (>print (remote/exec testing-box (script (call :ls {:l true} "/tmp/")))))
