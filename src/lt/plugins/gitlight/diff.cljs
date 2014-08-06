(ns lt.plugins.gitlight.diff
  (:require [lt.object :as object]
            [lt.objs.command :as cmd]
            [lt.util.dom :as dom]
            [lt.objs.editor.pool :as pool]
            [lt.objs.editor :as editor]
            [clojure.string :as string]
            [lt.plugins.gitlight.gutter :as gut]
            [lt.plugins.gitlight.libs :as lib]
            [lt.plugins.gitlight.git :as git]
            [lt.plugins.gitlight.common-ui :as cui])
  (:require-macros [lt.macros :refer [defui behavior]]))


(defn click-run-function-update [fun up x y]
  (fun)
  (up))


(def context (atom 3))

; necessary for update
(def last-filename (atom nil))
(def last-cached (atom false))



(defn git-diff-update-fun []
  (git-diff @last-filename @last-cached))



(defn make-context []
   [:div.context
    (cui/make-button "-" "-" (partial click-run-function-update
                                      #(swap! context dec)
                                      git-diff-update-fun))
    (str "context: " @context)
    (cui/make-button "+" "+" (partial click-run-function-update
                                      #(swap! context inc)
                                      git-diff-update-fun))])


(defn make-more-context []
   [:div.more-context
    (cui/make-button "whole file" "whole file" (partial click-run-function-update
                                                        #(reset! context 100000)
                                                        git-diff-update-fun))
    (cui/make-button "reset" "reset" (partial click-run-function-update
                                              #(reset! context 3)
                                              git-diff-update-fun))] )


(defn cached-toggle-button []
  (let [cached-txt (if @last-cached "unstaged changes" "staged changes")]
    (cui/make-button cached-txt cached-txt
                     (fn []
                       (swap! last-cached not)
                       (git-diff-update-fun)))))


(defui commit-input []
  [:input.title {:type "text"
                 :size 81
           :placeholder "commit title"}])
;;   :focus (fn []
;;            (ctx/in! :popup.input))
;;   :blur (fn []
;;           (ctx/out! :popup.input)))


(defui commit-body []
  [:textarea.body {:placeholder "commit body"
                   :cols 81
                   :rows 10}])

(defn make-commit-form []
   (let [title (commit-input)
         body (commit-body)]

  [:div.commit-form
   title [:br]
   body [:br]
   (cui/make-button "submit" "submit" (fn [x y]
                                        (git/git-commit (dom/val title) (dom/val body))
                                        (git-diff-update-fun)))]))


