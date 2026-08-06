(ns kwickchat.notify
  "Push notifications for people who aren't looking at the page, via ntfy
  (https://ntfy.sh).

  ntfy fits kwickchat's no-accounts rule exactly: a member invents a topic
  name, subscribes to it in the ntfy app on their phone, and anything POSTed
  to <server>/<topic> pops up as a notification. The topic name is the only
  secret — same deal as the room link.

  The ntfy *server* is picked by whoever runs kwickchat, never by a user:
  otherwise this would happily POST to any host a kid typed into a box.
  Members only choose the topic."
  (:require [clojure.string :as str])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers
            HttpResponse HttpResponse$BodyHandlers]
           [java.nio.charset StandardCharsets]
           [java.time Duration]
           [java.util.concurrent ExecutorService Executors]))

(defonce ^:private config (atom {:server nil :base-url nil}))

;; Deliveries happen off the request thread: a slow or unreachable ntfy must
;; never hold up the kid who just hit send.
(defonce ^:private sender
  (delay (Executors/newFixedThreadPool 2)))

(defonce ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 5))
             (.build))))

(def ^:private topic-re #"^[A-Za-z0-9_-]{6,64}$")

(defn valid-topic?
  "Topics are ntfy's own character set, with a length floor: on a public server
  anyone who guesses your topic can notify you, so single words are a bad idea."
  [topic]
  (boolean (and topic (re-matches topic-re topic))))

(defn- trim-slashes [s] (str/replace s #"/+$" ""))

(defn configure!
  "Set the ntfy server every notification goes to, and the public base URL of
  this kwickchat (used to make notifications tappable). A blank server turns
  the whole feature off."
  [{:keys [server base-url]}]
  (reset! config
          {:server   (when-not (str/blank? server) (trim-slashes (str/trim server)))
           :base-url (when-not (str/blank? base-url) (trim-slashes (str/trim base-url)))}))

(defn server [] (:server @config))
(defn enabled? [] (some? (server)))

(defn- room-url
  "Link straight back to the room, when the operator told us our public URL.
  Room names are already restricted to URL-safe characters."
  [room]
  (when-let [base (:base-url @config)]
    (str base "/" room)))

(defn- truncate [s n]
  (if (> (count s) n) (str (subs s 0 (dec n)) "…") s))

(defn- deliver!
  "POST one notification. Runs on the sender pool; never throws."
  [topic title body click]
  (try
    (let [b (cond-> (-> (HttpRequest/newBuilder (URI/create (str (server) "/" topic)))
                        (.timeout (Duration/ofSeconds 10))
                        ;; Header values must stay ASCII, so only the room name
                        ;; (already restricted) goes up top — usernames and
                        ;; message text ride in the UTF-8 body.
                        (.header "Title" title)
                        (.header "Tags" "speech_balloon")
                        (.header "Content-Type" "text/plain; charset=utf-8")
                        (.POST (HttpRequest$BodyPublishers/ofString body StandardCharsets/UTF_8)))
              click (.header "Click" click))
          ^HttpClient http @client
          ^HttpResponse resp (.send http (.build b) (HttpResponse$BodyHandlers/discarding))]
      (when-not (<= 200 (.statusCode resp) 299)
        (binding [*out* *err*]
          (println "ntfy: topic" topic "returned HTTP" (.statusCode resp)))))
    (catch Exception e
      (binding [*out* *err*]
        (println "ntfy: could not notify topic" topic "-" (.getMessage e))))))

(defn notify!
  "Tell `topic` that `username` said `text` in `room`. Returns immediately."
  [topic room username text]
  (when (enabled?)
    (let [^ExecutorService pool @sender
          ^Runnable job #(deliver! topic
                                   (str "kwickchat: " room)
                                   (truncate (str username ": " text) 160)
                                   (room-url room))]
      (.submit pool job))))

(defn confirm!
  "Send the one-off 'this works' notification when someone first sets a topic —
  the only way to know the phone half of the setup actually landed."
  [topic room]
  (when (enabled?)
    (let [^ExecutorService pool @sender
          ^Runnable job #(deliver! topic
                                   (str "kwickchat: " room)
                                   (str "Notifications are on. You'll get a buzz "
                                        "here when you miss a message in " room ".")
                                   (room-url room))]
      (.submit pool job))))
