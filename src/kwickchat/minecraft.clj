(ns kwickchat.minecraft
  "Echo room chat into a Minecraft server, so the people already in the game
  see what's being said in the chat room.

  Delivery is Minecraft's own RCON protocol: kwickchat opens a short-lived TCP
  connection to the server's `rcon.port`, authenticates with `rcon.password`,
  and runs one `tellraw @a …`. Nothing has to be installed on the game server
  beyond `enable-rcon=true` in server.properties, and it works on vanilla.

  Which server a room talks to is per-room, but the *set* of servers is picked
  by whoever runs kwickchat, in a JSON file — exactly like the ntfy server in
  `kwickchat.notify`, and for the same two reasons: an RCON password is a
  secret that must never travel through a browser, and a host typed into a box
  by a kid would turn kwickchat into a port scanner. Rooms choose a target by
  *name* from that file:

      {\"servers\": {\"survival\": {\"host\": \"10.0.0.5\",
                                    \"port\": 25575,
                                    \"password\": \"…\",
                                    \"label\": \"Survival world\"}},
       \"rooms\":   {\"minecraft-crew\": \"survival\"},
       \"default\":  null,
       \"locked\":   false}

  `rooms` and `default` are starting points; a room can be pointed somewhere
  else (or switched off) from its own UI, unless `locked` is true, in which
  case the file is the last word."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.io DataInputStream DataOutputStream IOException]
           [java.net InetSocketAddress Socket]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.charset StandardCharsets]
           [java.util.concurrent ArrayBlockingQueue ExecutorService ThreadPoolExecutor
            ThreadPoolExecutor$DiscardPolicy TimeUnit]))

(defonce ^:private config (atom {:servers {} :rooms {} :default nil :locked false}))

;; Relays run off the request thread, and a server that has gone away must not
;; be able to pile up work: the queue is bounded and overflow is dropped. A kid
;; hitting send never waits for Minecraft, and never sees it fail.
(defonce ^:private sender
  (delay (ThreadPoolExecutor. 1 2 30 TimeUnit/SECONDS
                              (ArrayBlockingQueue. 100)
                              (ThreadPoolExecutor$DiscardPolicy.))))

;; ---------------------------------------------------------------------------
;; Configuration
;; ---------------------------------------------------------------------------

(def ^:private name-re #"^[A-Za-z0-9 ._-]{1,32}$")

(defn valid-server-name? [n] (boolean (and n (re-matches name-re n))))

(defn- clean-server
  "Validate one entry of the `servers` map, returning nil (with a complaint on
  stderr) if it can't be used."
  [nm {:keys [host port password label]}]
  (cond
    (not (valid-server-name? nm))
    (binding [*out* *err*]
      (println "minecraft: ignoring server" (pr-str nm)
               "- names are 1–32 letters, numbers, spaces, . _ or -"))

    (str/blank? host)
    (binding [*out* *err*] (println "minecraft: server" nm "has no host"))

    (str/blank? password)
    (binding [*out* *err*] (println "minecraft: server" nm "has no rcon password"))

    :else
    {:host     (str/trim host)
     :port     (or (some-> port str str/trim parse-long) 25575)
     :password password
     :label    (if (str/blank? label) nm (str/trim label))}))

(defn- parse-config [raw]
  (let [servers (into {}
                      (keep (fn [[k v]]
                              (let [nm (name k)]
                                (when-let [s (clean-server nm v)] [nm s]))))
                      (:servers raw))
        known?  #(contains? servers %)
        rooms   (into {}
                      (keep (fn [[k v]]
                              (let [target (some-> v name)]
                                (cond
                                  (nil? target) nil
                                  (known? target) [(name k) target]
                                  :else (binding [*out* *err*]
                                          (println "minecraft: room" (name k)
                                                   "points at unknown server"
                                                   (pr-str target)))))))
                      (:rooms raw))
        default (some-> (:default raw) name)]
    {:servers servers
     :rooms   rooms
     :default (when (known? default) default)
     :locked  (true? (:locked raw))}))

(defn configure!
  "Load the operator's Minecraft config from `path`. A blank/missing path (or an
  unreadable file) simply leaves the feature switched off — chat must keep
  working whatever state the game servers are in."
  [path]
  (reset! config {:servers {} :rooms {} :default nil :locked false})
  (when-not (str/blank? path)
    (let [f (io/file path)]
      (if-not (.isFile f)
        (binding [*out* *err*]
          (println "minecraft: no config file at" (str f) "- relay disabled"))
        (try
          (reset! config (parse-config (json/read-str (slurp f) :key-fn keyword)))
          (catch Exception e
            (binding [*out* *err*]
              (println "minecraft: could not read" (str f) "-" (.getMessage e))))))))
  @config)

(defn enabled?
  "True once at least one usable server is configured."
  []
  (seq (:servers @config)))

(defn locked?
  "When true, rooms can't change their own target — the config file decides."
  []
  (:locked @config))

(defn known-server? [nm] (contains? (:servers @config) nm))

