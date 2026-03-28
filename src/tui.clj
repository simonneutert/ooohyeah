#!/usr/bin/env bb
(ns tui (:require [charm.core :as charm]
                  [cheshire.core :as json]
                  [clojure.string :as str]
                  [mistral :as mistral]
                  [babashka.fs :as fs]))

(def base-dir (str (fs/expand-home "~") "/.ooohyeah"))

;; ---------------------------------------------------------------------------
;; Logging
;; ---------------------------------------------------------------------------
(if (not (.exists (fs/file (str base-dir "/logs"))))
  (do
    (println "Data not found. Creating directory to cache Polymarket events.")
    (.mkdirs (fs/file (str base-dir "/logs")))
    (System/exit 1))
  "Data directory exists, proceeding.")
(def log-file (str base-dir "/logs/tui.log"))

(defn log-error [context ex]
  (let [ts  (str (java.time.LocalDateTime/now))
        ex-msg (str ex)
        msg (str ts " [ERROR] " context ": " ex-msg "\n"
                 (when (instance? Throwable ex)
                   (str (str/join "\n" (map str (.getStackTrace ^Throwable ex))) "\n"))
                 "\n")]
    (try
      (spit log-file msg :append true)
      (catch Throwable _
        (binding [*out* *err*]
          (println msg))))))

;; ---------------------------------------------------------------------------
;; Data loading
;; ---------------------------------------------------------------------------
(if (not (.exists (fs/file (str base-dir "/data"))))
  (do
    (println "Data not found. Creating directory to cache Polymarket events.")
    (.mkdirs (fs/file (str base-dir "/data")))
    (System/exit 1))
  "Data directory exists, proceeding.")

(try
  (def all-events (json/parse-string (slurp (str base-dir "/data/formatted_events.json")) true))
  (catch Throwable e
    ;; (log-error "loading data" e)
    (println (str "Error loading data — check " base-dir "/logs/tui.log"))))

(def summaries-cache (atom {}))

