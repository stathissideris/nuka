(ns nuka.network
  (:require [nuka.exec :as exec :refer [run-command exit-code >print]]
            [nuka.script :as script :refer [call]]))

(defn ssh* [{:keys [host user id-file] :as machine} & [ssh-params]]
  (call :ssh {:i id-file} ssh-params (str user "@" host)))

(defn ping
  ([host timeout]
   (let [host (if (map? host) (:host host) host)
         p    (run-command (call :ping {:o true :t timeout} host))]
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
        cmd (run-command (call :scp {:i id-file} options (scp-file src) (scp-file dest)))]
    (run-command cmd)))
