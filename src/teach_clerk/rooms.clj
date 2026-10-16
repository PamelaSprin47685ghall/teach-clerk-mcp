(ns teach-clerk.rooms
  "One independent lesson and notebook for each browser tab."
  (:import [java.security SecureRandom]
           [java.util Base64]))

(defonce !all (atom {:tokens {} :codes {}}))
(def ^SecureRandom rng (SecureRandom.))
(def max-rooms 120)
(def idle-ms (* 20 60 1000))
(defn now [] (System/currentTimeMillis))
(defn random-token []
  (let [b (byte-array 24)]
    (.nextBytes rng b)
    (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) b)))
(defn random-code [] (format "%06d" (.nextInt rng 1000000)))
(defn by-token [token]
  (when (string? token) (get-in @!all [:tokens token])))
(defn by-code [code]
  (when (and (string? code) (re-matches #"[0-9]{6}" code))
    (by-token (get-in @!all [:codes code]))))
(defn touch! [room] (reset! (:last-seen room) (now)))

(defn create! []
  (locking !all
    (when (>= (count (:tokens @!all)) max-rooms)
      (throw (ex-info "Classroom capacity reached" {:kind :capacity})))
    (let [code (loop [n 0]
                 (when (> n 1000)
                   (throw (ex-info "No free pairing code" {:kind :capacity})))
                 (let [candidate (random-code)]
                   (if (contains? (:codes @!all) candidate)
                     (recur (inc n))
                     candidate)))
          token (random-token)
          room {:token token
                :code code
                :state (atom {:events [] :next-id 1 :pending nil
                              :closed? false :revision 0 :principal nil})
                :last-seen (atom (now))
                :view (atom nil)
                :sockets (atom #{})
                :streams (atom #{})}]
      (swap! !all
             (fn [s]
               (-> s
                   (assoc-in [:tokens token] room)
                   (assoc-in [:codes code] token))))
      room)))

(defn snapshot [room] @(:state room))
(defn status [room]
  (let [{:keys [pending closed? revision principal]} (snapshot room)]
    {:pending (boolean pending) :question_id (:id pending)
     :closed closed? :paired (boolean principal) :revision revision}))

(defn pair! [room principal]
  (locking (:state room)
    (let [old (:principal (snapshot room))]
      (when (or (nil? principal) (and old (not= old principal)))
        (throw (ex-info "This pairing code belongs to another login"
                        {:kind :pairing})))
      (when-not old
        (swap! (:state room) (fn [s]
                               (-> s (assoc :principal principal)
                                   (update :revision inc)))))
      (touch! room)
      room)))

(defn begin! [room source value]
  (locking (:state room)
    (let [{:keys [pending closed? next-id]} (snapshot room)]
      (when (or pending closed?)
        (throw (ex-info (if closed? "Lesson ended" "Question already pending")
                        {:kind :conflict})))
      (let [reply (promise)]
        (swap! (:state room)
               (fn [s]
                 (-> s
                     (update :events conj {:id next-id :role :teacher
                                           :source source :value value})
                     (assoc :pending {:id next-id :reply reply})
                     (update :next-id inc)
                     (update :revision inc))))
        (touch! room)
        {:id next-id :reply reply}))))

(defn answer! [room id text]
  (locking (:state room)
    (let [{:keys [pending next-id closed?]} (snapshot room)]
      (when (or closed? (nil? pending) (not= id (:id pending)))
        (throw (ex-info "Question not found" {:kind :conflict})))
      (when (or (and (some? text) (not (string? text)))
                (and (string? text) (> (count text) 10000)))
        (throw (ex-info "Reply must be text or null" {:kind :invalid})))
      (swap! (:state room)
             (fn [s]
               (-> s (assoc :pending nil)
                   (update :events conj {:id next-id :role :student :text text})
                   (update :next-id inc)
                   (update :revision inc))))
      (touch! room)
      (deliver (:reply pending) text)
      text)))

(defn abandon! [room id]
  (when room
    (locking (:state room)
      (let [{:keys [pending next-id]} (snapshot room)]
        (when (and pending (= id (:id pending)))
          (swap! (:state room)
                 (fn [s]
                   (-> s
                       (assoc :pending nil)
                       (update :events conj {:id next-id :role :abandoned})
                       (update :next-id inc)
                       (update :revision inc))))
          (deliver (:reply pending) ::abandoned)
          true)))))

(defn finish! [room]
  (locking (:state room)
    (let [{:keys [pending next-id closed?]} (snapshot room)]
      (when pending
        (throw (ex-info "Cannot finish with a pending question"
                        {:kind :conflict})))
      (when-not closed?
        (swap! (:state room)
               (fn [s]
                 (-> s (assoc :closed? true)
                     (update :events conj {:id next-id :role :finished})
                     (update :next-id inc)
                     (update :revision inc))))))
    nil))

(defn discard! [room]
  (when room
    (locking !all
      (when (= room (by-token (:token room)))
        (swap! !all
               (fn [s]
                 (-> s
                     (update :tokens dissoc (:token room))
                     (update :codes dissoc (:code room)))))))
    (locking (:state room)
      (when-let [pending (:pending (snapshot room))]
        (abandon! room (:id pending)))
      (swap! (:state room) assoc :closed? true :disposed? true))
    (reset! (:sockets room) #{})
    (reset! (:streams room) #{})
    (reset! (:view room) nil)
    true))

(defn reap! []
  (doseq [room (vals (:tokens @!all))]
    (when (> (- (now) @(:last-seen room)) idle-ms)
      (discard! room))))

(defn reset-for-tests! []
  (doseq [room (vals (:tokens @!all))]
    (discard! room))
  (reset! !all {:tokens {} :codes {}}))
