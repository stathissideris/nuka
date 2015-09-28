(ns nuka.core
  (:require [nuka.exec :as exec :refer [run-command >print >slurp kill exit-code wait-for]]
            [nuka.script :as script :refer [script call q chain-and raw]]
            nuka.script.java
            nuka.script.bash))

(def ssh-no-authenticity
  [:o "UserKnownHostsFile=/dev/null"
   :o "StrictHostKeyChecking=no"])

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
   :host "ec2-52-17-234-137.eu-west-1.compute.amazonaws.com"})

(defn command-on
  ([machine scr]
   (command-on machine scr {}))
  ([{:keys [name host user id-file] :as machine} scr ssh-params]
   (let [scr (if (string? scr) scr (bash-render scr))
         _   (println (format "Running \"%s\" on machine \"%s\" (%s)" scr name host))
         s   (-> (call :ssh {:i id-file} ssh-params (str user "@" host) (q scr))
                 script java-render first)]
     (run-command s))))

(defn ping
  ([host timeout]
   (let [host (if (map? host) (:host host) host)
         p    (-> (call :ping {:o true :t timeout} host) script java-render first run-command)]
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
        cmd (-> (call :scp {:i id-file} options (scp-file src) (scp-file dest))
                script java-render first)]
    (run-command cmd)))

(defn script-on
  ([machine scr]
   (script-on machine scr {}))
  ([{:keys [name host user id-file] :as machine} scr ssh-params]
   (let [scr           (if (string? scr) scr (bash-render scr))
         tmp-contents  (into #{} (>slurp (command-on testing-box (script (call :ls)) ssh-params)))
         local-script  "/tmp/script" ;;TODO make unique
         remote-script "/tmp/script" ;;TODO make unique
         ]
     (spit local-script scr)
     (when (zero? (exit-code (scp "/tmp/script" [machine remote-script])))
       (let [code
             (exit-code
              (command-on
               machine
               (script (chain-and (call :chmod (raw "+x") remote-script)
                                  (call remote-script)))
               ssh-params))]
         (wait-for (command-on machine (script (call :rm remote-script)) ssh-params))
         code)))))

(comment
  (-> (call :ls :i) script java-render first run-command >print)
  (-> (call :whoami) script java-render first run-command >slurp first)
  (-> (call :seq 3) script java-render first run-command >slurp)

  (-> (script (call :ls :i)) java-render first run-command )
  
  (-> (call :ls :l :F) script java-render first run-command >slurp)
  
  (ping "54.76.218.80" 1)
  (ping testing-box 1)

  (def slee (-> (call :sleep 10) script java-render first run-command))
  (>print slee)
  (kill slee)
  (exit-code slee)

  (def re (command-on testing-box (script (call :ls {:l true :a true}))))
  (>print re)

  (>print (command-on testing-box (script (call :ls {:l true :a true}))))
  (first (>slurp (command-on testing (script (call :pwd)))))

  (>print (scp "/Users/sideris/Downloads/example.pdf" [testing-box "~/example3.pdf"]))
  (>print (scp [testing-box "~/example3.pdf"] "/Users/sideris/Downloads/example4.pdf"))

  (def cc (script-on testing-box
                     (script
                      (call :touch "/tmp/foo1")
                      (call :touch "/tmp/foo2"))))
  
  (>print (command-on testing-box (script (call :ls {:l true} "/tmp/")))))
