(ns kwickchat.db
  "Tiny SQLite persistence layer using raw JDBC (no extra dependencies).

  A single shared connection is guarded by a monitor lock. For a handful of
  kids chatting this is more than fast enough, and it sidesteps SQLite's
  multi-writer locking quirks entirely."
  (:import [java.sql DriverManager]))

(defonce ^:private conn (atom nil))
(def ^:private lock (Object.))

(def ^:private schema
  ["CREATE TABLE IF NOT EXISTS messages (
      id         INTEGER PRIMARY KEY AUTOINCREMENT,
      room       TEXT    NOT NULL,
      username   TEXT    NOT NULL,
      body       TEXT    NOT NULL,
      avatar     TEXT,
      created_at INTEGER NOT NULL,
      kind       TEXT    NOT NULL DEFAULT 'chat')"
   "CREATE INDEX IF NOT EXISTS idx_messages_room_id ON messages (room, id)"
   "CREATE TABLE IF NOT EXISTS members (
      room       TEXT    NOT NULL,
      cookie     TEXT    NOT NULL,
      username   TEXT    NOT NULL,
      avatar     TEXT,
      created_at INTEGER NOT NULL,
      PRIMARY KEY (room, cookie))"
   ;; A username can be reserved by exactly one cookie within a room.
   "CREATE UNIQUE INDEX IF NOT EXISTS idx_members_room_username ON members (room, username)"
   ;; One pinned "post-it" note per member (room + cookie), overwritten in place.
   "CREATE TABLE IF NOT EXISTS notes (
      room       TEXT    NOT NULL,
      cookie     TEXT    NOT NULL,
      username   TEXT    NOT NULL,
      body       TEXT    NOT NULL,
      updated_at INTEGER NOT NULL,
      PRIMARY KEY (room, cookie))"
   "CREATE INDEX IF NOT EXISTS idx_notes_room ON notes (room, updated_at)"])

(defn init!
  "Open (or create) the database at `path` and ensure the schema exists."
  [path]
  (Class/forName "org.sqlite.JDBC")
  (let [c (DriverManager/getConnection (str "jdbc:sqlite:" path))]
    (.setAutoCommit c true)
    (with-open [st (.createStatement c)]
      (.execute st "PRAGMA journal_mode=WAL")
      (.execute st "PRAGMA busy_timeout=5000")
      (doseq [ddl schema] (.execute st ddl))
      ;; Migration: Add avatar columns if they don't exist
      (try
        (.execute st "ALTER TABLE messages ADD COLUMN avatar TEXT")
        (catch Exception _))
      (try
        (.execute st "ALTER TABLE members ADD COLUMN avatar TEXT")
        (catch Exception _))
      ;; Migration: tag existing rows as chat messages; join notices use 'join'.
      (try
        (.execute st "ALTER TABLE messages ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'")
        (catch Exception _)))
    (reset! conn c)
    c))

(defn- row->message [rs]
  {:id         (.getLong rs "id")
   :username   (.getString rs "username")
   :body       (.getString rs "body")
   :avatar     (.getString rs "avatar")
   :created_at (.getLong rs "created_at")
   :kind       (.getString rs "kind")})

(defn- insert-message!
  "Persist a message of `kind` ('chat' or 'join') and return it with its id."
  [room username body avatar kind]
  (locking lock
    (let [now (System/currentTimeMillis)]
      (with-open [ps (.prepareStatement
                      @conn "INSERT INTO messages (room, username, body, avatar, created_at, kind) VALUES (?,?,?,?,?,?)")]
        (.setString ps 1 room)
        (.setString ps 2 username)
        (.setString ps 3 body)
        (.setString ps 4 avatar)
        (.setLong   ps 5 now)
        (.setString ps 6 kind)
        (.executeUpdate ps))
      (let [id (with-open [st (.createStatement @conn)
                           rs (.executeQuery st "SELECT last_insert_rowid()")]
                 (.next rs)
                 (.getLong rs 1))]
        {:id id :room room :username username :body body :avatar avatar
         :created_at now :kind kind}))))

(defn add-message!
  "Persist a chat message and return it (with its generated id)."
  [room username body avatar]
  (insert-message! room username body avatar "chat"))

(defn add-join-message!
  "Persist a 'joined' system notice for `username` and return it. The body is a
  plain fallback; clients compose the full '<name> has joined' line themselves."
  [room username avatar]
  (insert-message! room username "joined" avatar "join"))

