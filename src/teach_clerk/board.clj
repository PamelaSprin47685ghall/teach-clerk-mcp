(ns teach-clerk.board
  {:nextjournal.clerk/visibility {:code :hide :result :hide}
   :nextjournal.clerk/no-cache true}
  (:require [nextjournal.clerk :as clerk]
            [teach-clerk.rooms :as rooms]))

(def svg-tags
  #{:svg :g :path :circle :rect :line :polyline :polygon :ellipse :text})
(def html-tags
  #{:div :span :p :strong :em :small :ul :ol :li :table :tr :th :td :h1 :h2 :h3 :pre :code})
(def html-attrs
  #{:class :id :title :width :height :viewBox :d :x :y :x1 :y1 :x2 :y2
    :cx :cy :r :rx :ry :points :fill :stroke :stroke-width :font-size
    :text-anchor :transform :colSpan :rowSpan})

(defn- clean-hiccup [x]
  (cond
    (vector? x)
    (let [[tag & children] x
          [attrs children] (if (map? (first children))
                             [(first children) (rest children)]
                             [{} children])]
      (when-not (or (html-tags tag) (svg-tags tag))
        (throw (ex-info "HTML element is not allowed" {:element tag})))
      (let [attrs (into {} (filter (fn [[k v]]
                                    (and (html-attrs k)
                                         (or (string? v) (number? v)))) attrs))]
        (into [tag attrs] (map clean-hiccup) children)))
    (sequential? x) (mapv clean-hiccup x)
    (or (string? x) (number? x) (nil? x)) x
    :else (str x)))

(defn- view-value [value]
  (if (and (map? value) (contains? value :teach/view))
    (let [{:keys [teach/view teach/value]} value]
      (case view
        :md (clerk/md (str value))
        :tex (clerk/tex (str value))
        :code (clerk/code value)
        :table (clerk/table value)
        :plotly (clerk/plotly value)
        :vl (clerk/vl value)
        :html (clerk/html (clean-hiccup value))
        :fragment (apply clerk/fragment (map view-value value))
        :row (apply clerk/row (map view-value value))
        :col (apply clerk/col (map view-value value))
        (throw (ex-info "Unsupported Clerk viewer" {:kind view}))))
    value))

(defn validate-value! [value]
  ;; Present the new result before appending it to the immutable board.
  (clerk/present (view-value value))
  value)

(defn- event-blocks [{:keys [role value text silent?]}]
  (case role
    :teacher (if silent?
               []
               [(clerk/html [:div {:class "text-slate-500 text-xs font-semibold"} "老师"])
                (view-value value)])
    :student [(clerk/html [:div {:class "text-slate-500 text-xs font-semibold"} "学生"])
              (clerk/html [:div {:class "border border-slate-200 rounded-xl p-3 bg-slate-50"}
                           (if (nil? text) "（跳过回答）" text)])]
    :abandoned [(clerk/md "*本轮提问已中断。*")]
    :finished [(clerk/md "---\n\n**本节课结束。**")]
    []))

(defn lesson-value [room]
  (apply clerk/fragment
         (concat [(clerk/md "# 课堂白板")]
                 (mapcat event-blocks (:events (rooms/snapshot room))))))

^{::clerk/visibility {:code :hide :result :show}}
(clerk/md "# 课堂白板")
