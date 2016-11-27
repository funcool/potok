(ns potok.tests
  (:require [cljs.test :as t :include-macros true]
            [beicon.core :as rx]
            [potok.core :as ptk]))

(enable-console-print!)

(defrecord IncrementBy [n]
  ptk/UpdateEvent
  (update [_ state]
    (update state :counter + n)))

(defrecord AsyncIncrementBy [n]
  ptk/WatchEvent
  (watch [_ state stream]
    (rx/of (->IncrementBy n))))

(t/deftest synchronous-state-transformation-test
  (t/async done
    (let [store (ptk/store {:state {:counter 0}})]
      (-> (rx/take 1 store)
          (rx/subscribe (fn [{:keys [counter]}]
                          (t/is (= counter 1)))
                        nil
                        done))

      (ptk/emit! store (->IncrementBy 1)))))


(t/deftest asynchronous-state-transformation-test
  (t/async done
    (let [store (ptk/store {:state {:counter 0}})]
      (-> (rx/take 1 store)
          (rx/subscribe (fn [{:keys [counter]}]
                          (t/is (= counter 2)))
                        nil
                        done))

      (ptk/emit! store (->AsyncIncrementBy 2)))))

(set! *main-cli-fn* #(t/run-tests))

(defmethod t/report [:cljs.test/default :end-run-tests]
  [m]
  (if (t/successful? m)
    (set! (.-exitCode js/process) 0)
    (set! (.-exitCode js/process) 1)))
