(ns teach-clerk.oauth
  "OAuth 2.1 authorization-code + PKCE for one classroom owner.

  DCR client identifiers and OAuth tokens are signed. Authorization codes
  remain single-use in memory. Each browser consent creates an anonymous
  OAuth principal. No fixed API token is required by MCP clients."
  (:require [clojure.data.json :as json]
            [clojure.string :as str])
  (:import [java.net URLEncoder URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]))

(def ^:dynamic *settings* nil)
(defonce !codes (atom {}))
(def ^SecureRandom random (SecureRandom.))
(def max-body-chars 12000)
(def access-seconds 3600)
(def refresh-seconds (* 30 24 3600))
(def scope-name "teacher.teach")

(defn setting [key]
  (or (get *settings* key)
      (System/getenv
       (case key
         :issuer "PUBLIC_BASE_URL"
         :signing-secret "OAUTH_SIGNING_SECRET"))))

(defn issuer []
  (str/replace (or (setting :issuer)
                   (System/getenv "RENDER_EXTERNAL_URL")
                   "http://127.0.0.1:10000") #"/$" ""))

(defn audience [] (issuer))
(defn now [] (quot (System/currentTimeMillis) 1000))

(defn utf8 [s] (.getBytes (str s) StandardCharsets/UTF_8))
(defn b64 [b] (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b))
(defn unb64 [s] (.decode (Base64/getUrlDecoder) s))

(defn secure= [a b]
  (and (string? a) (string? b)
       (MessageDigest/isEqual (utf8 a) (utf8 b))))

(defn random-id []
  (let [b (byte-array 32)]
    (.nextBytes random b)
    (b64 b)))

(defn sha256 [s]
  (.digest (MessageDigest/getInstance "SHA-256") (utf8 s)))

(defn signature [text]
  (let [key (setting :signing-secret)
        mac (Mac/getInstance "HmacSHA256")]
    (when (< (count (or key "")) 32)
      (throw (ex-info "OAUTH_SIGNING_SECRET is missing" {})))
    (.init mac (SecretKeySpec. (utf8 key) "HmacSHA256"))
    (b64 (.doFinal mac (utf8 text)))))

(defn signed-json [prefix payload]
  (let [message (str prefix "." (b64 (utf8 (json/write-str payload))))]
    (str message "." (signature message))))

(defn read-signed [prefix token]
  (when (and (string? token) (< (count token) 12000))
    (try
      (let [parts (str/split token #"\." -1)]
        (when (= 3 (count parts))
          (let [[head raw proof] parts
                message (str head "." raw)]
            (when (and (= prefix head)
                       (secure= proof (signature message)))
              (let [data (String. (unb64 raw) StandardCharsets/UTF_8)]
                (when (< (count data) 10000)
                  (json/read-str data)))))))
      (catch Exception _ nil))))

(defn token [type client-id scope principal ttl]
  (signed-json "oauth"
               {:iss (issuer) :aud (audience)
                :sub principal :typ type
                :client_id client-id :scope scope
                :iat (now) :exp (+ (now) ttl) :jti (random-id)}))

(defn valid-token [value expected-type]
  (let [data (read-signed "oauth" value)]
    (when (and data
               (= expected-type (get data "typ"))
               (= (issuer) (get data "iss"))
               (= (audience) (get data "aud"))
               (string? (get data "sub"))
               (<= 12 (count (get data "sub")) 128)
               (number? (get data "exp"))
               (> (get data "exp") (now))
               (some #{scope-name}
                     (str/split (get data "scope" "") #"\s+")))
      data)))

(defn authorized? [request]
  (let [value (get-in request [:headers "authorization"])]
    (when (and (string? value) (str/starts-with? value "Bearer "))
      (valid-token (subs value 7) "access"))))

(defn resource-metadata []
  {:resource (audience)
   :authorization_servers [(issuer)]
   :scopes_supported [scope-name "offline_access"]})

(defn server-metadata []
  {:issuer (issuer)
   :authorization_endpoint (str (issuer) "/oauth/authorize")
   :token_endpoint (str (issuer) "/oauth/token")
   :registration_endpoint (str (issuer) "/oauth/register")
   :response_types_supported ["code"]
   :grant_types_supported ["authorization_code" "refresh_token"]
   :code_challenge_methods_supported ["S256"]
   :token_endpoint_auth_methods_supported ["none"]
   :scopes_supported [scope-name "offline_access"]
   :authorization_response_iss_parameter_supported true})

(defn challenge
  ([] (challenge "invalid_token" "OAuth login is required"))
  ([error description]
   (str "Bearer resource_metadata=\"" (issuer)
        "/.well-known/oauth-protected-resource\", scope=\""
        scope-name "\", error=\"" error
        "\", error_description=\"" description "\"")))

(defn json-response
  ([code data] (json-response code data {}))
  ([code data headers]
   {:status code
    :headers (merge {"content-type" "application/json; charset=utf-8"
                     "cache-control" "no-store"
                     "x-content-type-options" "nosniff"}
                    headers)
    :body (json/write-str data)}))

(defn error [status code message]
  (json-response status {:error code :error_description message}))

(defn query-encode [s] (URLEncoder/encode (str s) "UTF-8"))
(defn query-decode [s] (URLDecoder/decode (str/replace s "+" "%20") "UTF-8"))
(defn query-params [s]
  (into {}
        (keep (fn [part]
                (when (seq part)
                  (let [[k v] (str/split part #"=" 2)]
                    [(query-decode k) (query-decode (or v ""))]))))
        (str/split (or s "") #"&")))

(defn request-body [request]
  (let [raw (slurp (:body request))]
    (when (> (count raw) max-body-chars)
      (throw (ex-info "Request is too large" {:kind :invalid_request})))
    raw))

(defn form-body [request]
  (let [content-type (get-in request [:headers "content-type"] "")]
    (when-not (str/starts-with? content-type "application/x-www-form-urlencoded")
      (throw (ex-info "Expected application/x-www-form-urlencoded" {:kind :invalid_request})))
    (query-params (request-body request))))

(defn client-json [client]
  (let [raw (read-signed "dcr" client)]
    (when (and raw (= 1 (get raw "version"))
               (<= (now) (get raw "expires" 0)))
      raw)))

(defn allowed-redirect? [uri]
  (and (string? uri)
       (or (= uri "https://chatgpt.com/connector_platform_oauth_redirect")
           (boolean (re-matches
                     #"https://chatgpt\.com/connector/oauth/[A-Za-z0-9_-]{6,150}"
                     uri))
           (and (str/starts-with? (issuer) "http://127.0.0.1:")
                (boolean (re-matches
                          #"http://127\.0\.0\.1:[0-9]{2,5}/callback"
                          uri))))))

(defn register [request]
  (try
    (let [body (json/read-str (request-body request))
          redirects (get body "redirect_uris")
          method (get body "token_endpoint_auth_method" "none")
          grants (get body "grant_types" ["authorization_code" "refresh_token"])]
      (if-not (and (map? body)
                   (vector? redirects)
                   (<= 1 (count redirects) 5)
                   (every? allowed-redirect? redirects)
                   (= method "none")
                   (every? #{"authorization_code" "refresh_token"} grants))
        (error 400 "invalid_client_metadata" "Only approved ChatGPT callbacks and public PKCE clients are accepted")
        (let [client (signed-json "dcr"
                                  {:version 1
                                   :redirect_uris redirects
                                   :expires (+ (now) (* 5 365 24 3600))})]
          (json-response 201
                         {:client_id client
                          :client_id_issued_at (now)
                          :redirect_uris redirects
                          :grant_types ["authorization_code" "refresh_token"]
                          :response_types ["code"]
                          :token_endpoint_auth_method "none"}))))
    (catch Exception _ (error 400 "invalid_client_metadata" "Invalid client registration"))))

(defn validated-auth [values]
  (let [client-id (get values "client_id")
        client (client-json client-id)
        redirect (get values "redirect_uri")
        scope (get values "scope" scope-name)
        challenge (get values "code_challenge")
        resource (get values "resource")]
    (when-not (and client
                   (some #{redirect} (get client "redirect_uris"))
                   (allowed-redirect? redirect)
                   (= "code" (get values "response_type"))
                   (= "S256" (get values "code_challenge_method"))
                   (string? challenge)
                   (re-matches #"[A-Za-z0-9_-]{43,128}" challenge)
                   (string? (get values "state"))
                   (<= 8 (count (get values "state")) 2048)
                   (= resource (audience))
                   (every? #{scope-name "offline_access"}
                           (remove str/blank? (str/split scope #"\s+")))
                   (some #{scope-name} (str/split scope #"\s+")))
      (throw (ex-info "Invalid OAuth client, redirect URI, scope, PKCE or resource"
                      {:kind :invalid_request})))
    (assoc values "scope" scope)))

(defn escape-html [text]
  (str/escape (str text)
              {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;" \' "&#39;"}))

(defn login-page
  ([values] (login-page values nil))
  ([values problem]
   {:status (if problem 403 200)
    :headers {"content-type" "text/html; charset=utf-8"
              "cache-control" "no-store"
              "x-frame-options" "DENY"
              "referrer-policy" "no-referrer"
              "content-security-policy"
              ;; Do not set form-action here. Browsers apply that directive to
              ;; the entire OAuth redirect chain, not only the first POST.
              ;; ChatGPT may redirect from chatgpt.com to platform.openai.com,
              ;; and a form-action allowlist would silently block consent.
              (str "default-src 'none'; "
                   "style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'")}
    :body (str
           "<!doctype html><html lang=\"zh\"><meta charset=\"utf-8\">"
           "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
           "<title>Clerk 课堂 · 授权</title>"
           "<style>body{font:16px system-ui,-apple-system,sans-serif;background:#f7f8fa;"
           "display:grid;place-items:center;min-height:90vh;margin:0;color:#242a34}"
           "main{background:white;max-width:420px;width:calc(100% - 48px);"
           "padding:30px;border:1px solid #e4e7ee;border-radius:16px;"
           "box-shadow:0 8px 28px #1719230b}"
           "h1{font-size:23px;margin:0 0 14px}p{line-height:1.65;color:#586477}"
           "label{font-size:14px}input{box-sizing:border-box;display:block;width:100%;"
           "margin:8px 0 18px;padding:12px;border:1px solid #cad0da;border-radius:9px;font-size:16px}"
           "button{border:0;background:#242735;color:white;border-radius:9px;"
           "padding:13px;width:100%;font-size:15px;cursor:pointer}"
           ".error{color:#b33d3d}</style>"
           "<main><h1>连接 AI 教师</h1>"
           "<p>ChatGPT 申请使用 <b>teach_then_ask</b>。"
           "授权后，它可以在你提供配对码的教室里出题、读取回答并结束课堂。</p>"
           (when problem
             (str "<p class=\"error\">" (escape-html problem) "</p>"))
           "<form method=\"post\" action=\"/oauth/authorize\">"
           (apply str
                  (for [key ["client_id" "redirect_uri" "response_type"
                             "scope" "state" "code_challenge"
                             "code_challenge_method" "resource"]]
                    (str "<input type=\"hidden\" name=\"" key "\" value=\""
                         (escape-html (get values key "")) "\">")))
           "<button type=\"submit\">同意连接 ChatGPT</button></form>"
           "<p style=\"font-size:12px\">无需注册账号。每个浏览器标签页会产生一个"
           "独立的课堂配对码；只有你把它提供给 ChatGPT，它才能进入对应课堂。</p>"
           "</main></html>")}))

(defn redirect-result [location]
  {:status 302
   :headers {"location" location
             "cache-control" "no-store"
             "referrer-policy" "no-referrer"}
   :body ""})

(defn redirect-with [uri pairs]
  (str uri (if (str/includes? uri "?") "&" "?")
       (str/join "&"
                 (for [[k v] pairs]
                   (str (query-encode k) "=" (query-encode v))))))

(defn authorize [request]
  (try
    (let [values (if (= :get (:request-method request))
                   (query-params (:query-string request))
                   (form-body request))
          valid (validated-auth values)]
      (if (= :get (:request-method request))
        (login-page valid)
        (let [code (random-id)
              entry (assoc (select-keys valid
                                        ["client_id" "redirect_uri"
                                         "code_challenge" "scope" "resource"])
                           "principal" (random-id)
                           "expires" (+ (now) 300))]
          (swap! !codes
                 (fn [old]
                   (->> old
                        (filter (fn [[_ v]] (> (get v "expires") (now))))
                        (into {})
                        (#(assoc % code entry)))))
          (redirect-result
           (redirect-with (get valid "redirect_uri")
                          [["code" code]
                           ["state" (get valid "state")]
                           ["iss" (issuer)]])))))
    (catch Exception e
      (error 400 "invalid_request" (or (ex-message e) "Invalid authorization request")))))

(defn consume-code! [code]
  (let [saved (atom nil)]
    (swap! !codes
           (fn [old]
             (reset! saved (get old code))
             (dissoc old code)))
    @saved))

(defn valid-client? [client-id]
  (boolean (client-json client-id)))

(defn token-response [request]
  (try
    (let [args (form-body request)
          grant (get args "grant_type")
          client-id (get args "client_id")
          resource (get args "resource")
          _ (when-not (and (valid-client? client-id)
                           (= resource (audience)))
              (throw (ex-info "Invalid OAuth client or resource" {:kind :invalid_client})))
          granted
          (case grant
            "authorization_code"
            (let [code (consume-code! (get args "code"))
                  verifier (get args "code_verifier" "")]
              (when-not (and code (> (get code "expires") (now))
                             (= (get code "client_id") client-id)
                             (= (get code "redirect_uri") (get args "redirect_uri"))
                             (= (get code "resource") resource)
                             (re-matches #"[A-Za-z0-9._~-]{43,128}" verifier)
                             (secure= (get code "code_challenge")
                                      (b64 (sha256 verifier))))
                (throw (ex-info "Invalid or expired authorization code or PKCE verifier"
                                {:kind :invalid_grant})))
              {:scope (get code "scope") :principal (get code "principal")})

            "refresh_token"
            (let [refresh (valid-token (get args "refresh_token") "refresh")]
              (when-not (and refresh (= (get refresh "client_id") client-id))
                (throw (ex-info "Invalid or expired refresh token"
                                {:kind :invalid_grant})))
              {:scope (get refresh "scope") :principal (get refresh "sub")})

            (throw (ex-info "Unsupported OAuth grant type"
                            {:kind :unsupported_grant_type})))]
      (json-response 200
                     {:access_token (token "access" client-id
                                           (:scope granted) (:principal granted) access-seconds)
                      :token_type "Bearer"
                      :expires_in access-seconds
                      :refresh_token (token "refresh" client-id
                                            (:scope granted) (:principal granted) refresh-seconds)
                      :scope (:scope granted)}))
    (catch Exception e
      (let [kind (or (:kind (ex-data e)) :invalid_request)]
        (error 400 (name kind) (or (ex-message e) "Invalid token request"))))))

(defn handle [request]
  (case [(:request-method request) (:uri request)]
    [:get "/.well-known/oauth-protected-resource"]
    (json-response 200 (resource-metadata))
    [:get "/.well-known/oauth-protected-resource/mcp"]
    (json-response 200 (resource-metadata))
    [:get "/.well-known/oauth-authorization-server"]
    (json-response 200 (server-metadata))
    [:post "/oauth/register"] (register request)
    [:get "/oauth/authorize"] (authorize request)
    [:post "/oauth/authorize"] (authorize request)
    [:post "/oauth/token"] (token-response request)
    nil))
