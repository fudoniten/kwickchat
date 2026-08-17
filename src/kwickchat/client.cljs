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
;; Per-user identity: a deterministic colour + identicon derived from the name.
;; Same username -> same colour and little pixel-icon, everywhere, with no
;; server state. Pure ClojureScript so the no-npm build stays intact.
;; ---------------------------------------------------------------------------

(defn- str-hash
  "FNV-1a 32-bit hash of a string, returned as a non-negative int."
  [s]
  (loop [i 0 h (int 2166136261)]
    (if (< i (count s))
      (recur (inc i) (js/Math.imul (bit-xor h (.charCodeAt s i)) 16777619))
      (bit-and h 0x7fffffff))))

(defn- user-color
  "A vivid, readable HSL colour for a username (stable across sessions)."
  [name]
  (str "hsl(" (mod (str-hash name) 360) " 65% 55%)"))

(defn- user-bubble-color
  "A darker HSL color for message bubbles (works well with white text)."
  [name]
  (str "hsl(" (mod (str-hash name) 360) " 45% 35%)"))

(def ^:private svg-ns "http://www.w3.org/2000/svg")

(defn- svg-node [tag attrs & children]
  (let [e (.createElementNS js/document svg-ns (name tag))]
    (doseq [[k v] attrs] (.setAttribute e (name k) (str v)))
    (doseq [c children :when c] (.appendChild e c))
    e))

(defn- identicon
  "A GitHub-style 5x5 identicon for a username: a horizontally-mirrored grid
  of coloured cells, deterministic from the name's hash. Returns an <svg> node."
  [name]
  (let [h     (str-hash name)
        color (user-color name)
        ;; columns 0,1,2 are seeded; 3,4 mirror 1,0 -> 15 bits, one per cell
        rects (for [col (range 3) row (range 5)
                    :when (bit-test h (+ (* col 5) row))
                    c     (distinct [col (- 4 col)])]
                (svg-node :rect {:x c :y row :width 1 :height 1 :fill color}))]
    (apply svg-node :svg
           {:class "identicon" :viewBox "0 0 5 5" :width 40 :height 40
            :aria-hidden "true"}
           rects)))

(defn- avatar-or-identicon
  "Returns either a custom avatar <img> or an identicon <svg> for a user."
  [name avatar-filename]
  (if avatar-filename
    (let [img (.createElement js/document "img")]
      (set! (.-src img) (str "/avatars/" avatar-filename))
      (set! (.-className img) "identicon")
      (set! (.-width img) 40)
      (set! (.-height img) 40)
      img)
    (identicon name)))

;; ---------------------------------------------------------------------------
;; App state
;; ---------------------------------------------------------------------------

(defonce state (atom {:room nil :username nil :avatar nil :last-id 0 :stick true
                      :avatars [] :notes {} :notify {} :minecraft {}}))

(declare enter-chat upsert-note! refresh-minecraft-btn!)

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

(defn- join-node
  "A centred, non-bubble system notice: avatar + \"<name> has joined\"."
  [name avatar]
  (node :div {:class "msg-join"}
    (avatar-or-identicon name avatar)
    (node :span {:class "msg-join-text"}
      (node :span {:class "msg-join-name" :text name})
      " has joined")))

(defn- chat-node [name body avatar]
  (let [is-mine (= name (:username @state))
        msg-div (node :div {:class (if is-mine "msg msg-mine" "msg")})]
    ;; Set per-user bubble background color
    (set! (.. msg-div -style -backgroundColor) (user-bubble-color name))
    (.appendChild msg-div
      (node :span {:class "msg-head"}
        (avatar-or-identicon name avatar)
        (node :span {:class "msg-user" :text name})))
    (.appendChild msg-div
      (node :span {:class "msg-body" :text (str body)}))
    msg-div))

(defonce ^:private audio-ctx (atom nil))

(defn- get-audio-ctx []
  (when-let [ctor (or (.-AudioContext js/window) (.-webkitAudioContext js/window))]
    (or @audio-ctx (reset! audio-ctx (new ctor)))))

