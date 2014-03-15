(ns lt.plugins.gitlight.git
  (:require [lt.object :as object]
            [lt.objs.proc :as proc]
            [lt.objs.command :as cmd]
            [lt.objs.notifos :as notifos]
            [lt.objs.editor.pool :as pool]
            [lt.objs.files :as files]
            [lt.objs.popup :as popup]
            [lt.plugins.gitlight.execute :as exec]
            [lt.plugins.gitlight :refer [config error]]
            [clojure.string :as string])
    (:require-macros [lt.macros :refer [defui behavior]]))



(defn on-cwd? []
  (not (nil? (pool/last-active))))



(defn get-cwd []
  (files/parent
   (-> @(pool/last-active) :info :path)))



(defn get-git-root []
  (if (on-cwd?)
    (let [cwd (files/walk-up-find (get-cwd) ".git")]
      (if-not (nil? cwd)
        (files/parent cwd)))))


(defn git-command-cwd [obj cwd & args]
  (let [git-path (:git-binary @config)
        command (str git-path " " (string/join " " args))]
    (exec/run-deaf obj cwd command)))


(defn git-command [obj & args]
  (let [cwd (get-git-root)]
    (apply (partial git-command-cwd obj cwd) args)))



(defn git-command-ignore-out [& args]
  (apply (partial git-command git-ignore-out) args))



(defn git-add [filename]
  (git-command-ignore-out "add" "--" filename))



(defn git-reset [filename]
  (git-command-ignore-out "reset" "--" filename))


(defn git-checkout-file [filename]
  (git-command-ignore-out "checkout" "--" filename))


(behavior ::ignore.out-success
          :desc "Ignore git command output."
          :triggers #{:out}
          :reaction (fn [obj data err]
                      (notifos/set-msg! "git: success!")))


(behavior ::ignore.out-error
          :desc "Ignore git command output."
          :triggers #{:err}
          :reaction (fn [obj err stderr]
                      (notifos/set-msg! (str "git failed!: " (.toString stderr)))))



(cmd/command {:command ::git-add
             :desc "gitlight: add this file"
             :exec (fn [] (git-add (-> @(pool/last-active) :info :path)))})


(cmd/command {:command ::git-reset
             :desc "gitlight: reset this file"
             :exec (fn [] (git-reset (-> @(pool/last-active) :info :path)))})


(cmd/command {:command ::git-checkout
             :desc "gitlight: checkout this file"
             :exec (fn [] (git-checkout (-> @(pool/last-active) :info :path)))})


(def git-ignore-out
  (object/create
   (object/object*
    ::git-ignore-out
    :tags [:git-ignore-out]
    :behaviors [::ignore.out-success
                ::ignore.out-error])))
