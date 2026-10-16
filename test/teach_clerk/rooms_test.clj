(ns teach-clerk.rooms-test
  (:require [clojure.test :refer [deftest is]]
            [teach-clerk.board :as board]
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

(deftest student-timeout-is-not-student-skip-or-lesson-completion
  (rooms/reset-for-tests!)
  (let [room (rooms/create!)
        first-question (rooms/begin! room "first" {:teach/view :md :teach/value "题目"})]
    (is (true? (rooms/timeout! room (:id first-question))))
    (is (nil? (rooms/timeout! room (:id first-question))))
    (is (= ::rooms/timeout
           (.get ^java.util.concurrent.CompletableFuture (:done first-question))))
    (is (:pending (rooms/status room)))
    (is (:student_timed_out (rooms/status room)))
    (is (:must-continue? (rooms/snapshot room)))
    (is (nil? (rooms/abandon-active! room (:id first-question))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"MUST call teach_then_ask again"
                          (rooms/finish! room)))
    ;; The tool has returned, but the textbox stays alive.
    (rooms/answer! room (:id first-question) "想到了，是 42")
    (is (not (:pending (rooms/status room))))
    (is (= [{:question_id (:id first-question)
             :student_reply "想到了，是 42"}]
           (:late-answers (rooms/snapshot room))))
    (is (= {:question_id (:id first-question)
            :student_reply "想到了，是 42"}
           (:late-answer (rooms/begin! room "would be stale"
                                       {:teach/view :md :teach/value "旧提示"}))))
    (is (= 2 (count (:events (rooms/snapshot room)))))
    (is (thrown? clojure.lang.ExceptionInfo (rooms/finish! room)))
    (let [new-question (rooms/begin! room "second"
                                      {:teach/view :md :teach/value "下一步"})]
      (is (not (:must-continue? (rooms/snapshot room))))
      (is (not (:student_timed_out (rooms/status room))))
      (rooms/answer! room (:id new-question) "完成")
      (is (= "完成"
             (.get ^java.util.concurrent.CompletableFuture (:done new-question)))))
    (rooms/finish! room)
    (is (:closed (rooms/status room)))
    (rooms/discard! room)))

(deftest unanswered-timeout-can-be-followed-by-a-new-teaching-question
  (rooms/reset-for-tests!)
  (let [room (rooms/create!)
        old (rooms/begin! room "first" {:teach/view :md :teach/value "题目"})]
    (rooms/timeout! room (:id old))
    (is (= (:id old) (:question_id (rooms/status room))))
    ;; Teacher can now teach again without throwing "question already pending".
    (let [new (rooms/begin! room "second" {:teach/view :md :teach/value "慢慢想"})]
      (is (not= (:id old) (:id new)))
      (is (= (:id new) (:question_id (rooms/status room))))
      (is (not (:must-continue? (rooms/snapshot room))))
      (rooms/answer! room (:id new) "现在知道了")
      (is (nil? (rooms/timeout! room (:id new))))
      (is (= "现在知道了"
             (.get ^java.util.concurrent.CompletableFuture (:done new)))))
    (rooms/discard! room)))

(deftest a-silent-followup-restarts-the-wait-without-changing-the-whiteboard
  (rooms/reset-for-tests!)
  (let [room (rooms/create!)
        old (rooms/begin! room "(clerk/md \"试一试\")"
                          {:teach/view :md :teach/value "试一试"})]
    (rooms/timeout! room (:id old))
    (let [board-before (board/lesson-value room)
          new (rooms/begin! room "(clerk/fragment)"
                            {:teach/view :fragment :teach/value []})]
      (is (not= (:id old) (:id new)))
      (is (true? (-> room rooms/snapshot :events last :silent?)))
      (is (= board-before (board/lesson-value room)))
      (is (not (:must-continue? (rooms/snapshot room))))
      (rooms/answer! room (:id new) "想好了")
      (is (= "想好了" @(:reply new))))
    (rooms/discard! room)))
