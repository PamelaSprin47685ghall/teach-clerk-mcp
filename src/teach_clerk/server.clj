(ns teach-clerk.server
  "HTTP entry point. Classroom tokens are scoped to browser tabs, not cookies."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [nextjournal.clerk.webserver :as clerk-web]
            [teach-clerk.oauth :as oauth]
            [teach-clerk.mcp :as mcp]
            [teach-clerk.rooms :as rooms]
            [teach-clerk.notebooks :as notebooks])
  (:import [java.util.concurrent Executors TimeUnit]))

(defonce !created-per-ip (atom {}))
(defonce heartbeat (Executors/newSingleThreadScheduledExecutor))
(def max-room-creates-per-minute 120)

(defn json-response
  ([status data] (oauth/json-response status data))
  ([status data headers] (oauth/json-response status data headers)))

(defn- public-file [name content-type]
  {:status 200
   :headers {"content-type" content-type
             "cache-control" "public, max-age=300"
             "x-content-type-options" "nosniff"}
   :body (slurp (io/resource (str "public/" name)))})

(defn- classroom-page []
  {:status 200
   :headers {"content-type" "text/html; charset=utf-8"
             "cache-control" "no-store"
             "referrer-policy" "no-referrer"
             "content-security-policy"
             (str "default-src 'self'; "
                  "script-src 'self'; style-src 'self' 'unsafe-inline'; "
                  "frame-src 'self'; connect-src 'self'; "
                  "base-uri 'none'; form-action 'self'; object-src 'none'")}
   :body (slurp (io/resource "public/classroom.html"))})

(defn- valid-origin? [req]
  (let [origin (get-in req [:headers "origin"])
        proto (or (get-in req [:headers "x-forwarded-proto"]) "http")
        host (get-in req [:headers "host"])
        expected (when host (str proto "://" host))]
    (and (string? origin)
         (or (oauth/secure= origin (oauth/issuer))
             (oauth/secure= origin expected)))))

(defn- body [req]
  (let [raw (slurp (:body req))]
    (when (> (count raw) 12000)
      (throw (ex-info "Request too large" {:kind :invalid})))
    (json/read-str raw)))

(defn- query-room [req]
  (rooms/by-token
   (get (oauth/query-params (:query-string req)) "tab")))

(defn- session-request [req]
  (try
    (let [_ (body req)
          ip (or (:remote-addr req) "unknown")
          now (rooms/now)
          counter (get (swap! !created-per-ip update ip
                              (fn [old]
                                (if (and old (< (- now (:since old)) 60000))
                                  (update old :count inc)
                                  {:count 1 :since now})))
                       ip)
          _ (when (> (:count counter) max-room-creates-per-minute)
              (throw (ex-info "Too many classrooms created"
                              {:kind :capacity})))
          room (rooms/create!)]
      (rooms/touch! room)
      (json-response
       200
       {:tab_token (:token room)
        :pairing_code (:code room)
        :state (rooms/status room)}))
    (catch Throwable e
      (json-response 429 {:error (or (ex-message e) "Cannot create classroom")}))))

(defn- status [req]
  (if-let [room (query-room req)]
    (do (rooms/touch! room)
        (json-response 200 (rooms/status room)))
    (json-response 404 {:error "Classroom no longer exists; refresh to begin again"})))

(defn- events [req]
  (if-let [room (query-room req)]
    (http/as-channel
     req
     {:on-open (fn [ch]
                 (rooms/touch! room)
                 (http/send! ch
                             {:status 200
                              :headers {"content-type" "text/event-stream; charset=utf-8"
                                        "cache-control" "no-store, no-transform"
                                        "x-accel-buffering" "no"}
                              :body (str "event: status\ndata: "
                                         (json/write-str (rooms/status room)) "\n\n")}
                             false)
                 (swap! (:streams room) conj ch))
      :on-close (fn [ch _] (swap! (:streams room) disj ch))})
    (json-response 404 {:error "Unknown classroom"})))

(defn- answer [req]
  (if-not (valid-origin? req)
    (json-response 403 {:error "Origin mismatch"})
    (try
      (let [args (body req)
            room (rooms/by-token (get args "tab_token"))
            id (get args "question_id")
            text (get args "text" ::missing)]
        (when-not room
          (throw (ex-info "Classroom closed" {:kind :missing})))
        (when (or (not (integer? id))
                  (= text ::missing)
                  (and (string? text) (str/blank? text)))
          (throw (ex-info "Expected question_id and text or null" {:kind :invalid})))
        (rooms/answer! room id text)
        (notebooks/refresh! room)
        (notebooks/publish-status! room)
        (json-response 200 {:ok true :state (rooms/status room)}))
      (catch clojure.lang.ExceptionInfo e
        (json-response
         (case (:kind (ex-data e))
           :conflict 409
           :missing 404
           400)
         {:error (ex-message e)}))
      (catch Throwable _ (json-response 400 {:error "Invalid answer"})))))

