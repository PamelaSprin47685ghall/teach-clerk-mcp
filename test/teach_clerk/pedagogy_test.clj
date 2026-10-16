(ns teach-clerk.pedagogy-test
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [teach-clerk.board :as board]
            [teach-clerk.mcp :as mcp]
            [teach-clerk.oauth :as oauth]
            [teach-clerk.pedagogy :as pedagogy]
            [teach-clerk.rooms :as rooms]
            [teach-clerk.sandbox :as sandbox])
  (:import [java.io ByteArrayInputStream]))

(deftest teachers-are-guided-through-the-mcp-interface
  (binding [oauth/*settings* {:issuer "http://127.0.0.1:10000"
                              :signing-secret "testing-signing-secret-with-enough-length"}]
    (let [tool (first (mcp/tools))
          description (:description tool)
          req {:uri "/mcp" :request-method :post
               :headers {"content-type" "application/json"}
               :body (ByteArrayInputStream.
                      (.getBytes
                       (json/write-str
                        {:jsonrpc "2.0" :id 1 :method "initialize"
                         :params {:protocolVersion "2025-11-25"}})
                       "UTF-8"))}
          instructions (get-in (json/read-str (:body (mcp/handle req)))
                               ["result" "instructions"])]
      (is (= "teach_then_ask" (:name tool)))
      (is (str/includes? description "ASK TO REVEAL THINKING"))
      (is (str/includes? description "SCAFFOLD WITHOUT LEADING"))
      (is (str/includes? description "GOOD BLACKBOARD TEACHING"))
      (is (str/includes? description "MAKE VISUAL TEACHING AIDS"))
      (is (str/includes? description "BBS character art"))
      (is (str/includes? description "clerk/html"))
      (is (str/includes? description "clerk/plotly"))
      (is (str/includes? description "clerk/tex"))
      (is (str/includes? description pedagogy/svg-example-source))
      (is (str/includes? description "RESPECT THINKING TIME"))
      (is (str/includes? instructions "exploratory"))
      (is (str/includes? instructions "visual argument"))
      (is (str/includes? instructions "Preserve the original GOAL")))))

(deftest vector-board-example-is-real-executable-clojure
  (let [generated (sandbox/evaluate! pedagogy/svg-example-source)
        room (rooms/create!)]
    (try
      (is (= 3 (count generated)))
      (is (= [:md :html :md] (mapv :teach/view generated)))
      (is (= :svg (first (:teach/value (second generated)))))
      (is (= 15 (count (drop 2 (:teach/value (second generated))))))
      (board/validate-value! generated)
      (rooms/begin! room pedagogy/svg-example-source generated)
      (let [fragment (nth (:nextjournal.clerk/fragment
                           (board/lesson-value room)) 2)
            elements (vec (:nextjournal.clerk/fragment fragment))
            drawing (second elements)]
        (is (= 3 (count elements)))
        (is (= 'nextjournal.clerk.viewer/html-viewer
               (:nextjournal/viewer drawing)))
        (is (= :svg (first (:nextjournal/value drawing))))
        (is (= "0 0 200 130" (get-in drawing [:nextjournal/value 1 :viewBox])))
        (is (= 15 (count (drop 2 (:nextjournal/value drawing))))))
      (finally (rooms/discard! room)))))

(deftest vector-illustrations-support-labels-gradients-and-arrows
  (let [source
        (str "(clerk/html [:svg {:viewBox \"0 0 240 120\"} "
             "[:defs [:linearGradient {:id \"shade\"} "
             "[:stop {:offset \"0%\" :stop-color \"#9fd4ff\"}] "
             "[:stop {:offset \"100%\" :stop-color \"#315f91\"}]] "
             "[:marker {:id \"arrow\" :markerWidth 9 :markerHeight 9 "
             ":refX 7 :refY 4 :orient \"auto\"} "
             "[:path {:d \"M0,0 L8,4 L0,8 Z\" :fill \"#315f91\"}]]] "
             "[:circle {:cx 36 :cy 50 :r 24 :fill \"url(#shade)\"}] "
             "[:line {:x1 60 :y1 50 :x2 210 :y2 50 "
             ":stroke \"#315f91\" :stroke-width 3 :marker-end \"url(#arrow)\"}] "
             "[:text {:x 40 :y 100 :font-size 18 :font-weight \"bold\"} "
             "\"观察变化\"]])")
        value (sandbox/evaluate! source)
        room (rooms/create!)]
    (try
      (board/validate-value! value)
      (rooms/begin! room source value)
      (let [html (-> (board/lesson-value room)
                     :nextjournal.clerk/fragment
                     (nth 2)
                     :nextjournal/value)]
        (is (= :svg (first html)))
        (is (= :defs (first (nth html 2))))
        (is (= :circle (first (nth html 3))))
        (is (= "url(#shade)" (get-in html [3 1 :fill])))
        (is (= "url(#arrow)" (get-in html [4 1 :marker-end])))
        (is (= "bold" (get-in html [5 1 :font-weight]))))
      (finally (rooms/discard! room)))))

(deftest executable-svg-keeps-the-browser-safety-boundary
  (is (thrown? Exception
               (board/validate-value!
                (sandbox/evaluate! "(clerk/html [:script \"alert(1)\"])"))))
  (let [room (rooms/create!)
        v (sandbox/evaluate!
           "(clerk/html [:svg {:onClick \"alert(1)\" :viewBox \"0 0 40 40\"} [:circle {:cx 20 :cy 20 :r 10}]])")]
    (try
      (rooms/begin! room "s" v)
      (let [attrs (-> (board/lesson-value room)
                      :nextjournal.clerk/fragment
                      (nth 2)
                      :nextjournal/value
                      second)]
        (is (not (contains? attrs :onClick)))
        (is (= "0 0 40 40" (:viewBox attrs))))
      (finally (rooms/discard! room)))))