(defn- play-ding!
  "A short, subtle two-tone chime for incoming messages. Best-effort: browsers
  that block audio before a user gesture (or lack Web Audio) just stay silent."
  []
  (try
    (when-let [ctx (get-audio-ctx)]
      (when (= (.-state ctx) "suspended") (.resume ctx))
      (doseq [[freq offset] [[880 0] [1318.5 0.09]]]
        (let [now  (+ (.-currentTime ctx) offset)
              osc  (.createOscillator ctx)
              gain (.createGain ctx)]
          (set! (.-type osc) "sine")
          (.setValueAtTime (.-frequency osc) freq now)
          (.setValueAtTime (.-gain gain) 0 now)
          (.linearRampToValueAtTime (.-gain gain) 0.12 (+ now 0.01))
          (.exponentialRampToValueAtTime (.-gain gain) 0.0001 (+ now 0.25))
          (.connect osc gain)
          (.connect gain (.-destination ctx))
          (.start osc now)
          (.stop osc (+ now 0.25)))))
    (catch :default _)))

;; ---------------------------------------------------------------------------
;; Unread badge: while the tab is in the background, count what you're missing
;; and show it on the favicon, the tab title, and the app icon where the
;; browser has one. This is the "you're still in the browser" half of catching
;; up; the away-from-keyboard half is push notifications, further down.
;; ---------------------------------------------------------------------------

(defonce ^:private unread (atom 0))

(defonce ^:private base-icon
  (let [img (js/Image.)]
    (set! (.-src img) "/favicon.ico")
    img))

(defn- watching?
  "True when this tab is on screen *and* focused — otherwise anything arriving
  counts as unread."
  []
  (and (not (.-hidden js/document)) (.hasFocus js/document)))

(defn- icon-link []
  (or (.querySelector js/document "link[rel~='icon']")
      (let [l (.createElement js/document "link")]
        (.setAttribute l "rel" "icon")
        (.appendChild (.-head js/document) l)
        l)))

(defn- badge-favicon!
  "Redraw the favicon with `n` tagged in the corner; n=0 restores the plain one."
  [n]
  (try
    (let [l (icon-link)]
      (if (zero? n)
        (.setAttribute l "href" "/favicon.ico")
        (let [c (.createElement js/document "canvas")
              g (do (set! (.-width c) 64) (set! (.-height c) 64)
                    (.getContext c "2d"))]
          ;; Start from the real favicon when it has loaded, else a plain
          ;; grass-green tile so the badge still has something to sit on.
          (if (and (.-complete base-icon) (pos? (.-naturalWidth base-icon)))
            (.drawImage g base-icon 0 0 64 64)
            (do (set! (.-fillStyle g) "#5cab3f")
                (.fillRect g 0 0 64 64)))
          (set! (.-fillStyle g) "#d64541")
          (.beginPath g)
          (.arc g 43 43 21 0 (* 2 js/Math.PI))
          (.fill g)
          (set! (.-fillStyle g) "#ffffff")
          (set! (.-font g) "bold 28px system-ui, sans-serif")
          (set! (.-textAlign g) "center")
          (set! (.-textBaseline g) "middle")
          (.fillText g (if (> n 9) "9+" (str n)) 43 45)
          (.setAttribute l "href" (.toDataURL c "image/png")))))
    (catch :default _)))

(defn- app-badge!
  "Badge the installed-app icon, on the browsers that have that API."
  [n]
  (try
    (let [nav js/navigator
          ;; Reached by string so advanced compilation can't rename it away.
          f (aget nav (if (pos? n) "setAppBadge" "clearAppBadge"))]
      (when f (if (pos? n) (.call f nav n) (.call f nav))))
    (catch :default _)))

(defn- refresh-badges! []
  (let [n @unread]
    (set! (.-title js/document)
          (str (when (pos? n) (str "(" n ") ")) (:room @state) " - kwickchat"))
    (badge-favicon! n)
    (app-badge! n)))

