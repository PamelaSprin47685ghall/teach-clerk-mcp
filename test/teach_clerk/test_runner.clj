(ns teach-clerk.test-runner
  (:require [clojure.test :as test]
            [teach-clerk.auth-modes-test]
            [teach-clerk.board-test]
            [teach-clerk.entrypoint-test]
            [teach-clerk.oauth-test]
            [teach-clerk.pedagogy-test]
            [teach-clerk.rooms-test]
            [teach-clerk.sandbox-test]
            [teach-clerk.worker-test]))

(defn -main [& _]
  (let [result (test/run-tests 'teach-clerk.rooms-test
                               'teach-clerk.auth-modes-test
                               'teach-clerk.board-test
                               'teach-clerk.entrypoint-test
                               'teach-clerk.sandbox-test
                               'teach-clerk.worker-test
                               'teach-clerk.oauth-test
                               'teach-clerk.pedagogy-test)]
    (when (pos? (+ (:fail result) (:error result)))
      (System/exit 1))))
