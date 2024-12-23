;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>


(ns potok.v2.core
  (:refer-clojure :exclude [reify deftype])
  (:require
   [cljs.core :as c]
   [clojure.string :as str]
   [cljs.analyzer :as ana]
   [cljs.compiler :as comp]))

(defmacro deftype
  [t fields & impls]
  (#'c/validate-fields "deftype" t fields)
  (let [env &env
        r             (:name (ana/resolve-var (dissoc env :locals) t))
        [fpps pmasks] (#'c/prepare-protocol-masks env impls)
        protocols     (#'c/collect-protocols impls env)
        t             (vary-meta t assoc
                                 :protocols protocols
                                 :skip-protocol-flag fpps) ]
    `(deftype* ~t ~fields ~pmasks
       ~(if (seq impls)
          `(extend-type ~t ~@(#'c/dt->et t impls fields)))
       )))

(defmacro leaky-exists?
  [x]
  (if (symbol? x)
    (let [x (cond-> (:name (ana/resolve-var &env x))
              (= "js" (namespace x)) name)
          y (str (str/replace (str x) "/" "."))
          y (vary-meta (symbol "js" y) assoc :cljs.analyzer/no-resolve true)]
      (-> (list 'js* "(typeof ~{} !== 'undefined')" y)
          (vary-meta assoc :tag 'boolean)))
    `(some? ~x)))

(defmacro reify
  [type & impls]
  (let [t        (with-meta
                   (gensym (str (name type) "-"))
                   {:anonymous true
                    :cljs.analyzer/no-resolve true})
        meta-sym (gensym "meta")
        this-sym (gensym "_")
        locals   (keys (:locals &env))
        ns       (-> &env :ns :name)
        ]
    `(do
       (when-not (leaky-exists? ~(symbol (str ns) (str t)))
         (deftype ~t [~@locals ~meta-sym]
           ~'potok.v2.core/Event
           (~'-type [_#] ~type)

           ~'cljs.core/IWithMeta
           (~'-with-meta [~this-sym ~meta-sym]
             (new ~t ~@locals ~meta-sym))

           ~'cljs.core/IMeta
           (~'-meta [~this-sym] ~meta-sym)

           ~@impls))
       (new ~t ~@locals ~(ana/elide-reader-meta (meta &form))))))
