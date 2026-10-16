(ns teach-clerk.rooms-test
  (:require [clojure.test :refer [deftest is]]
            [teach-clerk.rooms :as rooms]))

(deftest independent-browser-tabs
  (rooms/reset-for-tests!)
  (let [a (rooms/create!)
        b (rooms/create!)
        principal "anonymous-oauth-grant-1"]
    (is (not= (:token a) (:token b)))
    (is (not= (:code a) (:code b)))
    (is (= a (rooms/by-token (:token a))))
    (is (= b (rooms/by-code (:code b))))
    (rooms/pair! a principal)
    (rooms/pair! b principal)
    (is (thrown? Exception (rooms/pair! a "other-oauth-grant")))
    (let [q-a (rooms/begin! a "A" {:teach/view :md :teach/value "A"})
          q-b (rooms/begin! b "B" {:teach/view :md :teach/value "B"})]
      (is (= 1 (:question_id (rooms/status a))))
      (is (= 1 (:question_id (rooms/status b))))
      (rooms/answer! b (:id q-b) "乙")
      (rooms/answer! a (:id q-a) "甲")
      (is (= "甲" @(:reply q-a)))
      (is (= "乙" @(:reply q-b)))
      (is (= "A" (-> a rooms/snapshot :events first :source)))
      (is (= "B" (-> b rooms/snapshot :events first :source))))
    (rooms/finish! a)
    (is (:closed (rooms/status a)))
    (is (not (:closed (rooms/status b))))
    (rooms/discard! a)
    (is (nil? (rooms/by-code (:code a))))
    (is (some? (rooms/by-token (:token b))))
    (rooms/discard! b)
    (is (= 0 (count (:tokens @rooms/!all))))))

(deftest tab-close-unblocks-teacher
  (rooms/reset-for-tests!)
  (let [room (rooms/create!)
        pending (rooms/begin! room "(clerk/md \"test\")" {:teach/view :md
                                                         :teach/value "test"})]
    (is (true? (rooms/discard! room)))
    (is (= ::rooms/abandoned @(:reply pending)))
    (is (nil? (rooms/by-token (:token room))))))

(deftest lesson-content-budget
  (rooms/reset-for-tests!)
  (let [room (rooms/create!)]
    (is (thrown? clojure.lang.ExceptionInfo
                 (rooms/begin! room "(clerk/md ...)"
                               (apply str (repeat 1000001 "x")))))
    (is (empty? (:events (rooms/snapshot room))))
    (rooms/discard! room)))
