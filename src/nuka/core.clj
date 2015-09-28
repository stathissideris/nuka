(ns nuka.core
  (:require [nuka.exec :as exec :refer [run-command >print >slurp kill exit-code]]
            [nuka.script :as script :refer [script]]
            nuka.script.java
            nuka.script.bash))

(def cms-dir "/Users/sideris/devel/work/moving-brands/mb-chauhan-phase2/cms/")

(def java-render nuka.script.java/render)
(def bash-render nuka.script.bash/render)
(def render nuka.script.bash/render)

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
   :host "ec2-54-76-218-80.eu-west-1.compute.amazonaws.com"})

(defn command-on [{:keys [name host user id-file] :as machine} scr]
  (let [scr (if (string? scr) scr (bash-render scr))
        _   (println (format "Running \"%s\" on machine \"%s\" (%s)" scr name host))
        s   (-> (ssh {:i ~id-file} ~(str user "@" host) (q scr))
                script java-render first)]
    (run-command s)))

(defn ping
  ([host timeout]
   (let [host (if (map? host) (:host host) host)
         p    (-> (ping {:o true :t ~timeout} ~host) script java-render first run-command)]
     (>print p)
     (zero? (exit-code p)))))

(defn- scp-file [[{:keys [user host] :as machine} path]]
  (if machine
    (str user "@" host ":" path)
    path))

(defn scp [src dest & [options]]
  (println (format "scp from %s to %s" (pr-str src) (pr-str dest)))
  (let [src (if (vector? src) src [nil src])
        dest (if (vector? dest) dest [nil dest])
        [src-machine src-file] src
        [dest-machine dest-file] dest
        id-file (if src-machine (:id-file src-machine) (:id-file dest-machine))
        cmd (-> (scp ~(merge {:i id-file} options) ~(scp-file src) ~(scp-file dest))
                script java-render first)]
    (run-command cmd)))

(defn script-on [{:keys [name host user id-file] :as machine} scr]
  (let [scr           (if (string? scr) scr (bash-render scr))
        tmp-contents  (into #{} (>slurp (command-on testing-box (script (ls)))))
        local-script  "/tmp/script" ;;TODO make unique
        remote-script "/tmp/script" ;;TODO make unique
        ]
    (spit local-script scr)
    (when (zero? (exit-code (scp "/tmp/script" [machine remote-script])))
      (exit-code (command-on machine (script (chain-and (chmod (raw "+x") ~remote-script) (~remote-script)))))
      (exit-code (command-on machine (script (rm ~remote-script)))))))

(comment
  (-> (ls :i) script java-render first run-command >print)
  (-> (whoami) script java-render first run-command >slurp first)
  (-> (seq 3) script java-render first run-command >slurp)

  (-> (ls :l :F) script java-render first run-command >slurp)
  
  (ping "54.76.218.80" 1)
  (ping testing-box 1)

  (def slee (-> (sleep 10) script java-render first run-command))
  (>print slee)
  (kill slee)
  (exit-code slee)

  (def re (command-on testing-box (script (ls {:l true :a true}))))
  (>print re)

  (>print (command-on testing-box (script (ls {:l true :a true}))))
  (first (>slurp (command-on testing (script (pwd)))))

  (>print (scp "/Users/sideris/Downloads/example.pdf" [testing "~/"]))
  (>print (scp [testing "~/example.pdf"] "/Users/sideris/Downloads/example2.pdf"))

  (def cc (script-on testing-box
                     (script (touch "/tmp/foo3")
                             (touch "/tmp/foo4"))))
  
  (>print (command-on testing-box (script (ls {:l true} "/tmp/")))))
