(ns teach-clerk.state
  "One active lesson, with append-only events and one outstanding question.")

(defonce !lesson
  (atom {:events [] :next-id 1 :pending nil :closed? false :revision 0}))

(defn snapshot [] @!lesson)

(defn status []
  (let [{:keys [pending closed? revision]} @!lesson]
    {:pending (boolean pending)
     :question_id (:id pending)
     :closed closed?
     :revision revision}))

(defn busy? []
  (let [{:keys [pending closed?]} @!lesson]
    (or pending closed?)))

(defn begin! [source value]
  (locking !lesson
    (let [{:keys [pending closed? next-id]} @!lesson]
      (when (or pending closed?)
        (throw (ex-info (if closed? "The lesson has ended" "A question is already pending")
                        {:kind :conflict})))
      (let [answer (promise)
            entry {:id next-id :role :teacher :source source :value value}]
        (swap! !lesson
               (fn [s]
                 (-> s
                     (update :events conj entry)
                     (assoc :pending {:id next-id :reply answer})
                     (update :next-id inc)
                     (update :revision inc))))
        {:id next-id :reply answer}))))

(defn reply! [id text]
  (locking !lesson
    (let [{:keys [pending closed? next-id]} @!lesson]
      (when (or closed? (nil? pending) (not= id (:id pending)))
        (throw (ex-info "No matching question is awaiting an answer" {:kind :conflict})))
      (when (or (and (some? text) (not (string? text)))
                (and (string? text) (> (count text) 10000)))
        (throw (ex-info "Reply must be text or null (at most 10,000 characters)" {:kind :invalid})))
      (swap! !lesson
             (fn [s]
               (-> s
                   (assoc :pending nil)
                   (update :events conj {:id next-id :role :student :text text})
                   (update :next-id inc)
                   (update :revision inc))))
      (deliver (:reply pending) text)
      text)))

(defn abandon! [id]
  "Cancel a question when its MCP client disconnects. Keep an audit event."
  (locking !lesson
    (let [{:keys [pending next-id]} @!lesson]
      (when (and pending (= id (:id pending)))
        (swap! !lesson
               (fn [s]
                 (-> s
                     (assoc :pending nil)
                     (update :events conj {:id next-id :role :abandoned})
                     (update :next-id inc)
                     (update :revision inc))))
        (deliver (:reply pending) ::abandoned)
        true))))

(defn finish! []
  (locking !lesson
    (let [{:keys [pending next-id closed?]} @!lesson]
      (when pending
        (throw (ex-info "Cannot finish while a question is awaiting an answer" {:kind :conflict})))
      (when-not closed?
        (swap! !lesson (fn [s]
                         (-> s
                             (assoc :closed? true)
                             (update :events conj {:id next-id :role :finished})
                             (update :next-id inc)
                             (update :revision inc))))))
    nil))

(defn reset-for-tests! []
  (reset! !lesson {:events [] :next-id 1 :pending nil :closed? false :revision 0}))
