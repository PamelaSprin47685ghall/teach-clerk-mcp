(ns teach-clerk.entrypoint
  "A UI-only MCP App entrypoint. It does not teach, open a classroom session,
   or add a model-visible tool. The classroom remains in the external browser."
  (:require [teach-clerk.oauth :as oauth]))

(def uri "ui://teach-clerk/classroom-entry-v1")
(def mime-type "text/html;profile=mcp-app")

(defn tool []
  {:name "open_teach_clerk"
   :title "课堂入口"
   :description
   "Open the Teach Clerk connection guide in ChatGPT. This UI-only tool is not used for teaching."
   :inputSchema {:type "object"
                 :properties {}
                 :additionalProperties false}
   :securitySchemes [{:type "oauth2" :scopes [oauth/scope-name]}]
   :_meta {:ui {:resourceUri uri
                :visibility ["app"]}
           "openai/ui" {:entrypoints [{:type "thread"} {:type "global"}]}
           "openai/outputTemplate" uri}})

(defn resource []
  {:uri uri
   :name "Teach Clerk classroom entry"
   :title "课堂入口"
   :mimeType mime-type
   :description "Guide to pairing an external Clerk classroom with ChatGPT."})

(defn- html []
  (str "<!doctype html><html lang=\"zh-CN\"><head>"
       "<meta charset=\"utf-8\">"
       "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
       "<title>Teach Clerk · 课堂入口</title>"
       "<style>"
       "html{font-family:system-ui,-apple-system,sans-serif;color:#242833;"
       "color-scheme:light dark}"
       "body{margin:0;padding:28px 24px;line-height:1.65}"
       "main{max-width:620px;margin:auto}"
       "h1{font-size:21px;margin:0 0 12px}"
       "p{margin:8px 0 16px}"
       "a{color:#7563cf;text-decoration:underline;overflow-wrap:anywhere}"
       "code{border-radius:6px;padding:2px 6px;background:#8882}"
       ".note{font-size:13px;opacity:.75}"
       "</style></head><body><main>"
       "<h1>Teach Clerk 已连接</h1>"
       "<p>学生使用另一个浏览器标签页学习，"
       "AI 教师通过同一会话中的 <code>teach_then_ask</code> "
       "持续生成 Clerk 白板并等待回答。</p>"
       "<p>在浏览器打开：<a href=\"" (oauth/issuer)
       "\" target=\"_blank\" rel=\"noopener noreferrer\">"
       (oauth/issuer) "</a></p>"
       "<p>将网页显示的六位配对码发送给 ChatGPT，并指定学习目标。"
       "随后继续在外部白板回答即可。无需每回合重新打开本入口。</p>"
       "<p class=\"note\">这是 ChatGPT 中的插件入口，"
       "不是第二个课堂或第二套 AI 教师。</p>"
       "</main></body></html>"))

(defn read-resource []
  {:contents [{:uri uri
               :mimeType mime-type
               :text (html)
               :_meta {"openai/ui" {:availableDisplayModes ["inline" "fullscreen"]}
                       :ui {:prefersBorder true
                            :csp {:connectDomains []
                                  :resourceDomains []}}}}]})

(defn open-result []
  {:content [{:type "text"
              :text (str "Teach Clerk's conversation entrypoint is open. "
                         "For actual teaching, call teach_then_ask with "
                         "the student's six-digit pairing_code and a Clojure "
                         "source. The student opens " (oauth/issuer)
                         " in a separate browser tab.")}]
   :structuredContent {:connected true
                       :student_url (oauth/issuer)
                       :entrypoint "thread-or-global"
                       :teaching_tool "teach_then_ask"}})
