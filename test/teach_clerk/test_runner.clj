(ns teach-clerk.test-runner
  (:require [clojure.test :as test]
            [teach-clerk.oauth-test]
            [teach-clerk.rooms-test]
            [teach-clerk.sandbox-test]
            [teach-clerk.worker-test]))

(defn -main [& _]
  (let [result (test/run-tests 'teach-clerk.rooms-test
                               'teach-clerk.sandbox-test
                               'teach-clerk.worker-test
                               'teach-clerk.oauth-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
