(ns teach-clerk.worker-test
  (:require [clojure.test :refer [deftest is]]
            [teach-clerk.worker :as worker]))

(deftest codeless-table-is-a-real-generator
  (let [result (worker/evaluate
                "(clerk/table (mapv (fn [i] (mapv #(* i %) (range 1 10))) (range 1 10)))")]
    (is (= :table (:teach/view result)))
    (is (= 81 (count (flatten (:teach/value result)))))
    (is (= 81 (get-in result [:teach/value 8 8])))))

(deftest no-java-interop
  (is (thrown? Exception
               (worker/evaluate "(java.lang.Runtime/getRuntime)"))))

(deftest many-viewers
  (let [result (worker/evaluate
                "(clerk/fragment (clerk/md \"## Hello\") (clerk/tex \"x^2\") (clerk/table [[1 2] [3 4]]))")]
    (is (= :fragment (:teach/view result)))
    (is (= [:md :tex :table] (mapv :teach/view (:teach/value result))))))
