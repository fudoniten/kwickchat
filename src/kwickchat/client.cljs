(ns kwickchat.client
  "The whole front end: a landing screen, a username picker, and the chat.
  No framework — just a few DOM helpers, fetch, and EventSource."
  (:require [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Emoji palette: a small set of common & funny ones, Minecraft-flavoured.
;; ---------------------------------------------------------------------------

(def emojis
  ["😀" "😂" "😎" "😭" "😡" "🤔" "🙃" "😴" "🤯" "🥳"
   "👍" "👎" "👋" "🙌" "👀" "💩" "🔥" "🎉" "❤️" "💯"
   "⛏️" "🗡️" "🛡️" "🟩" "🐉" "🧟" "🐷" "🐮" "💎" "🍖"
   "🛏️" "🚪" "💥" "✅" "❌" "⭐" "🌙" "🧠" "🚀" "🎮"])

;; ---------------------------------------------------------------------------
;; Random, hard-to-guess but shareable room slugs for the landing screen.
;; ---------------------------------------------------------------------------

(def ^:private adjectives
  ["happy" "shiny" "brave" "sneaky" "mighty" "fuzzy" "turbo" "cosmic"
   "spicy" "golden" "silent" "wild" "lucky" "mega" "ninja" "epic"])

(def ^:private nouns
  ["creeper" "diamond" "piglet" "zombie" "dragon" "pickaxe" "redstone"
   "enderman" "llama" "skeleton" "phantom" "goat" "axolotl" "wolf" "fox"])

(defn- random-slug []
  (str (rand-nth adjectives) "-" (rand-nth nouns) "-" (rand-int 1000)))

;; ---------------------------------------------------------------------------
;; App state
;; ---------------------------------------------------------------------------

(defonce state (atom {:room nil :username nil :last-id 0 :stick true}))

(declare enter-chat)

;; ---------------------------------------------------------------------------
;; DOM helpers
;; ---------------------------------------------------------------------------

(defn- by-id [id] (.getElementById js/document id))
(defn- clear! [node] (set! (.-innerHTML node) ""))

(defn- node
  "Create an element. opts may include :class :id :text :placeholder :value
  and event handlers :on-click :on-keydown :on-input :on-scroll."
  [tag opts & children]
  (let [e (.createElement js/document (name tag))]
    (when-let [c (:class opts)]       (set! (.-className e) c))
    (when-let [i (:id opts)]          (set! (.-id e) i))
    (when (contains? opts :text)      (set! (.-textContent e) (:text opts)))
    (when-let [p (:placeholder opts)] (.setAttribute e "placeholder" p))
    (when-let [v (:value opts)]       (set! (.-value e) v))
    (when-let [f (:on-click opts)]    (.addEventListener e "click" f))
    (when-let [f (:on-keydown opts)]  (.addEventListener e "keydown" f))
    (when-let [f (:on-input opts)]    (.addEventListener e "input" f))
    (when-let [f (:on-scroll opts)]   (.addEventListener e "scroll" f))
    (doseq [c children :when c]
      (.appendChild e (if (string? c) (.createTextNode js/document c) c)))
    e))

;; ---------------------------------------------------------------------------
;; HTTP helpers (always decode JSON to plain Clojure maps so advanced
;; compilation never renames data keys)
;; ---------------------------------------------------------------------------

(defn- fetch-json [url opts]
  (-> (js/fetch url (clj->js (merge {:credentials "same-origin"} opts)))
      (.then (fn [r] (.json r)))
      (.then (fn [j] (js->clj j :keywordize-keys true)))))

(defn- post-json [url data]
  (fetch-json url {:method "POST"
                   :headers {"Content-Type" "application/json"}
                   :body (js/JSON.stringify (clj->js data))}))

(defn- enc [s] (js/encodeURIComponent s))

;; ---------------------------------------------------------------------------
;; Scrolling / "jump to bottom" behaviour
;; ---------------------------------------------------------------------------

(defn- show-jump [show?]
  (when-let [j (by-id "jump")]
    (if show?
      (.remove (.-classList j) "hidden")
      (.add (.-classList j) "hidden"))))

(defn- scroll-bottom! []
  (when-let [h (by-id "history")]
    (set! (.-scrollTop h) (.-scrollHeight h))
    (swap! state assoc :stick true)
    (show-jump false)))

(defn- on-scroll [h]
  (let [near (<= (- (.-scrollHeight h) (.-scrollTop h) (.-clientHeight h)) 40)]
    (swap! state assoc :stick near)
    (show-jump (not near))))

;; ---------------------------------------------------------------------------
;; Messages
;; ---------------------------------------------------------------------------

(defn- append-message [{:keys [id username body]}]
  (when-let [h (by-id "history")]
    (.appendChild h
      (node :div {:class "msg"}
        (node :span {:class "msg-user" :text (str username)})
        (node :span {:class "msg-body" :text (str body)})))
    (swap! state update :last-id max id)
    (if (:stick @state)
      (scroll-bottom!)
      (show-jump true))))

(defn- load-history [room]
  (-> (fetch-json (str "/api/messages?room=" (enc room) "&since=0") {})
      (.then (fn [r]
               (doseq [m (:messages r)] (append-message m))
               (scroll-bottom!)))))

(defn- open-stream [room]
  (let [src (js/EventSource. (str "/api/stream?room=" (enc room)))
        opened (atom false)]
    (set! (.-onmessage src)
          (fn [ev]
            (let [msg (js->clj (js/JSON.parse (.-data ev)) :keywordize-keys true)]
              (when (> (:id msg) (:last-id @state))
                (append-message msg)))))
    ;; On a reconnect, pull anything we missed while disconnected.
    (set! (.-onopen src)
          (fn [_]
            (when @opened
              (-> (fetch-json (str "/api/messages?room=" (enc room)
                                   "&since=" (:last-id @state)) {})
                  (.then (fn [r]
                           (doseq [m (:messages r)]
                             (when (> (:id m) (:last-id @state))
                               (append-message m)))))))
            (reset! opened true)))))

(defn- do-send [room input]
  (let [body (str/trim (.-value input))]
    (when (seq body)
      (set! (.-value input) "")
      (swap! state assoc :stick true)
      (.focus input)
      (-> (post-json "/api/send" {:room room :body body})
          (.then (fn [r]
                   (when-not (:ok r)
                     (js/alert (or (:error r) "Could not send.")))))))))

;; ---------------------------------------------------------------------------
;; Screens
;; ---------------------------------------------------------------------------

(defn- mount! [child]
  (let [app (by-id "app")]
    (clear! app)
    (.appendChild app child)))

(defn- render-landing []
  (let [slug  (random-slug)
        input (node :input {:class "slug-input" :value slug})
        go    (fn [] (let [v (str/replace (str/trim (.-value input)) #"^/+" "")]
                       (when (seq v) (set! (.-href js/location) (str "/" v)))))]
    (.addEventListener input "keydown"
                       (fn [e] (when (= (.-key e) "Enter") (go))))
    (mount!
     (node :div {:class "landing"}
       (node :h1 {:text "kwickchat"})
       (node :p {:text "Every link is its own chat. Open one, pick a name, start talking. Share the link with your friends to chat together."})
       (node :div {:class "slug-row"}
         (node :span {:class "origin" :text (str (.-origin js/location) "/")})
         input)
       (node :button {:class "primary" :text "Open chat" :on-click go})))
    (.focus input)))

(defn- render-username [room]
  (let [input  (node :input {:class "name-input" :placeholder "Pick a username"})
        err    (node :div {:class "error"})
        submit (fn []
                 (let [name (str/trim (.-value input))]
                   (when (seq name)
                     (-> (post-json "/api/join" {:room room :username name})
                         (.then (fn [r]
                                  (if (:ok r)
                                    (enter-chat room (:username r))
                                    (set! (.-textContent err)
                                          (or (:error r) "Could not join.")))))))))]
    (.addEventListener input "keydown"
                       (fn [e] (when (= (.-key e) "Enter") (submit))))
    (mount!
     (node :div {:class "join"}
       (node :h1 {:text "Join chat"})
       (node :p {:class "room-label" :text (str "Chat: " room)})
       input
       err
       (node :button {:class "primary" :text "Join" :on-click submit})))
    (.focus input)))

(defn enter-chat [room username]
  (swap! state assoc :room room :username username :last-id 0 :stick true)
  (let [history (node :div {:class "history" :id "history"
                            :on-scroll (fn [e] (on-scroll (.-target e)))})
        jump    (node :button {:class "jump hidden" :id "jump" :text "↓ Jump to newest"
                               :on-click (fn [] (scroll-bottom!))})
        input   (node :input {:class "msg-input" :id "msg" :placeholder "Type a message…"})
        send    #(do-send room input)
        send-bt (node :button {:class "send" :text "Send" :on-click send})
        palette (node :div {:class "palette"})]
    (.addEventListener input "keydown"
                       (fn [e] (when (= (.-key e) "Enter") (.preventDefault e) (send))))
    (doseq [em emojis]
      (.appendChild palette
        (node :button {:class "emoji" :text em
                       :on-click (fn []
                                   (set! (.-value input) (str (.-value input) em))
                                   (.focus input))})))
    (mount!
     (node :div {:class "chat"}
       (node :div {:class "topbar"}
         (node :span {:class "room-name" :text room})
         (node :span {:class "me" :text (str "you: " username)}))
       (node :div {:class "history-wrap"} history jump)
       palette
       (node :div {:class "composer"} input send-bt)))
    (load-history room)
    (open-stream room)
    (.focus input)))

(defn- init-chat [room]
  (-> (fetch-json (str "/api/me?room=" (enc room)) {})
      (.then (fn [r]
               (if-let [u (:username r)]
                 (enter-chat room u)
                 (render-username room))))))

(defn ^:export main []
  (let [room (-> (.-pathname js/location)
                 (subs 1)
                 (str/replace #"/+$" ""))]
    (if (str/blank? room)
      (render-landing)
      (init-chat room))))

(main)