(defn- note-unread! []
  (swap! unread inc)
  (refresh-badges!))

(defn- clear-unread! []
  (when (pos? @unread)
    (reset! unread 0)
    (refresh-badges!)))

;; ---------------------------------------------------------------------------
;; Presence: tell the server we're actually here, so it knows when we're not.
;; Silence for a couple of minutes is what turns a message into a phone push.
;; ---------------------------------------------------------------------------

(defn- ping-active! [room]
  (when-not (.-hidden js/document)
    (-> (post-json "/api/active" {:room room})
        (.catch (fn [_] nil)))))

(defn- append-message [{:keys [id username body avatar kind]}]
  (when-let [h (by-id "history")]
    (let [name (str username)]
      (.appendChild h (if (= kind "join")
                        (join-node name avatar)
                        (chat-node name body avatar))))
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
                (append-message msg)
                (when (not= (:username msg) (:username @state))
                  (when (= (:kind msg) "chat") (play-ding!))
                  (when-not (watching?) (note-unread!)))))))
    ;; Post-it updates ride the same stream under a named "note" event.
    (.addEventListener src "note"
          (fn [ev]
            (let [note (js->clj (js/JSON.parse (.-data ev)) :keywordize-keys true)]
              (upsert-note! note))))
    ;; So does the room's Minecraft target — it belongs to the room, so when
    ;; somebody changes it everyone's pickaxe button should say so at once.
    (.addEventListener src "minecraft"
          (fn [ev]
            (let [msg (js->clj (js/JSON.parse (.-data ev)) :keywordize-keys true)]
              (swap! state assoc-in [:minecraft :server] (:server msg))
              (refresh-minecraft-btn!))))
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
;; Avatar picker component
;; ---------------------------------------------------------------------------