(defui diff-panel [this]
  (let [output (:result @this)]
    [:div.gitlight-diff
     [:h1 "diff: " (if (= "" @last-filename)
                     "whole repo"
                     @last-filename)]
     (make-context)
     (make-more-context)
     (cached-toggle-button)

     (when @last-cached
       (make-commit-form))

     (for [[header left right groups] (parse-git-diff output)]
       [:table

        [:tr [:td.header header]]
        [:tr
         [:td.left [:b left]]
         [:td.right [:b right]]]

        (for [[location content] groups]
          (cons [:tr.where [:td {:colspan 2} [:b location]]]
                (for [line-group (partition-by #(= \space (first %)) content)
                      :let [columned (columner line-group)]]
                  (for [[left right](:cols columned)]
                    [:tr {:class (:class columned)}
                     [:td.left [:pre left]]
                     [:td.right [:pre right]]]))))
        ])
     ]))



(defn breaker [left right]
  (let [m [(first left) (first right)]]
    (if (some identity m)
      (cons m (breaker (rest left) (rest right))))))


(defn separate [fun coll]
  (reduce (fn [[left right] line]
            (if (fun line)
              [(conj left line) right]
              [left (conj right line)]))
          [[] []]
          coll))


(defn columner [lines]
  (if (= \space (first (first lines)))
    {:class "context" :cols (breaker lines lines)}
    (let [[left right] (separate #(= \- (first %)) lines)]
      {:class "changed" :cols (breaker left right)})))



(defn split-into-groups [file-lines]
  (partition 2 (partition-by #(not= \@ (first %)) file-lines)))


(defn parse-single-git-diff [lines]
  (let [[fileinfo diff-lines] (split-at 3 lines)
        groups (split-into-groups diff-lines)]
    (conj (into [] fileinfo) groups)))


(defn split-into-files [lines]
  (let [diff-regexp #"diff --git .*"
        splitter (partial re-matches diff-regexp)
        splitted-by-regexp (partition-by splitter lines)]
    (take-nth 2 (rest splitted-by-regexp))))


(defn parse-git-diff [raw-data]
  (let [lines (string/split-lines (.toString raw-data))
        files (split-into-files lines)]
    (map parse-single-git-diff files)))



(def git-diff-output
  (cui/make-output-tab-object "Git diff"
                              ::gitlight-diff
                              diff-panel))



(defn git-diff
  ([filepath] (git-diff filepath false))
  ([filepath cached?] (let [contextstr (str "-U" @context)
                            args ["diff" "--no-color" contextstr
                                  (when cached? "--cached")
                                  "--" filepath]]
                        (reset! last-filename filepath)
                        (reset! last-cached cached?)
                        (git/git args git-diff-output))))


(defn git-diff-button [action filename]
  (git-diff filename))

(defn git-diff-cached-button [action filename]
  (git-diff filename true))

(defn git-diff-repo-button [action filename]
  (git-diff ""))

(defn git-diff-cached-repo-button [action filename]
  (git-diff "" true))


(defui style-diff-marker [[p m :as content]]
  [:div {:class (cond
                 (and (= p " ") (nil? m)) "no-change"
                 (and (= p "+") (nil? m)) "added-line"
                 (and (= p " ") (= "-" (first m))) "deleted-lines"
                 (and (= p "+") (= "-" (first m))) "modified-line")}
   content])


(defn filler []
  (let [file-editor (editor/->cm-ed (pool/last-active))
        line-number (.-size (.-doc file-editor))]
    (repeat line-number " ")))

(defn dec>0 [x]
  (if (> x 0)
    (dec x)
    0))

(defn with-deficit [marker deficit]
  (str marker
       (when (> deficit 0)
         (if (= \+ marker)
           "-"
           (str "-" deficit "↑")))))


(defn make-indicator [[consumed deficit] marker]
  (let [new-marker (with-deficit marker deficit)
        added-new (conj consumed new-marker)]
    (case marker
      \-     [consumed  (inc deficit)]
      \+     [added-new (dec>0 deficit)]
      \space [added-new 0])))


(defn diff-gutter [diff-lines]
  (let [diff (map first diff-lines)]
    (first (reduce make-indicator [[] 0] diff))))


(defn parse-diff-gutter-out [this command stdout stderr]
  (let [splitted (string/split-lines (.toString stdout))
        diff-part (drop 5 splitted)]
    (gut/show-gutter-data
      (pool/last-active)
      println
      style-diff-marker
      (if (empty? diff-part)
        (filler)
        (diff-gutter diff-part)))))


(behavior ::parse-diff-gutter-out
          :triggers [:out]
          :reaction parse-diff-gutter-out)


(behavior ::diff-gutter-err
          :triggers [:err]
          :reaction (fn [this command stdout stderr]
                      (println "error" stderr)))


(behavior ::refresh-diff-gutter-on-save
          :triggers #{:save+}
          :type :user
          :desc "gitlight: refresh diff gutter"
          :reaction (fn [editor content]
                      (if (object/has-tag? editor ::gitlight-gutter-on)
                        (do
                          (object/remove-tags editor #{::gitlight-gutter-on})
                          (add-git-diff-gutter)))
                      content))


(def git-diff-gutter-out
  (object/create
   (object/object* ::diff-file-out
                   :tags #{::diff-file-out}
                   :behaviors [::parse-diff-gutter-out ::diff-gutter-err])))


(defn add-git-diff-gutter []
  (object/add-tags (pool/last-active) #{::gitlight-gutter-on})
  (git/git ["diff" "-U10000" "--" (lib/current-file-path)]
           git-diff-gutter-out))


(defn remove-git-diff-gutter []
  (gut/remove-gutters (pool/last-active))
  (object/remove-tags (pool/last-active) #{::gitlight-gutter-on}))


(defn toggle-git-diff-gutter []
  (if (object/has-tag? (pool/last-active) ::gitlight-gutter-on)
    (remove-git-diff-gutter)
    (add-git-diff-gutter)))

(cmd/command {:command ::gitlight-add-diff-gutter
              :desc "gitlight: add gutter diff (experimental)"
              :exec add-git-diff-gutter})


(cmd/command {:command ::gitlight-remove-diff-gutter
              :desc "gitlight: remove gutter diff (experimental)"
              :exec remove-git-diff-gutter})


(cmd/command {:command ::gitlight-toggle-diff-gutter
              :desc "gitlight: toggle gutter diff (experimental)"
              :exec toggle-git-diff-gutter})


(cmd/command {:command ::git-diff-file
              :desc "gitlight: diff this file"
              :exec (fn []
                      (git-diff (lib/current-file-path)))})


(cmd/command {:command ::git-diff-repo
              :desc "gitlight: diff this repo"
              :exec (fn []
                      (git-diff ""))})
