(ns teach-clerk.notebooks
  "One in-memory Clerk Notebook per student, using Clerk's native Viewer and patch format."
  (:require [clojure.string :as str]
            [clojure.data.json :as json]
            [editscript.core :as editscript]
            [nextjournal.clerk :as clerk]
            [nextjournal.clerk.view :as view]
            [nextjournal.clerk.viewer :as viewer]
            [nextjournal.clerk.config :as config]
            [nextjournal.clerk.webserver :as clerk-web]
            [teach-clerk.board :as board]
            [teach-clerk.rooms :as rooms]))

(defonce !template (atom nil))

(defn initialize! []
  ;; Clerk's global document is only a template. No student uses its broadcast.
  (clerk/show! 'teach-clerk.board)
  (reset! !template @clerk-web/!doc))

(defn- evaluated-doc [room]
  (let [template @!template
        n (dec (count (:blocks template)))
        old (get-in template [:blocks n :result])
        revision (:revision (rooms/snapshot room))
        value (board/lesson-value room)]
    (when-not template
      (throw (ex-info "Clerk notebook template is not initialized" {})))
    (assoc-in template [:blocks n :result]
              (assoc old
                     :nextjournal/value value
                     :nextjournal/blob-id (str "lesson-" (:code room) "-" revision)))))

(defn- presented [room]
  ;; No teacher source code is evaluated during this operation.
  ;; Each room gets a private presentation cache.
  (view/doc->viewer
   {:presentation-cache (:cache @(:view room))}
   (evaluated-doc room)))

(defn refresh! [room]
  (locking (:view room)
    (let [old @(:view room)
          cache (or (:cache old) (atom {}))
          evaluated (evaluated-doc room)
          next (view/doc->viewer {:presentation-cache cache} evaluated)
          patch (when (:doc old)
                  (editscript/get-edits
                   (editscript/diff (:doc old) next {:algo :quick})))]
      (reset! (:view room)
              {:doc next
               :raw (with-meta evaluated next)
               :cache cache})
      (when (seq patch)
        (doseq [ch @(:sockets room)]
          (try (clerk-web/send! ch {:type :patch-state! :patch patch})
               (catch Throwable _ (swap! (:sockets room) disj ch)))))
      next)))

(defn html [room]
  (let [doc (refresh! room)
        page (view/->html
              {:doc doc
               :resource->url @config/!resource->url
               :render-router :serve
               :conn-ws? false})
        fetch-js (str "<script>(function(){"
                      "const original=window.fetch.bind(window);"
                      "window.fetch=function(input,options){"
                      "const url=typeof input==='string'?input:input.url;"
                      "if(typeof url==='string'&&url.startsWith('/_blob/')){"
                      "const sep=url.includes('?')?'&':'?';"
                      "return original(url+sep+'tab=" (:token room) "',options);}"
                      "return original(input,options);"
                      "};})();</script>")
        socket-js (str "<script type=\"module\">"
                       "nextjournal.clerk.sci_env.connect("
                       "location.origin.replace(/^http/, 'ws') + '/_ws/"
                       (:token room)
                       "');</script>")]
    (-> page
        (str/replace "</head>" (str fetch-js "</head>"))
        (str/replace "</body>" (str socket-js "</body>")))))

(defn serve-blob [req room]
  (when-not (:raw @(:view room))
    (refresh! room))
  (let [data (clerk-web/serve-blob
              (:raw @(:view room))
              (clerk-web/extract-blob-opts req))]
    (cond-> (dissoc data :content-type)
      (:content-type data)
      (assoc-in [:headers "content-type"] (:content-type data)))))

(defn- verified-socket [room ch]
  (rooms/touch! room)
  (swap! (:sockets room) conj ch))

(defn socket [req room]
  (org.httpkit.server/as-channel
   req
   {:on-open (fn [ch] (verified-socket room ch))
    :on-close (fn [ch _] (swap! (:sockets room) disj ch))
    :on-receive (fn [_ _] nil)}))

(defn publish-status! [room]
  (let [message (str "event: status\ndata: "
                     (json/write-str (rooms/status room))
                     "\n\n")]
    (doseq [ch @(:streams room)]
      (try
        (org.httpkit.server/send! ch message false)
        (catch Throwable _ (swap! (:streams room) disj ch))))))

(defn keepalive! [room]
  (doseq [ch @(:streams room)]
    (try (org.httpkit.server/send! ch ": keepalive\n\n" false)
         (catch Throwable _ (swap! (:streams room) disj ch)))))
