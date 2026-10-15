(ns teach-clerk.state-test
  (:require [clojure.test :refer [deftest is]]
            [teach-clerk.state :as state]))

(deftest full-lesson
  (state/reset-for-tests!)
  (let [{:keys [id reply]} (state/begin! "(clerk/md \"Hi\")" {:teach/view :md :teach/value "Hi"})]
    (is (= id (:question_id (state/status))))
    (is (true? (:pending (state/status))))
    (is (thrown? clojure.lang.ExceptionInfo (state/begin! "second" {})))
    (is (thrown? clojure.lang.ExceptionInfo (state/reply! (+ 1 id) "no")))
    (state/reply! id "yes")
    (is (= "yes" @reply))
    (is (false? (:pending (state/status))))
    (state/finish!)
    (is (true? (:closed (state/status))))
    (is (= [:teacher :student :finished] (mapv :role (:events (state/snapshot)))))))

(deftest skip-is-not-an-answer
  (state/reset-for-tests!)
  (let [{:keys [id reply]} (state/begin! "(clerk/md \"Hi\")" {:teach/view :md :teach/value "Hi"})]
    (state/reply! id nil)
    (is (nil? @reply))
    (is (nil? (-> (state/snapshot) :events last :text)))))

(deftest disconnect-does-not-cancel-another-question
  (state/reset-for-tests!)
  (is (nil? (state/abandon! nil)))
  (let [{:keys [id reply]} (state/begin! "(clerk/md \"Hi\")" {:teach/view :md :teach/value "Hi"})]
    (is (nil? (state/abandon! (inc id))))
    (is (true? (:pending (state/status))))
    (is (true? (state/abandon! id)))
    (is (= ::state/abandoned @reply))
    (is (nil? (state/abandon! id)))
    (is (= [:teacher :abandoned] (mapv :role (:events (state/snapshot)))))))