(defn- close-room [req]
  (try
    (let [args (body req)]
      (when-let [room (rooms/by-token (get args "tab_token"))]
        (rooms/discard! room))
      (json-response 200 {:closed true}))
    (catch Throwable _ (json-response 400 {:error "Invalid close request"}))))

(defn app [{:as req :keys [uri request-method]}]
  (or (oauth/handle req)
      (cond
        (and (= :get request-method) (= uri "/healthz"))
        (json-response 200 {:status "ok" :service "teach-clerk-mcp"})

        (= uri "/mcp") (mcp/handle req)

        (and (= :get request-method) (= uri "/"))
        (classroom-page)

        (and (= :get request-method) (= uri "/classroom.js"))
        (public-file "classroom.js" "application/javascript; charset=utf-8")

        (and (= :get request-method) (= uri "/classroom.css"))
        (public-file "classroom.css" "text/css; charset=utf-8")

        (and (= :post request-method) (= uri "/api/session"))
        (session-request req)

        (and (= :get request-method) (= uri "/api/state"))
        (status req)

        (and (= :get request-method) (= uri "/api/events"))
        (events req)

        (and (= :post request-method) (= uri "/api/answer"))
        (answer req)

        (and (= :post request-method) (= uri "/api/close"))
        (close-room req)

        (and (= :get request-method) (= uri "/_clerk"))
        (if-let [room (query-room req)]
          (try
            (rooms/touch! room)
            {:status 200
             :headers {"content-type" "text/html; charset=utf-8"
                       "cache-control" "no-store"
                       "referrer-policy" "no-referrer"}
             :body (notebooks/html room)}
            (catch Throwable e
              (json-response 500 {:error (or (ex-message e)
                                              "Clerk rendering failed")})))
          (json-response 404 {:error "Classroom no longer exists"}))

        (and (= :get request-method)
             (str/starts-with? uri "/_blob/"))
        (if-let [room (query-room req)]
          (try
            (rooms/touch! room)
            (notebooks/serve-blob req room)
            (catch Throwable _
              (json-response 404 {:error "Blob not found"})))
          (json-response 403 {:error "Private notebook token required"}))

        (and (:websocket? req)
             (str/starts-with? uri "/_ws/"))
        (if-let [room (rooms/by-token (subs uri (count "/_ws/")))]
          (if (valid-origin? req)
            (notebooks/socket req room)
            (json-response 403 {:error "Origin mismatch"}))
          (json-response 404 {:error "Unknown classroom"}))

        (and (= :get request-method)
             (or (= uri "/clerk_service_worker.js")
                 (re-matches #"/(js|css)/.*" uri)))
        (clerk-web/app req)

        (= uri "/favicon.ico") {:status 404 :body ""}
        :else {:status 404 :body "Not found"})))

(defn start! []
  (when (< (count (or (System/getenv "OAUTH_SIGNING_SECRET") "")) 32)
    (throw (ex-info "OAUTH_SIGNING_SECRET must be at least 32 characters" {})))
  (notebooks/initialize!)
  (.scheduleAtFixedRate
   heartbeat
   ^Runnable (fn []
               (try
                 (rooms/reap!)
                 (mcp/keepalive!)
                 (mcp/reap-failures!)
                 (let [now (rooms/now)]
                   (swap! !created-per-ip
                          (fn [entries]
                            (into {} (filter (fn [[_ v]]
                                               (< (- now (:since v)) 60000))
                                             entries)))))
                 (doseq [room (vals (:tokens @rooms/!all))]
                   (when (or (seq @(:sockets room))
                             (seq @(:streams room)))
                     (rooms/touch! room))
                   (notebooks/keepalive! room))
                 (catch Throwable e
                   (binding [*out* *err*]
                     (println "Classroom maintenance error:" (ex-message e))))))
   25 25 TimeUnit/SECONDS)
  (let [port (Integer/parseInt (or (System/getenv "PORT") "10000"))]
    (http/run-server #'app {:ip "0.0.0.0" :port port})
    (println "Teach Clerk MCP:" (str (oauth/issuer) "/mcp"))
    (println "Student homepage:" (oauth/issuer))))
