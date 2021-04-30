;; This Source Code Form is subject to the terms of the Mozilla Public
;; License, v. 2.0. If a copy of the MPL was not distributed with this
;; file, You can obtain one at http://mozilla.org/MPL/2.0/.
;;
;; Copyright (c) Andrey Antukh <niwi@niwi.nz>

(ns potok.core
  "Stream & Events based state management toolkit for ClojureScript."
  (:refer-clojure :exclude [reify])
  (:require [clojure.core :as c]))

(defmacro reify
  "A `reify` variant for define typed events."
  [type & impls]
  `(c/reify
     ~'potok.core/Event
     (~'-type [_#] ~type)
     ~@impls))
