(ns teach-clerk.sandbox-test
  (:require [clojure.test :refer [deftest is]]
            [teach-clerk.sandbox :as sandbox]))

(deftest bounded-clojure-generators
  (let [value (sandbox/evaluate!
               "(clerk/table (mapv (fn [i] (mapv #(* i %) (range 1 10))) (range 1 10)))")]
    (is (= :table (:teach/view value)))
    (is (= 81 (count (flatten (:teach/value value)))))
    (is (= 81 (get-in value [:teach/value 8 8])))))

(deftest accidental-unbounded-computation-is-rejected
  (is (thrown? Exception (sandbox/evaluate! "(loop [] (recur))")))
  (is (thrown? Exception (sandbox/evaluate! "(clerk/table (range 10000000))")))
  (is (thrown? Exception
               (sandbox/evaluate! "(java.lang.Runtime/getRuntime)"))))
