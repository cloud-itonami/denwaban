(require '[clojure.test :as t])

(def suites '[denwaban.test-session denwaban.test-transport denwaban.test-social denwaban.test-state-machine
              denwaban.test-http denwaban.test-uketsuke denwaban.test-carrier denwaban.test-kikitori])
(apply require suites)
(let [{:keys [fail error] :as result} (apply t/run-tests suites)]
  (println (select-keys result [:test :pass :fail :error]))
  (when (pos? (+ fail error))
    (System/exit 1)))
