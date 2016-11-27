(defproject funcool/potok "1.0.0"
  :description "Reactive streams based state management toolkit for ClojureScript"
  :url "https://github.com/funcool/potok"
  :license {:name "BSD (2-Clause)"
            :url "http://opensource.org/licenses/BSD-2-Clause"}
  :dependencies [[org.clojure/clojure "1.8.0" :scope "provided"]
                 [org.clojure/clojurescript "1.9.293" :scope "provided"]
                 [funcool/beicon "2.5.0"]]
  :deploy-repositories {"releases" :clojars
                        "snapshots" :clojars}
  :source-paths ["src" "assets"]
  :test-paths ["test"]
  :jar-exclusions [#"\.swp|\.swo|user.clj"]
  :plugins [[lein-ancient "0.6.10"]])