(defn servers
  "The choosable servers, as {:name :label} maps sorted by label. Never
  includes hosts or passwords: this is what the browser is allowed to see."
  []
  (->> (:servers @config)
       (map (fn [[nm {:keys [label]}]] {:name nm :label label}))
       (sort-by (comp str/lower-case :label))
       vec))

(defn default-for
  "The server this room talks to when nobody has chosen one in the room itself."
  [room]
  (get-in @config [:rooms room] (:default @config)))

;; ---------------------------------------------------------------------------
;; RCON (https://wiki.vg/RCON): little-endian framing, a login packet, then one
;; command packet per request. We open a fresh connection per message — chat
;; here is a trickle, and a per-message connection means there is no session to
;; nurse back to life after the game server restarts.
;; ---------------------------------------------------------------------------

(def ^:private ^:const type-command 2)
(def ^:private ^:const type-auth 3)
(def ^:private ^:const max-packet 8192)

(defn- write-packet! [^DataOutputStream out id type ^String body]
  (let [payload (.getBytes body StandardCharsets/UTF_8)
        ;; length covers id + type + body + the two trailing nulls
        len     (+ 4 4 (alength payload) 2)
        buf     (doto (ByteBuffer/allocate (+ 4 len))
                  (.order ByteOrder/LITTLE_ENDIAN)
                  (.putInt len)
                  (.putInt id)
                  (.putInt type)
                  (.put payload)
                  (.put (byte 0))
                  (.put (byte 0)))]
    (.write out (.array buf))
    (.flush out)))

(defn- read-int-le [^DataInputStream in]
  (Integer/reverseBytes (.readInt in)))

(defn- read-packet [^DataInputStream in]
  (let [len (read-int-le in)]
    (when (or (< len 10) (> len max-packet))
      (throw (IOException. (str "bad rcon packet length " len))))
    (let [id   (read-int-le in)
          type (read-int-le in)
          body (byte-array (- len 10))]
      (.readFully in body)
      (.readFully in (byte-array 2))                 ; the two nulls
      {:id id :type type :body (String. body StandardCharsets/UTF_8)})))

(defn- run-command!
  "Authenticate and run one console command. Throws on failure."
  [{:keys [host port password]} command]
  (with-open [sock (Socket.)]
    (.connect sock (InetSocketAddress. ^String host (int port)) 5000)
    (.setSoTimeout sock 5000)
    (with-open [out (DataOutputStream. (.getOutputStream sock))
                in  (DataInputStream. (.getInputStream sock))]
      (write-packet! out 1 type-auth password)
      ;; Some servers emit an empty response packet ahead of the auth reply.
      (let [resp (let [p (read-packet in)]
                   (if (zero? (:type p)) (read-packet in) p))]
        (when (= -1 (:id resp))
          (throw (ex-info "rcon authentication failed" {:host host}))))
      (write-packet! out 2 type-command command)
      (:body (read-packet in)))))

;; ---------------------------------------------------------------------------
;; The message itself
;; ---------------------------------------------------------------------------

(def ^:private max-text 200)

(defn- clean-text
  "Chat text is user-typed, so flatten it into something a single console
  command can carry: no newlines, no control characters, and no § formatting
  codes (which would otherwise let anyone colour the whole server's chat)."
  [s]
  (-> (str s)
      (str/replace #"[\p{Cntrl}§]" " ")
      (str/replace #"\s+" " ")
      str/trim
      (as-> t (if (> (count t) max-text) (str (subs t 0 (dec max-text)) "…") t))))

(defn- tellraw
  "A `tellraw @a` command showing '[room] <name> message' in the game chat.
  The text rides inside JSON, so quotes and backslashes in a message can't
  escape into the command."
  [room username text]
  (str "tellraw @a "
       (json/write-str
        {:text ""
         :extra [{:text (str "[" room "] ") :color "gray"}
                 {:text (str "<" username "> ") :color "aqua"}
                 {:text text :color "white"}]})))

(defn- deliver!
  "Send one line to one server. Runs on the sender pool; never throws."
  [server-name room username text]
  (try
    (if-let [server (get-in @config [:servers server-name])]
      (run-command! server (tellraw room username text))
      (binding [*out* *err*]
        (println "minecraft: room" room "points at unknown server" server-name)))
    (catch Exception e
      (binding [*out* *err*]
        (println "minecraft: could not reach server" server-name "-"
                 (or (.getMessage e) (.toString e)))))))

(defn notify!
  "Show '<username> text' from `room` in `server-name`'s game chat. Returns
  immediately; delivery happens on the sender pool."
  [server-name room username text]
  (when (known-server? server-name)
    (let [body (clean-text text)]
      (when (seq body)
        (let [^ExecutorService pool @sender
              ^Runnable job #(deliver! server-name room username body)]
          (.submit pool job))))))

(defn announce!
  "Say something in `server-name`'s chat on kwickchat's own behalf — used for
  the 'this room now points here' confirmation when a room picks a server."
  [server-name room text]
  (when (known-server? server-name)
    (let [^ExecutorService pool @sender
          ^Runnable job #(deliver! server-name room "kwickchat" (clean-text text))]
      (.submit pool job))))
