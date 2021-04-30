(require '[codox.main :as codox])

(codox/generate-docs
 {:output-path "doc/dist/latest"
  :metadata {:doc/format :markdown}
  :language :clojurescript
  :name "funcool/potok"
  :themes [:rdash]
  :source-paths ["src"]
  :namespaces [#"^potok\."]
  :source-uri "https://github.com/funcool/potok/blob/master/{filepath}#L{line}"})
