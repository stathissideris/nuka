(ns nuka.core
  (:require [nuka.exec :as exec :refer [run-command >print >slurp kill-process]]
            [nuka.script :as script :refer [script render]]))

(def cms-dir "/Users/sideris/devel/work/moving-brands/mb-chauhan-phase2/cms/")

(def base-machine
  {:user "ubuntu"
   :id-file (str cms-dir "chauhan-eu-ec2-keypair-new.pem")
   :cms-dir "/home/ubuntu/chauhan/cms/"})

(def dev
  (merge
   base-machine
   {:name "dev"
    :host "54.154.91.217"}))

(def prod
  (merge
   base-machine
   {:name "prod"
    :host "52.18.102.181"}))

(defn run-on [{:keys [name host user id-file] :as machine} scr]
  (println (format "Running \"%s\" on machine \"%s\" (%s)" scr name host))
  (let [s (render
           (script
            (ssh {:i ~id-file} ~(str user "@" host) (q scr))))]
    (run-command s)))


(comment
  (>print (run-on dev "ls"))
  (-> (ls :i) script render run-command >slurp)
  (-> (whoami) script render run-command >slurp first)
  (-> (ping (raw ~(:host dev))) script render run-command >print)
  (-> (sleep 3) script render run-command >print)
  )
