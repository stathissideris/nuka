(ns nuka.core
  (:require [nuka.exec :as exec :refer [run-command >print >slurp kill-process exit-code]]
            [nuka.script :as script :refer [script]]
            nuka.script.java
            nuka.script.bash))

(def cms-dir "/Users/sideris/devel/work/moving-brands/mb-chauhan-phase2/cms/")

(def java-render nuka.script.java/render)
(def render nuka.script.bash/render)


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

(defn ping [ip timeout]
  (let [p (-> (ping {:o true :t ~timeout} ~ip) script java-render first run-command)]
    (>print p)
    (exit-code p)))

(comment
  (>print (run-on dev "ls"))
  (-> (ls :i) script java-render first run-command >print)
  (-> (whoami) script java-render first run-command >slurp first)
  (-> (seq 3) script java-render first run-command >slurp)

  (-> (ls :l :F) script java-render first run-command >slurp)
  
  (do
    (def ping (-> (ping :o "www.google.com") script java-render first run-command))
    (>print ping)
    (exit-code ping))

  (do
    (def ping (-> (ping :o "54.76.218.80") script java-render first run-command))
    (>print ping)
    (exit-code ping))

  (def slee (-> (sleep 10) script java-render first run-command))
  (>print slee)
  (kill-process slee)
  (exit-code slee)
  )