(defn- show-avatar-picker
  "Shows a modal popup to select an avatar. on-select is called with the chosen filename or nil for identicon."
  [on-select]
  (let [overlay (node :div {:class "avatar-picker-overlay"})
        picker  (node :div {:class "avatar-picker"})
        close   (fn [] (.remove overlay))
        cancel-btn (node :button {:class "primary" :text "Cancel" :on-click close})
        
        ;; Option for identicon (nil avatar)
        identicon-opt (node :div {:class "avatar-option"
                                  :on-click (fn [] (on-select nil) (close))})
        sample-identicon (identicon (:username @state))]
    
    (.appendChild identicon-opt sample-identicon)
    (.appendChild identicon-opt (node :div {:class "avatar-label" :text "Default"}))
    (.appendChild picker (node :h3 {:text "Choose Avatar"}))
    (.appendChild picker cancel-btn)
    (.appendChild picker identicon-opt)
    
    ;; Load and display available avatars, sorted naturally by number
    (-> (fetch-json "/api/avatars" {})
        (.then (fn [r]
                 (let [avatars (->> (:avatars r)
                                    (sort-by #(js/parseInt (str/replace % #"\D+" "") 10)))]
                   (doseq [avatar avatars]
                     (let [opt (node :div {:class "avatar-option"
                                           :on-click (fn [] (on-select avatar) (close))})
                           img (.createElement js/document "img")
                           ;; Extract name without extension and numbers
                           label (-> avatar
                                     (str/replace #"\.\w+$" "")
                                     (str/replace #"^\d+" "")
                                     (str/trim))]
                       (set! (.-src img) (str "/avatars/" avatar))
                       (set! (.-width img) 64)
                       (set! (.-height img) 64)
                       (.appendChild opt img)
                       (when (seq label)
                         (.appendChild opt (node :div {:class "avatar-label" :text label})))
                       (.appendChild picker opt)))))))
    
    (.appendChild overlay picker)
    (.appendChild (.-body js/document) overlay)))

;; ---------------------------------------------------------------------------
;; Post-it notes: one pinned note per member, shown in a side panel. Notes are
;; keyed by username (unique within a room) and arrive live over SSE.
;; ---------------------------------------------------------------------------

(def ^:private weekdays
  ["Sun" "Mon" "Tue" "Wed" "Thu" "Fri" "Sat"])

(def ^:private months
  ["January" "February" "March" "April" "May" "June" "July"
   "August" "September" "October" "November" "December"])

(defn- format-note-date
  "Kid-friendly short date for a post-it, e.g. \"Sat, July 11\" (no year)."
  [ms]
  (let [d (js/Date. ms)]
    (str (get weekdays (.getDay d)) ", "
         (get months (.getMonth d)) " " (.getDate d))))

(def ^:private week-ms (* 7 24 60 60 1000))

(defn- stale-note?
  "Post-its older than a week are probably forgotten, but still useful — fade them."
  [ms]
  (and ms (> (- (js/Date.now) ms) week-ms)))

(defn- postit-node [{:keys [username avatar body updated_at]}]
  (let [name  (str username)
        stale (stale-note? updated_at)
        card  (node :div {:class (if stale "postit stale" "postit")})]
    ;; Tint the note with the poster's stable hashed colour.
    (set! (.. card -style -backgroundColor) (user-color name))
    (.appendChild card
      (node :div {:class "postit-head"}
        (avatar-or-identicon name avatar)
        (node :span {:class "postit-user" :text name})
        (when updated_at
          (node :span {:class "postit-date"
                       :text (format-note-date updated_at)}))))
    (.appendChild card (node :div {:class "postit-body" :text (str body)}))
    card))

(defn- render-postits! []
  (when-let [list (by-id "postits-list")]
    (clear! list)
    (let [notes (sort-by :updated_at > (vals (:notes @state)))]
      (if (empty? notes)
        (.appendChild list
          (node :div {:class "postit-empty"
                      :text "No notes yet. Leave one so friends know when you're on!"}))
        (doseq [n notes] (.appendChild list (postit-node n))))))
  (when-let [btn (by-id "postit-btn")]
    (set! (.-textContent btn)
          (if (contains? (:notes @state) (:username @state))
            "Replace post" "Make post"))))

(defn- upsert-note! [note]
  (swap! state assoc-in [:notes (:username note)] note)
  (render-postits!))

(defn- load-notes [room]
  (-> (fetch-json (str "/api/notes?room=" (enc room)) {})
      (.then (fn [r]
               (swap! state assoc :notes
                      (into {} (map (juxt :username identity) (:notes r))))
               (render-postits!)))))

(defn- show-note-composer
  "Modal to compose or overwrite the current user's post-it (max 128 chars)."
  [room]
  (let [overlay  (node :div {:class "avatar-picker-overlay"})
        modal    (node :div {:class "note-modal"})
        existing (get-in @state [:notes (:username @state)])
        textarea (node :textarea {:class "note-textarea"
                                  :placeholder "e.g. On the server Thursday @ 8pm!"})
        counter  (node :div {:class "note-count"})
        close    (fn [] (.remove overlay))
        update-count (fn []
                       (set! (.-textContent counter)
                             (str (.. textarea -value -length) " / 128")))
        save (fn []
               (let [body (str/trim (.-value textarea))]
                 (when (seq body)
                   (-> (post-json "/api/note" {:room room :body body})
                       (.then (fn [r]
                                (if (:ok r)
                                  (do (upsert-note! (:note r)) (close))
                                  (js/alert (or (:error r) "Could not save note.")))))))))]
    (set! (.-maxLength textarea) 128)
    (when existing (set! (.-value textarea) (:body existing)))
    (.addEventListener textarea "input" update-count)
    (.addEventListener textarea "keydown"
                       (fn [e] (when (and (= (.-key e) "Enter") (not (.-shiftKey e)))
                                 (.preventDefault e) (save))))
    (update-count)
    (.appendChild modal (node :h3 {:text "Your post-it"}))
    (.appendChild modal textarea)
    (.appendChild modal counter)
    (.appendChild modal
      (node :div {:class "note-actions"}
        (node :button {:class "ghost" :text "Cancel" :on-click close})
        (node :button {:class "primary" :text "Save" :on-click save})))
    (.appendChild overlay modal)
    (.appendChild (.-body js/document) overlay)
    (.focus textarea)))

(defn- toggle-postits! []
  (when-let [l (by-id "layout")]
    (.toggle (.-classList l) "show-postits")))

(defn- close-postits! []
  (when-let [l (by-id "layout")]
    (.remove (.-classList l) "show-postits")))

(defn- postit-panel [room]
  (node :div {:class "postits"}
    (node :div {:class "postits-head"}
      (node :span {:class "postits-title" :text "📌 Post-its"})
      (node :button {:class "postits-close" :text "✕" :on-click close-postits!}))
    (node :div {:class "postits-list" :id "postits-list"})
    (node :div {:class "postits-foot"}
      (node :button {:class "primary" :id "postit-btn" :text "Make post"
                     :on-click (fn [] (show-note-composer room))}))))

;; ---------------------------------------------------------------------------
;; Away notifications: a phone buzz for messages you miss while you're not at
;; the keyboard, delivered by ntfy (https://ntfy.sh) — no account, no email, in
;; keeping with the rest of the site. You invent a topic name, subscribe to it
;; in the ntfy app, and tell kwickchat what it is. The topic is a secret, just
;; like the room link, so we suggest a random one.
;; ---------------------------------------------------------------------------

(defn- hex2 [n]
  (let [s (.toString n 16)] (if (= 1 (count s)) (str "0" s) s)))

(defn- random-topic
  "A topic nobody is going to guess — anyone who knows it can notify you."
  []
  (let [bytes (js/Uint8Array. 6)]
    (if-let [c (.-crypto js/window)]
      (.getRandomValues c bytes)
      (dotimes [i 6] (aset bytes i (rand-int 256))))
    (str "kwick-" (str/join (map #(hex2 (aget bytes %)) (range 6))))))

(defn- link [href text]
  (let [a (node :a {:class "notify-link" :text text})]
    (.setAttribute a "href" href)
    (.setAttribute a "target" "_blank")
    (.setAttribute a "rel" "noopener noreferrer")
    a))

(defn- refresh-notify-btn! []
  (when-let [b (by-id "notify-btn")]
    (let [{:keys [available topic]} (:notify @state)]
      (set! (.. b -style -display) (if available "inline-flex" "none"))
      (set! (.-textContent b) (if topic "🔔" "🔕"))
      (.setAttribute b "title" (if topic
                                 "Away notifications are on"
                                 "Get notified when you're away")))))

(defn- show-notify-modal [room]
  (let [{:keys [server topic]} (:notify @state)
        overlay  (node :div {:class "avatar-picker-overlay"})
        modal    (node :div {:class "note-modal notify-modal"})
        input    (node :input {:class "notify-topic" :value (or topic (random-topic))})
        web-link (link (str server "/" (.-value input)) "watch it in a browser tab")
        err      (node :div {:class "error"})
        close    (fn [] (.remove overlay))
        finish   (fn [t]
                   (swap! state assoc-in [:notify :topic] t)
                   (refresh-notify-btn!)
                   (close))
        save     (fn []
                   (let [t (str/trim (.-value input))]
                     (-> (post-json "/api/notify" {:room room :topic t})
                         (.then (fn [r]
                                  (if (:ok r)
                                    (finish (:topic r))
                                    (set! (.-textContent err)
                                          (or (:error r) "Could not save."))))))))
        turn-off (fn []
                   (-> (post-json "/api/notify" {:room room :topic ""})
                       (.then (fn [_] (finish nil)))))
        copy     (fn []
                   (.select input)
                   (try
                     (when-let [cb (aget js/navigator "clipboard")]
                       (.call (aget cb "writeText") cb (.-value input)))
                     (catch :default _)))]
    ;; Keep the "watch in a browser" link pointed at whatever they've typed.
    (.addEventListener input "input"
                       (fn [] (.setAttribute web-link "href"
                                             (str server "/" (str/trim (.-value input))))))
    (.addEventListener input "keydown"
                       (fn [e] (when (= (.-key e) "Enter") (.preventDefault e) (save))))
    (.appendChild modal (node :h3 {:text "🔔 Buzz me when I'm away"}))
    (.appendChild modal
      (node :p {:text (str "Get a notification on your phone when you miss a message "
                           "here. It uses a free app called ntfy — no account, no email.")}))
    (.appendChild modal
      (node :ol {:class "notify-steps"}
        (node :li {}
          (node :span {:text "Install ntfy on your phone: "})
          (node :span {:class "notify-links"}
            (link "https://play.google.com/store/apps/details?id=io.heckel.ntfy" "Android")
            (link "https://apps.apple.com/app/ntfy/id1625396347" "iPhone")
            (link "https://f-droid.org/packages/io.heckel.ntfy/" "F-Droid")))
        (node :li {}
          (node :span {:text (str "Add a subscription on " server " with this topic:")})
          (node :div {:class "notify-row"} input
            (node :button {:class "ghost" :text "Copy" :on-click copy}))
          (node :div {:class "notify-aside"}
            (node :span {:text "No phone? You can "}) web-link
            (node :span {:text " instead."})))
        (node :li {:text "Hit Save. A test notification should pop up straight away."})))
    (.appendChild modal
      (node :p {:class "notify-warn"
                :text (str "Keep the topic secret: anyone who knows it can send you "
                           "notifications, the same way anyone with the link can chat here.")}))
    (.appendChild modal err)
    (.appendChild modal
      (node :div {:class "note-actions"}
        (node :button {:class "ghost" :text "Cancel" :on-click close})
        (when topic (node :button {:class "ghost" :text "Turn off" :on-click turn-off}))
        (node :button {:class "primary" :text "Save" :on-click save})))
    (.appendChild overlay modal)
    (.appendChild (.-body js/document) overlay)))

(defn- load-notify-config [room]
  (-> (fetch-json (str "/api/notify?room=" (enc room)) {})
      (.then (fn [r]
               (swap! state assoc :notify r)
               (refresh-notify-btn!)))))

;; ---------------------------------------------------------------------------
;; Minecraft relay: echo this room's chat into a game server, so whoever is
;; already playing sees it in-game. The servers on offer are set up by whoever
;; runs kwickchat (they need an RCON password, which is not a kid's business);
;; the room just picks one of them, or none.
;; ---------------------------------------------------------------------------

(defn- server-label
  "The friendly name of a server, falling back to its bare name."
  [nm]
  (or (some :label (filter #(= nm (:name %)) (:servers (:minecraft @state)))) nm))

(defn- refresh-minecraft-btn! []
  (when-let [b (by-id "minecraft-btn")]
    (let [{:keys [available server]} (:minecraft @state)]
      (set! (.. b -style -display) (if available "inline-flex" "none"))
      (if server
        (.remove (.-classList b) "off")
        (.add (.-classList b) "off"))
      (.setAttribute b "title" (if server
                                 (str "Chat here also shows up in " (server-label server))
                                 "Also show this chat in Minecraft")))))

(defn- show-minecraft-modal [room]
  (let [{:keys [servers server locked]} (:minecraft @state)
        overlay (node :div {:class "avatar-picker-overlay"})
        modal   (node :div {:class "note-modal minecraft-modal"})
        err     (node :div {:class "error"})
        chosen  (atom server)
        close   (fn [] (.remove overlay))
        options (node :div {:class "mc-options"})
        save    (fn []
                  (-> (post-json "/api/minecraft" {:room room :server (or @chosen "")})
                      (.then (fn [r]
                               (if (:ok r)
                                 (do (swap! state assoc-in [:minecraft :server] (:server r))
                                     (refresh-minecraft-btn!)
                                     (close))
                                 (set! (.-textContent err)
                                       (or (:error r) "Could not save.")))))))
        ;; One radio row per choice, including "off".
        option  (fn [value label]
                  (let [input (node :input {:class "mc-radio"})
                        row   (node :label {:class "mc-option"})]
                    (.setAttribute input "type" "radio")
                    (.setAttribute input "name" "mc-server")
                    (set! (.-checked input) (= value @chosen))
                    (set! (.-disabled input) (boolean locked))
                    (.addEventListener input "change"
                                       (fn [] (when (.-checked input) (reset! chosen value))))
                    (.appendChild row input)
                    (.appendChild row (node :span {:text label}))
                    row))]
    (.appendChild modal (node :h3 {:text "⛏️ Show this chat in Minecraft"}))
    (.appendChild modal
      (node :p {:text (str "Anything said here can pop up in a Minecraft server's chat, "
                           "so friends who are already playing don't miss it.")}))
    (doseq [{:keys [name label]} servers]
      (.appendChild options (option name label)))
    (.appendChild options (option nil "Don't send anywhere"))
    (.appendChild modal options)
    (when locked
      (.appendChild modal
        (node :p {:class "notify-warn"
                  :text "Whoever runs this site has fixed which server this room uses."})))
    (.appendChild modal err)
    (.appendChild modal
      (node :div {:class "note-actions"}
        (node :button {:class "ghost" :text "Cancel" :on-click close})
        (when-not locked
          (node :button {:class "primary" :text "Save" :on-click save}))))
    (.appendChild overlay modal)
    (.appendChild (.-body js/document) overlay)))

(defn- load-minecraft-config [room]
  (-> (fetch-json (str "/api/minecraft?room=" (enc room)) {})
      (.then (fn [r]
               (swap! state assoc :minecraft r)
               (refresh-minecraft-btn!)))))

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
  (let [selected-avatar (atom nil)
        input  (node :input {:class "name-input" :placeholder "Pick a username"})
        err    (node :div {:class "error"})
        avatar-btn (node :div {:class "avatar-selector-btn" :id "avatar-btn"})
        update-avatar-btn (fn []
                            (clear! avatar-btn)
                            (.appendChild avatar-btn 
                              (if @selected-avatar
                                (let [img (.createElement js/document "img")]
                                  (set! (.-src img) (str "/avatars/" @selected-avatar))
                                  (set! (.-width img) 48)
                                  (set! (.-height img) 48)
                                  img)
                                (let [placeholder (.createElement js/document "div")]
                                  (set! (.-className placeholder) "avatar-placeholder")
                                  (set! (.-textContent placeholder) "?")
                                  placeholder)))
                            (.appendChild avatar-btn (node :div {:class "avatar-hint" :text "Click to choose"})))
        submit (fn []
                 (let [name (str/trim (.-value input))]
                   (when (seq name)
                     (swap! state assoc :username name)
                     (-> (post-json "/api/join" {:room room :username name :avatar @selected-avatar})
                         (.then (fn [r]
                                  (if (:ok r)
                                    (do
                                      (swap! state assoc :avatar @selected-avatar)
                                      (enter-chat room (:username r)))
                                    (set! (.-textContent err)
                                          (or (:error r) "Could not join.")))))))))]
    (.addEventListener avatar-btn "click" 
                       (fn [] (show-avatar-picker (fn [avatar] 
                                                     (reset! selected-avatar avatar)
                                                     (update-avatar-btn)))))
    (.addEventListener input "keydown"
                       (fn [e] (when (= (.-key e) "Enter") (submit))))
    (update-avatar-btn)
    (mount!
     (node :div {:class "join"}
       (node :h1 {:text "Join chat"})
       (node :p {:class "room-label" :text (str "Chat: " room)})
       avatar-btn
       input
       err
       (node :button {:class "primary" :text "Join" :on-click submit})))
    (.focus input)))

(defn enter-chat [room username]
  ;; Set the browser tab title to the room name
  (swap! state assoc :room room)
  (set! (.-title js/document) (str room " - kwickchat"))
  (let [history (node :div {:class "history" :id "history"
                            :on-scroll (fn [e] (on-scroll (.-target e)))})
        jump    (node :button {:class "jump hidden" :id "jump" :text "↓ Jump to newest"
                               :on-click (fn [] (scroll-bottom!))})
        input   (node :input {:class "msg-input" :id "msg" :placeholder "Type a message…"})
        send    #(do-send room input)
        send-bt (node :button {:class "send" :text "Send" :on-click send})
        palette (node :div {:class "palette"})
        avatar-display (node :span {:class "me-avatar" :id "me-avatar"})
        ;; Hidden until we hear whether this server offers away notifications.
        notify-btn (node :button {:class "notify-toggle" :id "notify-btn" :text "🔕"
                                  :on-click (fn [] (show-notify-modal room))})
        ;; Likewise hidden until we know the operator set up any game servers.
        ;; The target belongs to the room, so somebody else may have moved it
        ;; since we loaded: re-read it before showing the choices.
        minecraft-btn (node :button {:class "minecraft-toggle off" :id "minecraft-btn"
                                     :text "⛏️"
                                     :on-click (fn []
                                                 (-> (load-minecraft-config room)
                                                     (.then #(show-minecraft-modal room))))})
        update-my-avatar (fn []
                          (clear! avatar-display)
                          (.appendChild avatar-display (avatar-or-identicon username (:avatar @state))))
        change-avatar (fn []
                       (show-avatar-picker 
                         (fn [new-avatar]
                           (-> (post-json "/api/change-avatar" {:room room :avatar new-avatar})
                               (.then (fn [r]
                                        (when (:ok r)
                                          (swap! state assoc :avatar new-avatar)
                                          (update-my-avatar))))))))]
    (.addEventListener avatar-display "click" change-avatar)
    (.addEventListener input "keydown"
                       (fn [e] (when (= (.-key e) "Enter") (.preventDefault e) (send))))
    (doseq [em emojis]
      (.appendChild palette
        (node :button {:class "emoji" :text em
                       :on-click (fn []
                                   (set! (.-value input) (str (.-value input) em))
                                   (.focus input))})))
    (update-my-avatar)
    (mount!
     (node :div {:class "app-layout" :id "layout"}
       (node :div {:class "chat"}
         (node :div {:class "topbar"}
           (node :span {:class "room-name" :text room})
           (node :span {:class "me"}
             minecraft-btn
             notify-btn
             (node :button {:class "postits-toggle" :text "📌"
                            :on-click toggle-postits!})
             avatar-display
             (node :span {:class "me-name" :text (str "you: " username)})))
         (node :div {:class "history-wrap"} history jump)
         palette
         (node :div {:class "composer"} input send-bt))
       (postit-panel room)))
    (set! (.. notify-btn -style -display) "none")
    (set! (.. minecraft-btn -style -display) "none")
    (load-history room)
    (load-notes room)
    (load-notify-config room)
    (load-minecraft-config room)
    (open-stream room)
    ;; Presence + unread bookkeeping. Coming back to the tab clears the badge
    ;; and tells the server we're at the keyboard again; while we're here, a
    ;; heartbeat keeps us marked present (comfortably inside the server's
    ;; two-minute away threshold).
    (let [wake (fn [] (clear-unread!) (ping-active! room))]
      (.addEventListener js/document "visibilitychange"
                         (fn [] (when-not (.-hidden js/document) (wake))))
      (.addEventListener js/window "focus" wake)
      (js/setInterval (fn [] (ping-active! room)) 45000)
      (ping-active! room))
    (.focus input)))

(defn- init-chat [room]
  (-> (fetch-json (str "/api/me?room=" (enc room)) {})
      (.then (fn [r]
               (if-let [u (:username r)]
                 (do
                   (swap! state assoc :room room :username u :avatar (:avatar r) :last-id 0 :stick true)
                   (enter-chat room u))
                 (render-username room))))))

(defn ^:export main []
  (let [room (-> (.-pathname js/location)
                 (subs 1)
                 (str/replace #"/+$" ""))]
    (if (str/blank? room)
      (render-landing)
      (init-chat room))))

(main)
