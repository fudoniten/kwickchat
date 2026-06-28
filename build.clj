(ns build
  "Build tasks:
     clojure -T:build cljs    ;; compile the ClojureScript bundle
     clojure -T:build uber    ;; cljs + a runnable uberjar at target/kwickchat.jar
     clojure -T:build clean"
  (:require [clojure.tools.build.api :as b]
            [cljs.build.api :as cljs]))

(def class-dir "target/classes")
(def uber-file "target/kwickchat.jar")
(def basis (delay (b/create-basis {:project "deps.edn"})))

(defn clean [_]
  (b/delete {:path "target"})
  (b/delete {:path "resources/public/js"}))

(defn cljs [_]
  (println "Compiling ClojureScript -> resources/public/js/main.js")
  (cljs/build "src"
              {:output-to     "resources/public/js/main.js"
               :output-dir    "target/cljs"
               :main          'kwickchat.client
               :optimizations :advanced
               :pretty-print  false})
  (println "ClojureScript done."))

(defn uber [_]
  (clean nil)
  (cljs nil)
  (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
  (b/compile-clj {:basis      @basis
                  :ns-compile '[kwickchat.server]
                  :class-dir  class-dir})
  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :basis     @basis
           :main      'kwickchat.server})
  (println "Uberjar:" uber-file))
