(ns teach-clerk.sandbox
  "One-JVM best-effort SCI evaluator. It is not a hostile-code security boundary."
  (:require [edamame.core :as edamame]
            [teach-clerk.worker :as worker])
  (:import [java.util.concurrent ArrayBlockingQueue Callable
             ThreadPoolExecutor TimeUnit RejectedExecutionException]))

(def max-source-chars 20000)
(def max-forms 250)
(def max-time-ms 4500)
(def max-range-items 4000)

(def forbidden
  '#{loop loop* recur while iterate cycle repeatedly repeat lazy-seq
     future future-call pmap pcalls pvalues eval load-string
     read-string slurp spit load-file require use import ns
     defmacro alter-var-root set! locking bound-fn
     new . .. monitor-enter monitor-exit})

(defn- limited-range [& args]
  (let [xs (apply clojure.core/range args)
        counted (vec (take (inc max-range-items) xs))]
    (when (> (count counted) max-range-items)
      (throw (ex-info "range produces too many values" {})))
    counted))

(defn- inspect-source! [source]
  (when (or (not (string? source))
            (> (count source) max-source-chars)
            (empty? source))
    (throw (ex-info "Clojure source must be nonempty and at most 20,000 characters" {})))
  (let [forms (edamame/parse-string-all source {:all true})
        nodes (tree-seq coll? seq forms)]
    (when (> (count (take (inc max-forms) forms)) max-forms)
      (throw (ex-info "Too many top-level Clojure forms" {})))
    (when (some (fn [x] (and (symbol? x)
                             (contains? forbidden (symbol (name x)))))
                (take 30000 nodes))
      (throw (ex-info "Clojure expression contains an unbounded or unsafe form" {})))
    (when (>= (count (take 30001 nodes)) 30001)
      (throw (ex-info "Clojure expression is too complex" {})))))

(defonce pool
  (ThreadPoolExecutor.
   2 2 0 TimeUnit/MILLISECONDS
   (ArrayBlockingQueue. 4)
   (reify java.util.concurrent.ThreadFactory
     (newThread [_ runnable]
       (doto (Thread. runnable "teacher-sci")
         (.setDaemon true))))
   (java.util.concurrent.ThreadPoolExecutor$AbortPolicy.)))

(defn evaluate! [source]
  (inspect-source! source)
  (let [job (try
              (.submit pool
                       ^Callable
                       (reify Callable
                         (call [_] (worker/evaluate source {:range limited-range}))))
              (catch RejectedExecutionException _
                (throw (ex-info "The calculator is busy, try again later" {}))))]
    (try
      (.get job max-time-ms TimeUnit/MILLISECONDS)
      (catch java.util.concurrent.TimeoutException _
        (.cancel job true)
        (throw (ex-info "Clojure expression took too long" {})))
      (catch java.util.concurrent.ExecutionException e
        (throw (or (.getCause e) e))))))
