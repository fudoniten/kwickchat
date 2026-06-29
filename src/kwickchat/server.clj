(ns kwickchat.server
  "A very small chat server.

  Every URL subpath is its own chat room. Visiting a room serves a
  ClojureScript single-page app; the SPA talks to a handful of JSON
  endpoints under /api and receives new messages over Server-Sent Events.

  Identity is a cookie: a random token reserves a username within a room.
  Clear your cookies and you simply pick a name again."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [kwickchat.db :as db])
  (:import [com.sun.net.httpserver HttpServer HttpHandler HttpExchange]
           [java.net InetSocketAddress URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.util UUID]
           [java.util.concurrent Executors]
           [java.util.jar JarFile])
  (:gen-class))

;; ---------------------------------------------------------------------------
;; SSE listener registry:  room -> set of sinks.  A sink is the open response
;; stream for one connected browser.
;; ---------------------------------------------------------------------------

(defonce ^:private listeners (atom {}))

(defn- register! [room sink]
  (swap! listeners update room (fnil conj #{}) sink))

(defn- unregister! [room sink]
  (swap! listeners update room disj sink))

(defn- sse-write
  "Write a raw SSE chunk to a sink. Returns true on success; on any I/O
  failure the sink is marked dead and false is returned."
  [sink ^String s]
  (locking (:lock sink)
    (if @(:open sink)
      (try
        (let [^java.io.OutputStream os (:os sink)]
          (.write os (.getBytes s StandardCharsets/UTF_8))
          (.flush os))
        true
        (catch java.io.IOException _
          (reset! (:open sink) false)
          false))
      false)))

(defn- broadcast!
  "Push an SSE payload to every browser connected to `room`. With no `event`
  the browser sees a default `message` event (chat messages); naming an event
  (e.g. \"note\") lets the client route post-it updates separately."
  ([room msg] (broadcast! room nil msg))
  ([room event msg]
   (let [payload (str (when event (str "event: " event "\n"))
                      "data: " (json/write-str msg) "\n\n")]
     (doseq [sink (get @listeners room)]
       (when-not (sse-write sink payload)
         (unregister! room sink))))))

;; ---------------------------------------------------------------------------
;; HTTP helpers
;; ---------------------------------------------------------------------------

(defn- query-params [^HttpExchange ex]
  (let [raw (.getRawQuery (.getRequestURI ex))]
    (if (str/blank? raw)
      {}
      (into {}
            (for [pair (str/split raw #"&")]
              (let [[k v] (str/split pair #"=" 2)]
                [(URLDecoder/decode k "UTF-8")
                 (URLDecoder/decode (or v "") "UTF-8")]))))))

(defn- cookies [^HttpExchange ex]
  (let [header (.getFirst (.getRequestHeaders ex) "Cookie")]
    (if (str/blank? header)
      {}
      (into {}
            (for [c (str/split header #";")]
              (let [[k v] (str/split (str/trim c) #"=" 2)]
                [k (or v "")]))))))

(def ^:private cookie-name "kwick")

(defn- session-token
  "Return [token new?] for this request, minting a fresh token when the
  browser doesn't have one yet."
  [^HttpExchange ex]
  (if-let [tok (get (cookies ex) cookie-name)]
    [tok false]
    [(str (UUID/randomUUID)) true]))

(defn- send-bytes [^HttpExchange ex status ^bytes body content-type extra-headers]
  (let [h (.getResponseHeaders ex)]
    (.set h "Content-Type" content-type)
    (doseq [[k v] extra-headers] (.add h k v))
    (.sendResponseHeaders ex status (alength body))
    (with-open [os (.getResponseBody ex)]
      (.write os body))))

(defn- set-cookie-header [token]
  ;; A year-long, JS-invisible cookie. SameSite=Lax keeps it sane; no Secure
  ;; flag so it also works over plain http on a home LAN.
  (str cookie-name "=" token
       "; Path=/; Max-Age=31536000; HttpOnly; SameSite=Lax"))

(defn- json-response
  ([ex status data] (json-response ex status data nil))
  ([ex status data new-token]
   (send-bytes ex status
               (.getBytes (json/write-str data) StandardCharsets/UTF_8)
               "application/json; charset=utf-8"
               (when new-token {"Set-Cookie" (set-cookie-header new-token)}))))

(defn- request-body [^HttpExchange ex]
  (let [s (slurp (.getRequestBody ex) :encoding "UTF-8")]
    (if (str/blank? s) {} (json/read-str s :key-fn keyword))))

;; ---------------------------------------------------------------------------
;; Validation
;; ---------------------------------------------------------------------------

(def ^:private room-re #"^[A-Za-z0-9._/-]{1,200}$")
(def ^:private name-re #"^[\p{L}\p{N} _-]{1,24}$")
(def ^:private max-body 2000)
(def ^:private max-note 128)

(defn- valid-room? [room] (and room (re-matches room-re room)))

(defn- clean-name [name]
  (let [n (some-> name str/trim)]
    (when (and n (re-matches name-re n)) n)))

(defn- clean-body [body]
  (let [b (some-> body str/trim)]
    (when (and b (seq b) (<= (count b) max-body)) b)))

(defn- clean-note [body]
  (let [b (some-> body str/trim)]
    (when (and b (seq b) (<= (count b) max-note)) b)))

;; ---------------------------------------------------------------------------
;; Moderation log: every user-generated message and note is echoed to stdout so
;; it lands in the systemd journal (journalctl -u kwickchat). These are kids;
;; a grown-up should be able to skim what's being said.
;; ---------------------------------------------------------------------------

(defn- log-content! [kind room username body]
  (println (str "[" kind "] room=" room " user=" username " :: " body)))

;; ---------------------------------------------------------------------------
;; API handlers
;; ---------------------------------------------------------------------------

(defn- handle-me [^HttpExchange ex]
  (let [room (get (query-params ex) "room")
        [tok new?] (session-token ex)
        user-info (when (valid-room? room) (db/username-for room tok))]
    (if (valid-room? room)
      (json-response ex 200 (or user-info {}) (when new? tok))
      (json-response ex 400 {:error "bad room"} (when new? tok)))))

(defn- handle-join [^HttpExchange ex]
  (let [body (request-body ex)
        room (:room body)
        [tok new?] (session-token ex)
        name (clean-name (:username body))
        avatar (:avatar body)]
    (cond
      (not (valid-room? room))
      (json-response ex 400 {:ok false :error "Bad room."} (when new? tok))

      (nil? name)
      (json-response ex 400 {:ok false :error "Use 1–24 letters, numbers, spaces, - or _."} (when new? tok))

      :else
      (let [{:keys [status username]} (db/claim-username! room tok name avatar)]
        (case status
          (:ok :already-claimed) (json-response ex 200 {:ok true :username username} (when new? tok))
          :taken (json-response ex 409 {:ok false :error "That name is taken here — pick another."} (when new? tok)))))))

(defn- handle-messages [^HttpExchange ex]
  (let [q (query-params ex)
        room (get q "room")
        since (or (parse-long (str (get q "since" "0"))) 0)]
    (if (valid-room? room)
      (json-response ex 200 {:messages (db/messages-since room since 500)})
      (json-response ex 400 {:error "bad room"}))))

(defn- handle-send [^HttpExchange ex]
  (let [body (request-body ex)
        room (:room body)
        [tok new?] (session-token ex)
        text (clean-body (:body body))]
    (cond
      (not (valid-room? room))
      (json-response ex 400 {:ok false :error "Bad room."} (when new? tok))

      :else
      (let [user-info (db/username-for room tok)]
        (cond
          (nil? user-info)
          (json-response ex 403 {:ok false :error "Pick a username first."} (when new? tok))

          (nil? text)
          (json-response ex 400 {:ok false :error "Empty or too-long message."} (when new? tok))

          :else
          (let [msg (db/add-message! room (:username user-info) text (:avatar user-info))]
            (log-content! "chat" room (:username user-info) text)
            (broadcast! room msg)
            (json-response ex 200 {:ok true :message msg} (when new? tok))))))))

(defn- handle-notes [^HttpExchange ex]
  (let [room (get (query-params ex) "room")]
    (if (valid-room? room)
      (json-response ex 200 {:notes (db/notes-for room)})
      (json-response ex 400 {:error "bad room"}))))

(defn- handle-set-note [^HttpExchange ex]
  (let [body (request-body ex)
        room (:room body)
        [tok new?] (session-token ex)
        text (clean-note (:body body))]
    (cond
      (not (valid-room? room))
      (json-response ex 400 {:ok false :error "Bad room."} (when new? tok))

      (nil? text)
      (json-response ex 400 {:ok false :error "Notes are 1–128 characters."} (when new? tok))

      :else
      (if-let [note (db/set-note! room tok text)]
        (do
          (log-content! "note" room (:username note) text)
          (broadcast! room "note" note)
          (json-response ex 200 {:ok true :note note} (when new? tok)))
        (json-response ex 403 {:ok false :error "Pick a username first."} (when new? tok))))))

(defn- handle-stream
  "Open a long-lived Server-Sent Events connection for a room."
  [^HttpExchange ex]
  (let [room (get (query-params ex) "room")]
    (if-not (valid-room? room)
      (send-bytes ex 400 (.getBytes "bad room") "text/plain" nil)
      (let [h (.getResponseHeaders ex)]
        (.set h "Content-Type" "text/event-stream; charset=utf-8")
        (.set h "Cache-Control" "no-cache, no-transform")
        (.set h "Connection" "keep-alive")
        (.set h "X-Accel-Buffering" "no")            ; don't let nginx buffer SSE
        (.sendResponseHeaders ex 200 0)              ; 0 => chunked, stays open
        (let [sink {:os (.getResponseBody ex) :lock (Object.) :open (atom true)}]
          (register! room sink)
          (sse-write sink ": connected\n\n")
          (try
            ;; Park this thread, sending a heartbeat so we notice when the
            ;; browser goes away (the write fails and we stop).
            (loop []
              (Thread/sleep 25000)
              (when (and @(:open sink) (sse-write sink ": ping\n\n"))
                (recur)))
            (finally
              (unregister! room sink)
              (try (.close (:os sink)) (catch Exception _))
              (.close ex))))))))

;; ---------------------------------------------------------------------------
;; Static files + SPA
;; ---------------------------------------------------------------------------

(defn- serve-resource [^HttpExchange ex resource content-type]
  (if-let [url (io/resource resource)]
    (let [body (with-open [in (.openStream url)] (.readAllBytes in))]
      (send-bytes ex 200 body content-type nil))
    (send-bytes ex 404 (.getBytes "not found") "text/plain" nil)))

(defn- serve-index [^HttpExchange ex]
  ;; Any room path renders the same SPA; a fresh visitor also gets a cookie.
  (let [[tok new?] (session-token ex)
        body (if-let [url (io/resource "public/index.html")]
               (with-open [in (.openStream url)] (.readAllBytes in))
               (.getBytes "missing index.html"))]
    (send-bytes ex 200 body "text/html; charset=utf-8"
                (when new? {"Set-Cookie" (set-cookie-header tok)}))))

(defn- handle-avatars
  "List available avatar filenames from resources/public/avatars/"
  [^HttpExchange ex]
  (try
    (if-let [url (io/resource "public/avatars")]
      (let [protocol (.getProtocol url)
            files (cond
                    ;; Running from filesystem (development)
                    (= protocol "file")
                    (let [dir (io/file (.toURI url))]
                      (->> (.listFiles dir)
                           (filter #(.isFile %))
                           (map #(.getName %))
                           (filter #(str/ends-with? (str/lower-case %) ".png"))
                           (sort)
                           vec))
                    
                    ;; Running from JAR (production)
                    (= protocol "jar")
                    (let [path (.getPath url)
                          jar-path (subs path 5 (str/index-of path "!"))
                          jar (JarFile. jar-path)]
                      (->> (enumeration-seq (.entries jar))
                           (map #(.getName %))
                           (filter #(str/starts-with? % "public/avatars/"))
                           (filter #(str/ends-with? (str/lower-case %) ".png"))
                           (map #(subs % (count "public/avatars/")))
                           (filter seq)
                           (sort)
                           vec))
                    
                    :else [])]
        (json-response ex 200 {:avatars files}))
      (json-response ex 200 {:avatars []}))
    (catch Exception e
      (.printStackTrace e)
      (json-response ex 200 {:avatars []}))))

(defn- handle-change-avatar [^HttpExchange ex]
  (let [body (request-body ex)
        room (:room body)
        avatar (:avatar body)
        [tok new?] (session-token ex)]
    (cond
      (not (valid-room? room))
      (json-response ex 400 {:ok false :error "Bad room."} (when new? tok))

      :else
      (if-let [result (db/change-avatar! room tok avatar)]
        (json-response ex 200 {:ok true :avatar (:avatar result)} (when new? tok))
        (json-response ex 403 {:ok false :error "Not a member of this room."} (when new? tok))))))

;; ---------------------------------------------------------------------------
;; Routing
;; ---------------------------------------------------------------------------

(defn- route [^HttpExchange ex]
  (let [method (.getRequestMethod ex)
        path   (.getPath (.getRequestURI ex))]
    (cond
      ;; Static files
      (= path "/style.css")   (serve-resource ex "public/style.css" "text/css; charset=utf-8")
      (= path "/js/main.js")  (serve-resource ex "public/js/main.js" "application/javascript; charset=utf-8")
      (= path "/wood.png")    (serve-resource ex "public/wood.png" "image/png")
      (= path "/dirt.png")    (serve-resource ex "public/dirt.png" "image/png")
      (= path "/favicon.ico") (serve-resource ex "public/favicon.ico" "image/x-icon")
      
      ;; Avatar images
      (str/starts-with? path "/avatars/")
      (let [filename (subs path 9)]
        (serve-resource ex (str "public/avatars/" filename) "image/png"))
      
      ;; API endpoints
      (= path "/api/me")            (handle-me ex)
      (= path "/api/join")          (handle-join ex)
      (= path "/api/messages")      (handle-messages ex)
      (= path "/api/send")          (handle-send ex)
      (= path "/api/notes")         (handle-notes ex)
      (= path "/api/note")          (handle-set-note ex)
      (= path "/api/stream")        (handle-stream ex)
      (= path "/api/avatars")       (handle-avatars ex)
      (= path "/api/change-avatar") (handle-change-avatar ex)
      
      ;; Everything else is a chat room -> serve the SPA (GET only).
      (= method "GET") (serve-index ex)
      :else (send-bytes ex 404 (.getBytes "not found") "text/plain" nil))))

(defn- handler []
  (reify HttpHandler
    (handle [_ ex]
      (try
        (route ex)
        (catch Throwable t
          (binding [*out* *err*] (println "request error:" (.getMessage t)))
          (try (send-bytes ex 500 (.getBytes "internal error") "text/plain" nil)
               (catch Exception _)))))))

(defn -main [& args]
  (let [;; Parse command-line args: --port <num>, --host <addr>, --db <path>
        arg-map (loop [remaining args, acc {}]
                  (if (empty? remaining)
                    acc
                    (let [[k v & rest] remaining]
                      (case k
                        "--port" (recur rest (assoc acc :port v))
                        "--host" (recur rest (assoc acc :host v))
                        "--db"   (recur rest (assoc acc :db v))
                        (recur (next remaining) acc)))))
        port (Integer/parseInt (or (:port arg-map)
                                    (System/getenv "KWICKCHAT_PORT")
                                    "5660"))
        host (or (:host arg-map)
                 (System/getenv "KWICKCHAT_HOST")
                 "0.0.0.0")
        db-path (or (:db arg-map)
                    (System/getenv "KWICKCHAT_DB")
                    "kwickchat.db")
        server (HttpServer/create (InetSocketAddress. host (int port)) 0)]
    (db/init! db-path)
    (.createContext server "/" (handler))
    (.setExecutor server (Executors/newCachedThreadPool))
    (.start server)
    (println (str "kwickchat listening on http://" host ":" port "  (db: " db-path ")"))
    @(promise)))
