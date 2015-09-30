(ns nuka.remote-exec
  (:require [nuka.exec :as exec :refer [run-command >slurp exit-code wait-for]]
            [nuka.script :as script :refer [script call q chain-and raw call? script?]]
            [nuka.network :as net]
            nuka.script.bash))

(defrecord Machine [name host user id-file])

(def bash-render nuka.script.bash/render)

(defn command-on
  "Runs a command on the passed machine via ssd. scr can be a string
  containing the code, a Script record or a single Call record."
  ([machine scr]
   (command-on machine scr {}))
  ([{:keys [name host user id-file] :as machine} scr ssh-params]
   (let [scr (cond (string? scr) scr
                   (call? scr)   (bash-render (script scr))
                   (script? scr) (bash-render scr)
                   :else         (throw (ex-info "Could not process passed script" {:script scr})))]
     (println (format "Running \"%s\" on machine \"%s\" (%s)" scr name host))
     (run-command (call :ssh {:i id-file} ssh-params (str user "@" host) (q scr))))))

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
   (let [scr           (if (string? scr) scr (bash-render scr))
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
         (wait-for process)
         (wait-for (command-on machine (call :rm remote-script) ssh-params))
         process)))))
