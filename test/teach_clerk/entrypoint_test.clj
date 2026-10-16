(ns teach-clerk.entrypoint-test
  (:require [clojure.data.json :as json]
            [clojure.test :refer [deftest is]]
            [teach-clerk.entrypoint :as entrypoint]
            [teach-clerk.mcp :as mcp]
            [teach-clerk.oauth :as oauth])
  (:import [java.io ByteArrayInputStream]))

(def settings {:issuer "http://127.0.0.1:10000"
               :signing-secret "entrypoint-test-hmac-secret-at-least-32-bytes"})

(defn request
  ([id method params] (request id method params nil))
  ([id method params bearer]
   {:uri "/mcp"
    :request-method :post
    :headers (cond-> {"content-type" "application/json"}
               bearer (assoc "authorization" (str "Bearer " bearer)))
    :body (ByteArrayInputStream.
           (.getBytes
            (json/write-str
             {:jsonrpc "2.0" :id id :method method :params params})
            "UTF-8"))}))

(defn rpc! [id method params bearer]
  (json/read-str
   (:body (mcp/handle (request id method params bearer)))))

(deftest teaching-tool-stays-exclusive-for-the-model
  (binding [oauth/*settings* settings]
    (let [descriptors (get-in (rpc! 1 "tools/list" {} nil) ["result" "tools"])
          teach (first (filter #(= "teach_then_ask" (get % "name"))
                               descriptors))
          ui (first (filter #(= "open_teach_clerk" (get % "name"))
                            descriptors))]
      (is (= #{"teach_then_ask" "open_teach_clerk"}
             (set (map #(get % "name") descriptors))))
      (is (= ["pairing_code" "source"]
             (get-in teach ["inputSchema" "required"])))
      (is (= [{"type" "noauth"}
              {"type" "oauth2" "scopes" ["teacher.teach"]}]
             (get ui "securitySchemes")))
      (is (= (get ui "securitySchemes")
             (get-in ui ["_meta" "securitySchemes"])))
      (is (= ["app"] (get-in ui ["_meta" "ui" "visibility"])))
      (is (= entrypoint/uri
             (get-in ui ["_meta" "ui" "resourceUri"])))
      (is (= #{"thread" "global"}
             (set (map #(get % "type")
                       (get-in ui ["_meta" "openai/ui" "entrypoints"])))))
      (is (empty? (get-in ui ["inputSchema" "required"])))
      (is (= {} (get-in ui ["inputSchema" "properties"]))))))

(deftest chatgpt-ui-resource-is-discoverable
  (binding [oauth/*settings* settings]
    (let [listed (rpc! 2 "resources/list" {} nil)
          result (rpc! 3 "resources/read" {:uri entrypoint/uri} nil)
          document (get-in result ["result" "contents" 0])]
      (is (= entrypoint/uri
             (get-in listed ["result" "resources" 0 "uri"])))
      (is (= "text/html;profile=mcp-app"
             (get document "mimeType")))
      (is (= entrypoint/uri (get document "uri")))
      (is (re-find #"<!doctype html>" (get document "text")))
      (is (re-find #"teach_then_ask" (get document "text")))
      (is (re-find #"http://127.0.0.1:10000" (get document "text")))
      (is (= -32002
             (get-in (rpc! 4 "resources/read" {:uri "ui://missing"} nil)
                     ["error" "code"]))))))

(deftest chatgpt-entrypoint-accepts-empty-args-across-turns
  (binding [oauth/*settings* settings]
    (let [token (oauth/token "access" "test-client" "teacher.teach"
                             "user-grant-0000000001" 1000)
          results (mapv (fn [id]
                          (rpc! id "tools/call"
                                {:name "open_teach_clerk" :arguments {}}
                                token))
                        [5 6 7])
          denied (mcp/handle
                  (request 8 "tools/call"
                           {:name "open_teach_clerk" :arguments {}} nil))]
      (is (= [true true true]
             (mapv #(get-in % ["result" "structuredContent" "connected"])
                   results)))
      (is (every? #(not (get-in % ["result" "isError"])) results))
      (is (= 200 (:status denied)))
      (is (= true
             (get-in (json/read-str (:body denied))
                     ["result" "structuredContent" "connected"])))
      (is (= 401
             (:status (mcp/handle
                       (assoc-in (request 10 "tools/call"
                                          {:name "open_teach_clerk"
                                           :arguments {}}
                                          nil)
                                 [:headers "authorization"]
                                 "Bearer expired-or-fake")))))
      (is (= true
             (get-in (rpc! 9 "tools/call"
                            {:name "open_teach_clerk" :arguments {}} token)
                     ["result" "structuredContent" "connected"]))))))
