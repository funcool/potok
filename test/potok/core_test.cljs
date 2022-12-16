(ns potok.core-test
  (:require
   [cljs.test :as t]
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
    (let [store  (ptk/store {:state {:counter 0}})]
      (add-watch store "test" (fn [_ _ _ state]
                                (t/is (= 1 (:counter state)))
                                (remove-watch store "test")
                                (done)))
      (ptk/emit! store (->IncrementBy 1)))))


(t/deftest asynchronous-state-transformation-test
  (t/async done
    (let [store (ptk/store {:state {:counter 0}})]
      (add-watch store "test" (fn [_ _ _ state]
                                (t/is (= 2 (:counter state)))
                                (remove-watch store "test")
                                (done)))
      (ptk/emit! store (->AsyncIncrementBy 2)))))

(t/deftest data-only-events
  (let [event (ptk/data-event ::foobar {:some "data"})]
    (t/is (ptk/type? ::foobar event))
    (t/is (= {:some "data"} @event))))

;; (set! *main-cli-fn* #(t/run-tests))

;; (defmethod t/report [:cljs.test/default :end-run-tests]
;;   [m]
;;   (if (t/successful? m)
;;     (set! (.-exitCode js/process) 0)
;;     (set! (.-exitCode js/process) 1)))
