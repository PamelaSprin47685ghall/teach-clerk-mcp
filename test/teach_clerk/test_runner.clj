(ns teach-clerk.test-runner
  (:require [clojure.test :as test]
            [teach-clerk.state-test]
            [teach-clerk.worker-test]))

(defn -main [& _]
  (let [result (test/run-tests 'teach-clerk.state-test 'teach-clerk.worker-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