(defn messages-since
  "Return up to `limit` messages in `room` with id greater than `since`,
  oldest first. Pass since=0 to load history from the start."
  [room since limit]
  (locking lock
    (with-open [ps (.prepareStatement
                    @conn "SELECT id, username, body, avatar, created_at, kind FROM messages
                           WHERE room = ? AND id > ? ORDER BY id ASC LIMIT ?")]
      (.setString ps 1 room)
      (.setLong   ps 2 since)
      (.setInt    ps 3 limit)
      (with-open [rs (.executeQuery ps)]
        (loop [acc []]
          (if (.next rs)
            (recur (conj acc (row->message rs)))
            acc))))))

(defn username-for
  "The username this `cookie` has reserved in `room`, or nil."
  [room cookie]
  (locking lock
    (with-open [ps (.prepareStatement
                    @conn "SELECT username, avatar FROM members WHERE room = ? AND cookie = ?")]
      (.setString ps 1 room)
      (.setString ps 2 cookie)
      (with-open [rs (.executeQuery ps)]
        (when (.next rs)
          {:username (.getString rs 1)
           :avatar   (.getString rs 2)})))))

(defn- username-taken? [room username]
  (with-open [ps (.prepareStatement
                  @conn "SELECT 1 FROM members WHERE room = ? AND username = ?")]
    (.setString ps 1 room)
    (.setString ps 2 username)
    (with-open [rs (.executeQuery ps)]
      (.next rs))))

(defn claim-username!
  "Try to reserve `username` in `room` for `cookie`.

  Returns a map {:status ... :username ... :avatar ... :new? ...} where status
  is one of:
    :ok             - reserved (or this cookie already owns this name)
    :already-claimed- this cookie already owns a *different* name here
    :taken          - the name belongs to someone else
  and :new? is true only when this call actually created a new member (a fresh
  join), so callers can announce it without re-announcing reconnects."
  [room cookie username avatar]
  (locking lock
    (if-let [existing (username-for room cookie)]
      {:status (if (= (:username existing) username) :ok :already-claimed)
       :username (:username existing)
       :avatar (:avatar existing)
       :new? false}
      (if (username-taken? room username)
        {:status :taken :username nil :avatar nil :new? false}
        (do
          (with-open [ps (.prepareStatement
                          @conn "INSERT INTO members (room, cookie, username, avatar, created_at) VALUES (?,?,?,?,?)")]
            (.setString ps 1 room)
            (.setString ps 2 cookie)
            (.setString ps 3 username)
            (.setString ps 4 avatar)
            (.setLong   ps 5 (System/currentTimeMillis))
            (.executeUpdate ps))
          {:status :ok :username username :avatar avatar :new? true})))))

(defn change-avatar!
  "Update the avatar for a member identified by `cookie` in `room`.
  Returns the new avatar on success, or nil if the member doesn't exist."
  [room cookie avatar]
  (locking lock
    (when (username-for room cookie)
      (with-open [ps (.prepareStatement
                      @conn "UPDATE members SET avatar = ? WHERE room = ? AND cookie = ?")]
        (.setString ps 1 avatar)
        (.setString ps 2 room)
        (.setString ps 3 cookie)
        (.executeUpdate ps))
      avatar)))

;; ---------------------------------------------------------------------------
;; Post-it notes: one per member, joined to members so the avatar stays current.
;; ---------------------------------------------------------------------------

(defn- row->note [rs]
  {:username   (.getString rs "username")
   :avatar     (.getString rs "avatar")
   :body       (.getString rs "body")
   :updated_at (.getLong rs "updated_at")})

(defn notes-for
  "All post-it notes in `room`, most recently updated first. Each note's
  avatar is read live from the members table so it tracks avatar changes."
  [room]
  (locking lock
    (with-open [ps (.prepareStatement
                    @conn "SELECT n.username AS username, m.avatar AS avatar,
                                  n.body AS body, n.updated_at AS updated_at
                           FROM notes n
                           LEFT JOIN members m
                             ON m.room = n.room AND m.cookie = n.cookie
                           WHERE n.room = ?
                           ORDER BY n.updated_at DESC")]
      (.setString ps 1 room)
      (with-open [rs (.executeQuery ps)]
        (loop [acc []]
          (if (.next rs)
            (recur (conj acc (row->note rs)))
            acc))))))

(defn set-note!
  "Create or overwrite the note belonging to `cookie` in `room`. Only members
  may post; returns the note map on success, or nil if not a member."
  [room cookie body]
  (locking lock
    (when-let [{:keys [username avatar]} (username-for room cookie)]
      (let [now (System/currentTimeMillis)]
        (with-open [ps (.prepareStatement
                        @conn "INSERT INTO notes (room, cookie, username, body, updated_at)
                               VALUES (?,?,?,?,?)
                               ON CONFLICT(room, cookie) DO UPDATE SET
                                 username   = excluded.username,
                                 body       = excluded.body,
                                 updated_at = excluded.updated_at")]
          (.setString ps 1 room)
          (.setString ps 2 cookie)
          (.setString ps 3 username)
          (.setString ps 4 body)
          (.setLong   ps 5 now)
          (.executeUpdate ps))
        {:username username :avatar avatar :body body :updated_at now}))))
