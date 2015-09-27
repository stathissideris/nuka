(ns nuka.core)

(def cms-dir "/Users/sideris/devel/work/moving-brands/mb-chauhan-phase2/cms/")

(def base-machine
  {:user "ubuntu"
   :id-file (str cms-dir "chauhan-eu-ec2-keypair-new.pem")
   :cms-dir "/home/ubuntu/chauhan/cms/"})

(def dev
  (merge
   base-machine
   {:host "54.154.91.217"}))

(def prod
  (merge
   base-machine
   {:host "52.18.102.181"}))