(defn compute-tags
  "Returns sorted vector of [tag count] pairs from events, descending by count."
  [events]
  (->> events
       (mapcat :tags)
       (frequencies)
       (sort-by val >)
       (filterv #(> (val %) 1))))

(defn remaining-tags
  "Tags in events not already selected, sorted by frequency."
  [events selected-tags]
  (let [selected (set selected-tags)]
    (->> events
         (mapcat :tags)
         (remove selected)
         (frequencies)
         (sort-by val >)
         (filterv #(> (val %) 1)))))

(defn events-with-tag [events tag]
  (filterv #(some #{tag} (:tags %)) events))

(defn event-volume24hr [ev]
  (->> (:markets ev)
       (map #(or (:volume24hr %) 0))
       (reduce +)))

(defn format-price [price-str]
  (when price-str
    (let [prices (json/parse-string price-str)]
      (when (seq prices)
        (str (Math/round (* 100.0 (Double/parseDouble (first prices)))) "¢")))))

(defn format-volume [v]
  (when v
    (let [v (double v)]
      (cond
        (>= v 1e6) (format "$%.1fM" (/ v 1e6))
        (>= v 1e3) (format "$%.0fK" (/ v 1e3))
        :else       (format "$%.0f" v)))))

;; ---------------------------------------------------------------------------
;; Styles
;; ---------------------------------------------------------------------------

(def title-style   (charm/style :fg charm/magenta :bold true))
(def heading-style (charm/style :fg charm/cyan :bold true))
(def price-yes     (charm/style :fg charm/green :bold true))
(def price-no      (charm/style :fg charm/red))
(def dim-style     (charm/style :fg 245))
(def border-style  (charm/style :fg 238))
(def badge-style   (charm/style :fg charm/black :bg charm/yellow :bold true))

;; ---------------------------------------------------------------------------
;; Help bindings per screen
;; ---------------------------------------------------------------------------

(def search-help
  (charm/help-from-pairs
   "type" "filter tags"
   "up/dn" "navigate"
   "enter" "select tag"
   "ctrl+c" "quit"))

(def narrow-help
  (charm/help-from-pairs
   "type" "filter tags"
   "up/dn" "navigate"
   "enter" "narrow by tag"
   "s" "skip, show events"
   "escape" "back"
   "q" "quit"))

(def events-help
  (charm/help-from-pairs
   "type" "filter events"
   "up/dn" "navigate"
   "enter" "view event"
   "escape" "back"
   "q" "quit"))

(def detail-help
  (charm/help-from-pairs
   "j/k" "scroll"
   "a" "ai summary"
   "escape" "back"
   "q" "quit"))

(def summary-help
  (charm/help-from-pairs
   "j/k" "scroll"
   "escape" "back"
   "q" "quit"))

;; ---------------------------------------------------------------------------
;; Item builders
;; ---------------------------------------------------------------------------

(defn make-tag-items [tag-counts]
  (mapv (fn [[tag cnt]]
          {:title (str tag " (" cnt ")")
           :tag   tag
           :count cnt})
        tag-counts))

(defn make-event-items [events]
  (mapv (fn [ev]
          (let [top-market (first (:markets ev))]
            {:title (:title ev)
             :event ev}))
        events))

;; ---------------------------------------------------------------------------
;; Screens:
;;   :tag-search   - search/select a tag (text input + filtered list)
;;   :narrow-tag   - pick another tag to narrow, or skip to events
;;   :events-list  - matching event titles
;;   :event-detail - one event's markets
;; ---------------------------------------------------------------------------

(defn filter-tag-list
  "Rebuild the visible tag list from the search input."
  [state]
  (let [query    (str/lower-case (charm/text-input-value (:search-input state)))
        all-tags (:all-tags state)
        matched  (if (str/blank? query)
                   all-tags
                   (filterv #(str/includes? (str/lower-case (first %)) query) all-tags))
        items    (make-tag-items matched)]
    (assoc state :tag-list (charm/item-list items :height 18))))

(defn filter-narrow-list
  "Rebuild the narrow tag list from the search input."
  [state]
  (let [query   (str/lower-case (charm/text-input-value (:search-input state)))
        all     (:all-narrow-tags state)
        matched (if (str/blank? query)
                  all
                  (filterv #(str/includes? (str/lower-case (first %)) query) all))
        items   (make-tag-items matched)]
    (assoc state :narrow-list (charm/item-list items
                                               :height 18
                                               :title (:narrow-title state)))))

(defn filter-event-list
  "Rebuild the event list from the search input."
  [state]
  (let [query   (str/lower-case (charm/text-input-value (:search-input state)))
        all     (:all-events-data state)
        matched (if (str/blank? query)
                  all
                  (filterv #(str/includes? (str/lower-case (:title %)) query) all))
        items   (make-event-items matched)]
    (assoc state :event-list (charm/item-list items
                                              :height 18
                                              :title (:events-title state)))))

(defn init []
  (let [tag-counts (compute-tags all-events)]
    [{:screen       :tag-search
      :all-tags     tag-counts
      :tag-list     (charm/item-list (make-tag-items tag-counts) :height 18)
      :search-input (charm/text-input :prompt "search: " :placeholder "filter tags...")
      :tags         []
      :events       []
      :event-list   nil
      :narrow-list  nil
      :help         (charm/help search-help :width 60)}
     nil]))

;; ---------------------------------------------------------------------------
;; Screen transitions
;; ---------------------------------------------------------------------------

(defn goto-search [state]
  (let [tag-counts (compute-tags all-events)]
    (-> state
        (assoc :screen :tag-search
               :all-tags tag-counts
               :tag-list (charm/item-list (make-tag-items tag-counts) :height 18)
               :search-input (charm/text-input :prompt "search: " :placeholder "filter tags...")
               :tags []
               :events []
               :help (charm/help search-help :width 60)))))

(defn goto-events [state]
  (let [evs      (vec (sort-by event-volume24hr > (:events state)))
        ev-items (make-event-items evs)
        title    (str (str/join " + " (:tags state))
                      " (" (count evs) " events)")]
    (-> state
        (assoc :screen :events-list
               :all-events-data evs
               :events-title title
               :search-input (charm/text-input :prompt "search: " :placeholder "filter events...")
               :event-list (charm/item-list ev-items :height 18 :title title)
               :help (charm/help events-help :width 60)))))

(defn goto-detail [state event]
  (-> state
      (assoc :screen :event-detail
             :detail-event event
             :detail-scroll 0
             :help (charm/help detail-help :width 60))))

(defn fetch-summary-cmd [event]
  (charm/cmd
   (fn []
     (try
       (let [event-id (or (:id event) (:title event))
             text     (mistral/mistral-api event-id (json/generate-string event))]
         {:type :summary-done :event-id event-id :text text})
       (catch Throwable e
         {:type :summary-error :error (str e)})))))

(defn goto-summary [state]
  (let [ev       (:detail-event state)
        event-id (or (:id ev) (:title ev))
        cached   (get @summaries-cache event-id)]
    (if cached
      [(-> state
           (assoc :screen :ai-summary
                  :summary-loading false
                  :summary-text cached
                  :summary-scroll 0
                  :help (charm/help summary-help :width 60)))
       nil]
      (let [[spinner spinner-cmd] (charm/spinner-init (charm/spinner :dots))]
        [(-> state
             (assoc :screen :ai-summary
                    :summary-loading true
                    :summary-spinner spinner
                    :summary-text nil
                    :summary-error nil
                    :summary-scroll 0
                    :help (charm/help summary-help :width 60)))
         (charm/batch spinner-cmd (fetch-summary-cmd ev))]))))

(declare select-tag)

(defn select-tag
  "After picking a tag, auto-narrow if more tags exist, else show events."
  [state tag]
  (let [tags     (conj (:tags state) tag)
        filtered (reduce (fn [evs t] (events-with-tag evs t)) all-events tags)
        extra    (remaining-tags filtered tags)]
    (if (seq extra)
      ;; more tags to narrow by
      (let [narrow-title (str "Narrow: " (str/join " + " tags)
                              " (" (count filtered) " events)")]
        (-> state
            (assoc :screen :narrow-tag
                   :tags tags
                   :events filtered
                   :all-narrow-tags extra
                   :narrow-title narrow-title
                   :search-input (charm/text-input :prompt "search: " :placeholder "filter tags...")
                   :narrow-list (charm/item-list (make-tag-items extra)
                                                 :height 18
                                                 :title narrow-title)
                   :help (charm/help narrow-help :width 60))))
      ;; no more tags — go straight to events
      (-> state
          (assoc :tags tags :events filtered)
          goto-events))))

;; ---------------------------------------------------------------------------
;; Update
;; ---------------------------------------------------------------------------

(defn update-fn [state msg]
  (try
    (cond
      (charm/key-match? msg "ctrl+c")
      [state charm/quit-cmd]

      ;; --- Tag search screen ---
      (= (:screen state) :tag-search)
      (cond
        (charm/key-match? msg "enter")
        (let [selected (charm/list-selected-item (:tag-list state))]
          (if selected
            [(select-tag state (:tag selected)) nil]
            [state nil]))

        (or (charm/key-match? msg "down")
            (charm/key-match? msg "ctrl+n"))
        (let [[new-list cmd] (charm/list-update (:tag-list state) msg)]
          [(assoc state :tag-list new-list) cmd])

        (or (charm/key-match? msg "up")
            (charm/key-match? msg "ctrl+p"))
        (let [[new-list cmd] (charm/list-update (:tag-list state) msg)]
          [(assoc state :tag-list new-list) cmd])

        :else
        ;; typing goes to text input, then re-filter list
        (let [[new-input cmd] (charm/text-input-update (:search-input state) msg)
              s (-> state
                    (assoc :search-input new-input)
                    filter-tag-list)]
          [s cmd]))

      ;; --- Narrow tag screen ---
      (= (:screen state) :narrow-tag)
      (cond
        (charm/key-match? msg "escape")
        [(goto-search state) nil]

        (charm/key-match? msg "q")
        (if (str/blank? (charm/text-input-value (:search-input state)))
          [state charm/quit-cmd]
          (let [s (-> state
                      (update :search-input charm/text-input-reset)
                      filter-narrow-list)]
            [s nil]))

        (charm/key-match? msg "s")
        [(goto-events state) nil]

        (charm/key-match? msg "enter")
        (let [selected (charm/list-selected-item (:narrow-list state))]
          (if selected
            [(select-tag state (:tag selected)) nil]
            [(goto-events state) nil]))

        (or (charm/key-match? msg "down")
            (charm/key-match? msg "ctrl+n"))
        (let [[new-list cmd] (charm/list-update (:narrow-list state) msg)]
          [(assoc state :narrow-list new-list) cmd])

        (or (charm/key-match? msg "up")
            (charm/key-match? msg "ctrl+p"))
        (let [[new-list cmd] (charm/list-update (:narrow-list state) msg)]
          [(assoc state :narrow-list new-list) cmd])

        :else
        (let [[new-input cmd] (charm/text-input-update (:search-input state) msg)
              s (-> state
                    (assoc :search-input new-input)
                    filter-narrow-list)]
          [s cmd]))

      ;; --- Events list screen ---
      (= (:screen state) :events-list)
      (cond
        (charm/key-match? msg "escape")
        [(goto-search state) nil]

        (charm/key-match? msg "q")
        (if (str/blank? (charm/text-input-value (:search-input state)))
          [state charm/quit-cmd]
          (let [s (-> state
                      (update :search-input charm/text-input-reset)
                      filter-event-list)]
            [s nil]))

        (charm/key-match? msg "enter")
        (let [selected (charm/list-selected-item (:event-list state))]
          (if selected
            [(goto-detail state (:event selected)) nil]
            [state nil]))

        (or (charm/key-match? msg "down")
            (charm/key-match? msg "ctrl+n"))
        (let [[new-list cmd] (charm/list-update (:event-list state) msg)]
          [(assoc state :event-list new-list) cmd])

        (or (charm/key-match? msg "up")
            (charm/key-match? msg "ctrl+p"))
        (let [[new-list cmd] (charm/list-update (:event-list state) msg)]
          [(assoc state :event-list new-list) cmd])

        :else
        (let [[new-input cmd] (charm/text-input-update (:search-input state) msg)
              s (-> state
                    (assoc :search-input new-input)
                    filter-event-list)]
          [s cmd]))

      ;; --- Event detail screen ---
      (= (:screen state) :event-detail)
      (cond
        (charm/key-match? msg "escape")
        [(goto-events state) nil]

        (charm/key-match? msg "q")
        [state charm/quit-cmd]

        (or (charm/key-match? msg "j")
            (charm/key-match? msg "down"))
        [(update state :detail-scroll inc) nil]

        (or (charm/key-match? msg "k")
            (charm/key-match? msg "up"))
        [(update state :detail-scroll #(max 0 (dec %))) nil]

        (charm/key-match? msg "a")
        (goto-summary state)

        :else
        [state nil])

      ;; --- AI summary screen ---
      (= (:screen state) :ai-summary)
      (cond
        (charm/key-match? msg "escape")
        [(goto-detail state (:detail-event state)) nil]

        (charm/key-match? msg "q")
        [state charm/quit-cmd]

        (= :summary-done (:type msg))
        (let [event-id (:event-id msg)
              text     (:text msg)]
          (swap! summaries-cache assoc event-id text)
          [(assoc state
                  :summary-loading false
                  :summary-text text
                  :summary-error nil)
           nil])

        (= :summary-error (:type msg))
        [(assoc state
                :summary-loading false
                :summary-error (:error msg))
         nil]

        (and (:summary-loading state)
             (= :spinner-tick (:type msg)))
        (let [[new-spinner cmd] (charm/spinner-update (:summary-spinner state) msg)]
          [(assoc state :summary-spinner new-spinner) cmd])

        (and (not (:summary-loading state))
             (or (charm/key-match? msg "j")
                 (charm/key-match? msg "down")))
        [(update state :summary-scroll inc) nil]

        (and (not (:summary-loading state))
             (or (charm/key-match? msg "k")
                 (charm/key-match? msg "up")))
        [(update state :summary-scroll #(max 0 (dec %))) nil]

        :else
        [state nil])

      :else
      [state nil])
    (catch Throwable e
      (log-error (str "update-fn [" (:screen state) "]") e)
      [state nil])))

;; ---------------------------------------------------------------------------
;; View
;; ---------------------------------------------------------------------------

(def separator (apply str (repeat 50 "─")))

(defn wrap-line [width line]
  (if (<= (count line) width)
    [line]
    (let [words (str/split line #" ")]
      (reduce
       (fn [acc word]
         (let [current (last acc)
               candidate (if (str/blank? current) word (str current " " word))]
           (if (<= (count candidate) width)
             (conj (vec (butlast acc)) candidate)
             (conj acc word))))
       [""]
       words))))

(defn render-market [m]
  (try
    (let [outcomes   (json/parse-string (:outcomes m))
          prices     (json/parse-string (:outcomePrices m))
          yes-price  (when (first prices)  (str (Math/round (* 100.0 (Double/parseDouble (first prices)))) "¢"))
          no-price   (when (second prices) (str (Math/round (* 100.0 (Double/parseDouble (second prices)))) "¢"))
          day-chg    (:oneDayPriceChange m)
          week-chg   (:oneWeekPriceChange m)
          month-chg  (:oneMonthPriceChange m)
          changes    (str/join "  "
                               (remove nil?
                                       [(when day-chg   (format "1d: %+.1f%%" (* 100 day-chg)))
                                        (when week-chg  (format "1w: %+.1f%%" (* 100 week-chg)))
                                        (when month-chg (format "1m: %+.1f%%" (* 100 month-chg)))]))]
      (str "  " (:question m) "\n"
           "    " (charm/render price-yes (str (first outcomes) " " yes-price))
           "  "  (charm/render price-no  (str (second outcomes) " " no-price))
           "\n"
           (when (seq changes)
             (str "    " (charm/render dim-style changes) "\n"))))
    (catch Throwable e
      (log-error (str "render-market " (:id m) " " (:question m)) e)
      (str "  " (charm/render dim-style (str "[error rendering: " (:question m) "]")) "\n"))))

(defn detail-view [state]
  (let [ev     (:detail-event state)
        scroll (:detail-scroll state)
        lines  (concat
                [(charm/render heading-style (:title ev))
                 ""
                 (when (seq (:description ev))
                   (let [desc (:description ev)
                         short (if (> (count desc) 500)
                                 (str (subs desc 0 497) "...")
                                 desc)]
                     (charm/render dim-style short)))
                 ""
                 (charm/render border-style separator)
                 (charm/render heading-style (str (count (:markets ev)) " Market(s)"))
                 ""]
                (mapcat (fn [m]
                          [(render-market m)
                           (charm/render border-style (apply str (repeat 40 "·")))])
                        (:markets ev)))
        ;; Flatten embedded newlines so each screen line is a separate entry
        all-lines (->> lines
                       (remove nil?)
                       (mapcat #(str/split-lines %))
                       (mapcat (partial wrap-line 72))
                       vec)
        visible   (take 20 (drop scroll all-lines))]
    (str/join "\n" visible)))

(defn summary-view [state]
  (let [ev     (:detail-event state)
        scroll (:summary-scroll state)]
    (str (charm/render heading-style (:title ev))
         "\n\n"
         (cond
           (:summary-loading state)
           (str (charm/spinner-view (:summary-spinner state))
                "  "
                (charm/render dim-style "Fetching AI summary…"))

           (:summary-error state)
           (charm/render price-no (str "Error: " (:summary-error state)))

           :else
           (let [lines   (->> (str/split-lines (:summary-text state))
                              (mapcat (partial wrap-line 72))
                              vec)
                 visible (take 20 (drop scroll lines))]
             (str/join "\n" visible))))))

(defn view [state]
  (try
    (let [{:keys [screen help tags]} state
          raw (str
               (charm/render title-style "ooohyeah") "  "
               (charm/render dim-style "polymarket explorer")
               "\n\n"

               (case screen
                 :tag-search
                 (str (charm/text-input-view (:search-input state))
                      "\n\n"
                      (charm/list-view (:tag-list state)))

                 :narrow-tag
                 (str (charm/render badge-style (str " " (str/join " + " tags) " "))
                      "  "
                      (charm/render dim-style (str (count (:events state)) " events — narrow or press s to view all"))
                      "\n\n"
                      (charm/text-input-view (:search-input state))
                      "\n\n"
                      (charm/list-view (:narrow-list state)))

                 :events-list
                 (str (charm/render badge-style (str " " (str/join " + " tags) " "))
                      "  "
                      (charm/render dim-style (str (count (:events state)) " events"))
                      "\n\n"
                      (charm/text-input-view (:search-input state))
                      "\n\n"
                      (charm/list-view (:event-list state)))

                 :event-detail
                 (detail-view state)

                 :ai-summary
                 (summary-view state))

               "\n\n"
               (charm/help-view-short help))]
      ;; Strip trailing blank lines to prevent jline Display.update from
      ;; calling .remove() on an immutable Clojure vector
      (str/trimr raw))
    (catch Throwable e
      (log-error (str "view [" (:screen state) "]") e)
      (str (charm/render title-style "ooohyeah") "  "
           (charm/render dim-style (str "render error — check " base-dir "/logs/tui.log"))
           "\n\npress escape to go back, q to quit\n\n"
           (charm/help-view-short (:help state))))))

;; ---------------------------------------------------------------------------
;; Run
;; ---------------------------------------------------------------------------
(defn -main [& args]
  (try
    (charm/run {:init init
                :update update-fn
                :view view
                :alt-screen true})
    (catch Throwable e
      (log-error "charm/run" e)
      (binding [*out* *err*]
        (println (str "Fatal error — see " base-dir "/logs/tui.log"))
        (println (str e))))))
