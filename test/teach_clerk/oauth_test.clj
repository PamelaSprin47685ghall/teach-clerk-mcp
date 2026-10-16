(ns teach-clerk.oauth-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [teach-clerk.oauth :as oauth]
            [teach-clerk.main :as main])
  (:import [java.io ByteArrayInputStream]))

(def settings
  {:issuer "http://127.0.0.1:10000"
   :signing-secret "stable-signing-secret-for-test-only-987654321"})

(def callback "http://127.0.0.1:3000/callback")
(def verifier (apply str (repeat 48 "V")))

(defn body [value]
  (ByteArrayInputStream. (oauth/utf8 value)))

(defn form [values]
  (str/join "&"
            (for [[key value] values]
              (str (oauth/query-encode key) "="
                   (oauth/query-encode value)))))

(defn json-post [path data]
  {:uri path
   :request-method :post
   :body (body (json/write-str data))
   :headers {"content-type" "application/json"}})

(defn form-post [path data]
  {:uri path
   :request-method :post
   :body (body (form data))
   :headers {"content-type" "application/x-www-form-urlencoded"}})

(defn json-result [response]
  (json/read-str (:body response)))

(defn make-client []
  (let [response (oauth/handle (json-post "/oauth/register"
                                          {"redirect_uris" [callback]
                                           "token_endpoint_auth_method" "none"}))]
    (is (= 201 (:status response)))
    (get (json-result response) "client_id")))

(defn auth-args [client]
  {"client_id" client
   "redirect_uri" callback
   "response_type" "code"
   "scope" "teacher.teach offline_access"
   "state" "random-state-1234567890"
   "code_challenge" (oauth/b64 (oauth/sha256 verifier))
   "code_challenge_method" "S256"
   "resource" (oauth/audience)})

(defn authorize! [args]
  (let [request (form-post "/oauth/authorize" args)
        response (oauth/handle request)]
    (is (= 302 (:status response)))
    (oauth/query-params (second (str/split
                                 (get-in response [:headers "location"])
                                 #"\?" 2)))))

(deftest discovery-exposes-pkce-and-resource
  (binding [oauth/*settings* settings]
    (let [resource (json-result (oauth/handle
                                 {:uri "/.well-known/oauth-protected-resource"
                                  :request-method :get}))
          server (json-result (oauth/handle
                               {:uri "/.well-known/oauth-authorization-server"
                                :request-method :get}))]
      (is (= (oauth/issuer) (get resource "resource")))
      (is (= [(oauth/issuer)] (get resource "authorization_servers")))
      (is (= ["S256"] (get server "code_challenge_methods_supported")))
      (is (= ["none"] (get server "token_endpoint_auth_methods_supported")))
      (is (= true (get server "authorization_response_iss_parameter_supported")))
      (is (= ["authorization_code" "refresh_token"]
             (get server "grant_types_supported"))))))

(deftest registration-rejects-unknown-redirect
  (binding [oauth/*settings* settings]
    (is (= 400
           (:status (oauth/register
                     (json-post "/oauth/register"
                                {"redirect_uris" ["https://evil.example/callback"]})))))
    (is (= 400
           (:status (oauth/register
                     (json-post "/oauth/register"
                                {"redirect_uris" [callback]
                                 "token_endpoint_auth_method" "client_secret_post"})))))))

(deftest unauthorized-mcp-advertises-oauth
  (binding [oauth/*settings* settings]
    (let [probe (main/app {:uri "/mcp" :request-method :get})
          list-result (main/app (json-post
                                 "/mcp"
                                 {"jsonrpc" "2.0" "id" 1 "method" "tools/list"}))
          info (-> list-result json-result (get "result") (get "tools") first)
          refused (main/app (json-post
                              "/mcp"
                              {"jsonrpc" "2.0" "id" 2 "method" "tools/call"
                               "params" {"name" "teach_then_ask"
                                         "arguments" {"pairing_code" "123456"
                                                      "source" nil}}}))]
      (is (= 401 (:status probe)))
      (is (str/includes? (get-in probe [:headers "www-authenticate"])
                         "/.well-known/oauth-protected-resource"))
      (is (= [{"type" "oauth2" "scopes" [oauth/scope-name]}]
             (get info "securitySchemes")))
      (is (not (str/includes? (:body list-result) "/room/")))
      (is (= 401 (:status refused)))
      (is (str/includes? (get-in refused [:headers "www-authenticate"])
                         "/.well-known/oauth-protected-resource")))))

(deftest login-token-refresh-and-replay-protection
  (binding [oauth/*settings* settings]
    (reset! oauth/!codes {})
    (let [client (make-client)
          args (auth-args client)
          login (oauth/handle
                 {:uri "/oauth/authorize"
                  :request-method :get
                  :query-string (form args)})]
      (is (= 200 (:status login)))
      (is (str/includes? (:body login) "同意连接 ChatGPT"))
      (is (not (str/includes?
                (get-in login [:headers "content-security-policy"])
                "form-action")))
      (let [redirect (authorize! args)
            code (get redirect "code")
            params {"grant_type" "authorization_code"
                    "client_id" client
                    "resource" (oauth/audience)
                    "redirect_uri" callback
                    "code" code
                    "code_verifier" verifier}
            token-response (oauth/token-response
                            (form-post "/oauth/token" params))
            issued (json-result token-response)
            access (get issued "access_token")
            refresh (get issued "refresh_token")]
        (is (= (oauth/issuer) (get redirect "iss")))
        (is (= (get args "state") (get redirect "state")))
        (is (= 200 (:status token-response)))
        (is (= "Bearer" (get issued "token_type")))
        (is (some? (oauth/authorized?
                    {:headers {"authorization" (str "Bearer " access)}})))
        (is (>= (count (get (oauth/valid-token access "access") "sub")) 12))
        (is (nil? (oauth/authorized?
                   {:headers {"authorization" (str "Bearer " refresh)}})))
        (is (= 400 (:status (oauth/token-response
                             (form-post "/oauth/token" params)))))
        (let [refreshed (oauth/token-response
                         (form-post "/oauth/token"
                                    {"grant_type" "refresh_token"
                                     "client_id" client
                                     "resource" (oauth/audience)
                                     "refresh_token" refresh}))]
          (is (= 200 (:status refreshed)))
          (is (= "Bearer" (get (json-result refreshed) "token_type")))
          (is (= (get (oauth/valid-token access "access") "sub")
                 (get (oauth/valid-token
                       (get (json-result refreshed) "access_token") "access")
                      "sub"))))))))

(deftest pkce-mismatch-consumes-code
  (binding [oauth/*settings* settings]
    (reset! oauth/!codes {})
    (let [client (make-client)
          code (get (authorize! (auth-args client)) "code")
          params {"grant_type" "authorization_code"
                  "client_id" client
                  "resource" (oauth/audience)
                  "redirect_uri" callback
                  "code" code}]
      (is (= 400
             (:status (oauth/token-response
                       (form-post "/oauth/token"
                                  (assoc params "code_verifier" (apply str (repeat 48 "X"))))))))
      (is (= 400
             (:status (oauth/token-response
                       (form-post "/oauth/token"
                                  (assoc params "code_verifier" verifier)))))))))
