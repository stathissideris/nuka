(ns nuka.core
  (:require [nuka.exec :as exec :refer [run-command >print >slurp >no-err >err->out kill exit-code wait-for]]
            [nuka.script :as script :refer [script call q chain-and raw]]
            [nuka.network :refer [ping scp]]
            [nuka.remote-exec :refer [command-on script-on]]))

(def ssh-no-authenticity
  [:o "UserKnownHostsFile=/dev/null"
   :o "StrictHostKeyChecking=no"])

(def cms-dir "/Users/sideris/devel/work/moving-brands/mb-chauhan-phase2/cms/")

(def base-machine
  {:user "ubuntu"
   :id-file (str cms-dir "chauhan-eu-ec2-keypair-new.pem")
   :cms-dir "/home/ubuntu/chauhan/cms/"})

(def dev-box
  (merge
   base-machine
   {:name "dev"
    :host "54.154.91.217"}))

(def prod-box
  (merge
   base-machine
   {:name "prod"
    :host "52.18.102.181"}))

(def testing-box
  {:user "ubuntu"
   :id-file (str "/Users/sideris/devel/nuka/testing.pem")
   :name "testing"
   :host "ec2-52-19-148-202.eu-west-1.compute.amazonaws.com"})

(comment
  ;;local execution
  
  (-> (call :ls :i) run-command >print)
  (-> (call :whoami) run-command >slurp first)
  (-> (call :seq 3) run-command >slurp)

  (-> (script (call :ls :i)) run-command >print)
  
  (-> (call :ls :l :F) run-command >slurp)
  
  (ping testing-box 1)

  (def slee (-> (call :sleep 10) run-command))
  (>print slee)
  (kill slee)
  (exit-code slee)

  ;;remote execution
  
  (def re (command-on testing-box (call :ls {:l true :a true})))
  (>print re)

  (>print (command-on testing-box (call :ls {:l true :a true})))
  (first (>slurp (command-on testing-box (call :pwd))))

  ;;merge in some ssh settings
  (>print (command-on testing-box (call :pwd) ssh-no-authenticity))

  ;;>slurp fails if there is anything in stderr, and the no-auth
  ;;options can print a warning to stderr, so we ignore it before
  ;;piping the process to >slurp
  (-> (command-on testing-box (call :ls :l :a) ssh-no-authenticity) >no-err >slurp) 

  ;;...or you can redirect err to out and collect all lines:
  (-> (command-on testing-box (call :ls :l :a) ssh-no-authenticity) >err->out >slurp)
  
  (>print (scp "/Users/sideris/Downloads/example.pdf" [testing-box "~/example3.pdf"]))
  (>print (scp [testing-box "~/example3.pdf"] "/Users/sideris/Downloads/example4.pdf"))

  (def cc (script-on testing-box
                     (script
                      (call :touch "/tmp/foo1")
                      (call :touch "/tmp/foo2"))))
  
  (>print (command-on testing-box (script (call :ls {:l true} "/tmp/")))))
