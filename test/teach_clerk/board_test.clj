(ns teach-clerk.board-test
  (:require [clojure.test :refer [deftest is]]
            [teach-clerk.board :as board]
            [teach-clerk.rooms :as rooms]
            [teach-clerk.worker :as worker]))

(defn- teacher-result [source]
  (let [room (rooms/create!)]
    (try
      (rooms/begin! room source (worker/evaluate source))
      ;; The first notebook fragment item is the heading; the second is the
      ;; "老师" label. The third is the converted Clojure-generated result.
      (nth (:nextjournal.clerk/fragment (board/lesson-value room)) 2)
      (finally (rooms/discard! room)))))

(deftest generator-vectors-render-as-clerk-fragments
  (let [view (teacher-result
              "[(clerk/md \"## 乘法\") (clerk/table {:head [\"x\"] :rows [[5]]}) (clerk/tex \"x^2\")]")
        children (vec (:nextjournal.clerk/fragment view))]
    (is (= 3 (count children)))
    (is (= 'nextjournal.clerk.viewer/markdown-viewer
           (:nextjournal/viewer (nth children 0))))
    (is (= 'nextjournal.clerk.viewer/table-viewer
           (:nextjournal/viewer (nth children 1))))
    (is (= 'nextjournal.clerk.viewer/katex-viewer
           (:nextjournal/viewer (nth children 2))))
    (is (= "## 乘法" (:nextjournal/value (first children))))
    (is (= {:head ["x"] :rows [[5]]}
           (:nextjournal/value (second children))))))

(deftest mapped-generator-results-are-rendered
  (let [view (teacher-result
              "(mapv (fn [i] (clerk/md (str \"### 第 \" i \" 步\"))) (range 1 4))")
        children (vec (:nextjournal.clerk/fragment view))]
    (is (= 3 (count children)))
    (is (= ["### 第 1 步" "### 第 2 步" "### 第 3 步"]
           (mapv :nextjournal/value children)))))

(deftest explicit-fragments-still-render
  (let [view (teacher-result
              "(clerk/fragment (clerk/md \"# A\") (clerk/table [[1 2]]))")]
    (is (= 2 (count (:nextjournal.clerk/fragment view))))))

(deftest ordinary-data-is-not-misinterpreted-as-a-fragment
  (is (= [1 2 3] (teacher-result "[1 2 3]")))
  (is (= {:name "test" :score 12}
         (teacher-result "{:name \"test\" :score 12}"))))

(deftest nested-viewers-inside-maps-are-converted
  (let [view (teacher-result
              "{:title (clerk/md \"Title\") :sections [(clerk/tex \"x\") (clerk/md \"Y\")]}")]
    (is (= 'nextjournal.clerk.viewer/markdown-viewer
           (get-in view [:title :nextjournal/viewer])))
    (is (= 2 (count (:nextjournal.clerk/fragment (:sections view)))))))
