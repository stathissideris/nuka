(defproject nuka "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [com.palletops/stevedore "0.8.0-beta.7"]]

  :pedantic? :abort
  
  :profiles {:dev {:source-paths ["dev" "examples"]
                   :dependencies [[org.clojure/tools.namespace "0.2.11"]]}})
