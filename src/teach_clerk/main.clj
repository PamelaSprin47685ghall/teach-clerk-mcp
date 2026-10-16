(ns teach-clerk.main
  (:require [teach-clerk.server :as server]))

(def app server/app)

(defn -main [& _]
  (server/start!))
