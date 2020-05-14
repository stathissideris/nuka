(ns nuka.remote-exec
  (:require [nuka.exec :as exec :refer [>slurp exit-code wait]]
            [nuka.script :as script :refer [script call q chain-and raw call? script?]]
            [nuka.network :as net]
            [nuka.script.bash :as bash]
            [clojure.spec.alpha :as s]))

(s/def ::user string?)
(s/def ::host string?)
(s/def ::name any?)
(s/def ::id-file string?)
(s/def ::machine
  (s/keys :req-un [::host ::user]
          :opt-un [::name ::id-file]))

(defn command-on
  "Runs a command on the passed machine via ssh. scr can be a string
  containing the code, a Script record or a single Call record."
  ([machine scr]
   (command-on machine scr {}))
  ([{:keys [name host user id-file] :as machine} scr ssh-params]
   (when-not (s/valid? ::machine machine)
     (throw (ex-info "machine map for remote-exec is not valid"
                     {:machine machine :script scr :ssh-params ssh-params})))
   (let [scr (cond (string? scr) scr
                   (call? scr)   (bash/render (script scr))
                   (script? scr) (bash/render scr)
                   :else         (throw (ex-info "Could not process passed script" {:script scr})))]
     (println (format "Running \"%s\" on machine \"%s\" (%s)" scr name host))
     (exec/exec
      (if id-file
        (call :ssh {:i id-file} ssh-params (str user "@" host) (q scr))
        (call :ssh ssh-params (str user "@" host) (q scr)))))))

(def exec command-on)

(defn script-on
  "Runs the passed script on the machine. Steps taken: scp passed
  script, chmod it to be executable, execute it via ssh, wait for it
  to finish, remove it from remote machine. The passed scr can be a
  string containing the script or a Script record. You can optionally
  pass arguments to ssh and scp via the 3rd optional argument.

  Returns the ssh Process."
  ([machine scr]
   (script-on machine scr {}))
  ([{:keys [name host user id-file] :as machine} scr ssh-params]
   (when-not (s/valid? ::machine machine)
     (throw (ex-info "machine map for remote-exec is not valid"
                     {:machine machine :script scr :ssh-params ssh-params})))
   (let [scr           (if (string? scr) scr (bash/render scr))
         tmp-contents  (into #{} (>slurp (command-on machine (call :ls) ssh-params)))
         local-script  "/tmp/script" ;;TODO make unique
         remote-script "/tmp/script" ;;TODO make unique
         ]
     (spit local-script scr)
     (when (zero? (exit-code (net/scp "/tmp/script" [machine remote-script] ssh-params)))
       (let [process
             (command-on
              machine
              (script (chain-and (call :chmod (raw "+x") remote-script)
                                 (call remote-script)))
              ssh-params)]
         (wait process)
         (wait (command-on machine (call :rm remote-script) ssh-params))
         process)))))
