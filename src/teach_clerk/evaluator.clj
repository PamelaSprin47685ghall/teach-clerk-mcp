(ns teach-clerk.evaluator
  "Supervises a warm SCI JVM. Timed-out evaluations kill the entire child JVM."
  (:require [clojure.edn :as edn]
            [clojure.string :as str])
  (:import [java.io BufferedReader BufferedWriter InputStreamReader OutputStreamWriter]
           [java.nio.charset StandardCharsets]
           [java.util Base64]))

(def timeout-ms 10000)
(def max-source-chars 32000)
(defonce !worker (atom nil))

(defn- start-worker! []
  (let [cmd ["java" "-Xms16m" "-Xmx80m" "-XX:MaxMetaspaceSize=96m"
             "-XX:MaxDirectMemorySize=24m" "-XX:ReservedCodeCacheSize=32m"
             "-XX:+UseSerialGC" "-cp" (System/getProperty "java.class.path")
             "clojure.main" "-m" "teach-clerk.worker"]
        builder (ProcessBuilder. ^java.util.List cmd)
        process (do
                  ;; Do not inherit MCP bearer secrets into the child JVM.
                  (let [environment (.environment builder)
                        path (.get environment "PATH")
                        lang (.get environment "LANG")]
                    (.clear environment)
                    (when path (.put environment "PATH" path))
                    (when lang (.put environment "LANG" lang)))
                  (.redirectError builder java.lang.ProcessBuilder$Redirect/INHERIT)
                  (.start builder))]
    {:process process
     :reader (BufferedReader. (InputStreamReader. (.getInputStream process) StandardCharsets/UTF_8))
     :writer (BufferedWriter. (OutputStreamWriter. (.getOutputStream process) StandardCharsets/UTF_8))}))

(defn- halt-worker! []
  (when-let [{:keys [process]} @!worker]
    (.destroyForcibly process))
  (reset! !worker nil))

(defn evaluate! [source]
  (when (or (not (string? source)) (str/blank? source)
            (> (count source) max-source-chars))
    (throw (ex-info "source must contain a nonempty Clojure expression (max 32,000 characters)" {})))
  (locking !worker
    (let [{:keys [process reader writer] :as child}
          (if (and @!worker (.isAlive (:process @!worker)))
            @!worker
            (let [new-worker (start-worker!)]
              (reset! !worker new-worker)
              new-worker))]
      (try
        (.write writer (.encodeToString (Base64/getEncoder)
                                        (.getBytes source StandardCharsets/UTF_8)))
        (.newLine writer)
        (.flush writer)
        (let [reply (deref (future (.readLine reader)) timeout-ms ::timeout)]
          (when (= reply ::timeout)
            (halt-worker!)
            (throw (ex-info "Clojure expression exceeded the 10-second evaluation budget" {})))
          (when (or (nil? reply) (> (count reply) 350000))
            (throw (ex-info "Evaluator stopped or produced too much output" {})))
          (let [{:keys [ok value error]} (edn/read-string {:readers {}} reply)]
            (when-not ok (throw (ex-info (or error "Evaluation failed") {})))
            value))
        (catch Throwable e
          (when-not (.isAlive process)
            (halt-worker!))
          (throw e))))))
