(ns teach-clerk.auth-modes-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [teach-clerk.mcp :as mcp]
            [teach-clerk.oauth :as oauth]
            [teach-clerk.rooms :as rooms])
  (:import [java.io ByteArrayInputStream]))

(def settings {:issuer "http://127.0.0.1:10000"
               :signing-secret "test-signing-secret-for-mixed-mode-auth-42"})

(defn request [code auth]
  {:uri "/mcp"
   :request-method :post
   :remote-addr "203.0.113.47"
   :headers (cond-> {"content-type" "application/json"}
              auth (assoc "authorization" auth))
   :body (ByteArrayInputStream.
          (.getBytes
           (json/write-str
            {:jsonrpc "2.0" :id 11 :method "tools/call"
             :params {:name "teach_then_ask"
                      :arguments {:pairing_code code :source nil}}})
           "UTF-8"))})

(deftest anonymous-pairing-code-is-not-an-oauth-identity
  (rooms/reset-for-tests!)
  (let [anonymous (rooms/create!)
        oauth-room (rooms/create!)]
    (try
      (rooms/pair! anonymous (str "anonymous:" (:code anonymous)))
      (is (thrown? Exception (rooms/pair! anonymous "oauth-grant-1")))
      (rooms/pair! oauth-room "oauth-grant-2")
      (is (thrown? Exception
                   (rooms/pair! oauth-room (str "anonymous:" (:code oauth-room)))))
      (is (true? (:paired (rooms/status anonymous))))
      (is (true? (:paired (rooms/status oauth-room))))
      (finally
        (rooms/discard! anonymous)
        (rooms/discard! oauth-room)))))

(deftest anonymous-guess-throttle-applies-across-different-codes
  (binding [oauth/*settings* settings]
    (rooms/reset-for-tests!)
    (reset! mcp/!pair-failures {})
    (let [room (rooms/create!)]
      (try
        (let [guesses (->> (range 0 20)
                           (map #(format "%06d" %))
                           (remove #{(:code room)})
                           (take 10))]
          (doseq [guess guesses]
            (let [response (mcp/handle (request guess nil))
                  payload (json/read-str (:body response))]
              (is (= 200 (:status response)))
              (is (true? (get-in payload ["result" "isError"])))
              (is (str/includes?
                   (get-in payload ["result" "content" 0 "text"])
                   "Pairing code not found")))))
        ;; The eleventh attempt is blocked even when it guesses a real code.
        (let [response (mcp/handle (request (:code room) nil))
              payload (json/read-str (:body response))]
          (is (= 200 (:status response)))
          (is (str/includes?
               (get-in payload ["result" "content" 0 "text"])
               "Too many invalid pairing codes"))
          (is (nil? (:principal (rooms/snapshot room)))))
        (finally
          (reset! mcp/!pair-failures {})
          (rooms/discard! room))))))

(deftest explicit-invalid-authorization-never-falls-back-to-noauth
  (binding [oauth/*settings* settings]
    (doseq [header ["Bearer not-valid" "Basic abc" "Bearer " ""]]
      (let [response (mcp/handle (request "123456" header))]
        (is (= 401 (:status response)))
        (is (str/includes? (get-in response [:headers "www-authenticate"])
                           "/.well-known/oauth-protected-resource"))))))
