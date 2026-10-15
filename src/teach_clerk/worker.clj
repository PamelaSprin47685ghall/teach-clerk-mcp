(ns teach-clerk.worker
  "A disposable JVM that evaluates a restricted Clojure teaching expression."
  (:require [sci.core :as sci]))

(defn- view [kind]
  (fn [value] {:teach/view kind :teach/value value}))

(defn- fragment [& values]
  {:teach/view :fragment :teach/value (vec values)})

(defn- table [values]
  {:teach/view :table :teach/value values})

(defn- all-data [value depth]
  (when (> depth 24)
    (throw (ex-info "Result is nested too deeply" {})))
  (cond
    (or (nil? value) (string? value) (boolean? value)
        (number? value) (keyword? value)) value
    (map? value) (do
                   (when (> (count value) 1000)
                     (throw (ex-info "Map is too large" {})))
                   (into {} (map (fn [[k v]] [(all-data k (inc depth))
                                           (all-data v (inc depth))]) value)))
    (or (vector? value) (list? value) (seq? value) (set? value))
    (let [limit 12000
          checked (vec (take (inc limit) value))]
      (when (> (count checked) limit)
        (throw (ex-info "Result contains too many elements" {})))
      (mapv #(all-data % (inc depth)) checked))
    :else (throw (ex-info "Result is not plain Clojure data" {:type (str (type value))}))))

(defn evaluate [source]
  (let [output (java.io.StringWriter.)
        bindings {'clerk {'md (view :md)
                          'tex (view :tex)
                          'code (view :code)
                          'html (view :html)
                          'table table
                          'plotly (view :plotly)
                          'vl (view :vl)
                          'fragment fragment
                          'row (fn [& values] {:teach/view :row :teach/value (vec values)})
                          'col (fn [& values] {:teach/view :col :teach/value (vec values)})}}]
    (binding [*out* output]
      (let [result (sci/eval-string source {:namespaces bindings})
            plain (all-data result 0)
            serialized (pr-str plain)]
        (when (> (count serialized) 250000)
          (throw (ex-info "Rendered value is too large" {})))
        plain))))

(defn -main []
  (let [reader (java.io.BufferedReader.
                (java.io.InputStreamReader. System/in java.nio.charset.StandardCharsets/UTF_8))
        writer (java.io.BufferedWriter.
                (java.io.OutputStreamWriter. System/out java.nio.charset.StandardCharsets/UTF_8))]
    (doseq [line (line-seq reader)]
      (let [response (try
                       (let [bytes (.decode (java.util.Base64/getDecoder) line)
                             source (String. bytes java.nio.charset.StandardCharsets/UTF_8)]
                         {:ok true :value (evaluate source)})
                       (catch Throwable e
                         {:ok false :error (or (ex-message e) "Clojure evaluation failed")}))]
        (.write writer (pr-str response))
        (.newLine writer)
        (.flush writer)))))
