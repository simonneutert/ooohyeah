#!/usr/bin/env bb
(ns polymarket-api
  (:require [babashka.http-client :as http]
            [babashka.cli :as cli]
            [babashka.fs :as fs]
            [cheshire.core :as json]))

(def base-dir (str (fs/expand-home "~") "/.ooohyeah"))

;; (def current-timestamp
;;   (str/replace (str (java.time.LocalDateTime/now))
;;                #"[T:\.]"
;;                "-"))

(if (not (.exists (fs/file (str base-dir "/data"))))
  (do
    (println "Data not found. Creating directory to cache Polymarket events.")
    (.mkdirs (fs/file (str base-dir "/data")))
    (System/exit 1))
  "Data directory exists, proceeding.")

(if (not (.exists (fs/file (str base-dir "/logs"))))
  (do
    (println "Logs not found. Creating directory to cache Polymarket logs.")
    (.mkdirs (fs/file (str base-dir "/logs")))
    (System/exit 1))
  "Logs directory exists, proceeding.")

(def cli-spec
  {:spec
   {:url   {:desc    "Base URL of the Polymarket API (or a WireMock proxy http://localhost:8080/)"
            :default "https://gamma-api.polymarket.com/"}
    :model {:desc    "Mistral model to use"
            :default "mistral-small-latest"}
    :help  {:desc   "Show this help message"
            :alias  :h
            :coerce :boolean}}})

(def cli-opts (cli/parse-opts *command-line-args* cli-spec))

(when (:help cli-opts)
  (println "ooohyeah - Scrape Polymarket events and output filtered JSON")
  (println)
  (println "Usage: bb ooohyeah.clj [options]")
  (println)
  (println "Options:")
  (println (cli/format-opts (merge cli-spec {:order [:url :model :help]})))
  (System/exit 0))

(def query-params
  {:limit      100
   :active     true
   :archived   false
   :closed     false
   :order      "volume24hr"
   :ascending  false})

(def base-url (:url cli-opts))

(defn fetch-page [offset]
  (-> (http/get (str base-url "events/pagination")
                {:headers      {"accept" "application/json, text/plain, */*"}
                 :query-params (assoc query-params :offset offset)})
      :body
      (json/parse-string true)
      :data))

(defn fetch-all-events []
  (loop [offset 0
         acc    []]
    (let [page (fetch-page offset)]
      (if (< (count page) (:limit query-params))
        (into acc page)
        (recur (+ offset (:limit query-params)) (into acc page))))))

(def rejected-tags #{"sports" "crypto" "temperature" "pop-culture" "ai" "soccer"})
(defn take-valid-markets [markets]
  (filter (fn [m]
            (some? (:outcomePrices m)))
          markets))

(defn -main [& args]
  (let [all-data (fetch-all-events)
        _ (spit (str base-dir "/data/events.json") (json/generate-string all-data {:pretty true}))
        formatted-data (->> all-data
                            (remove (fn [event]
                                      (some #(rejected-tags (:slug %))
                                            (:tags event))))
                            (map (fn [event]
                                   {:id    (:id event)
                                    :title  (:title event)
                                    :description (:description event)
                                    :tags (mapv :slug (:tags event))
                                    :markets (mapv (fn [m]
                                                     {:id            (:id m)
                                                      :question      (:question m)
                                                      :outcomes      (:outcomes m)
                                                      :outcomePrices (:outcomePrices m)
                                                      :volume24hr    (:volume24hr m)
                                                      :volume1wk     (:volume1wk m)
                                                      :volume1mo     (:volume1mo m)
                                                      :volume1yr     (:volume1yr m)
                                                      :oneDayPriceChange (:oneDayPriceChange m)
                                                      :oneWeekPriceChange (:oneWeekPriceChange m)
                                                      :oneMonthPriceChange (:oneMonthPriceChange m)
                                                      :liquidity (:liquidity m)
                                                      :liquidityClob (:liquidityClob m)
                                                      :competitive (:competitive m)
                                                      :resolutionSource (:resolutionSource m)})
                                                   (take-valid-markets (:markets event)))})))
        ;; tags (reduce (fn [acc tag]
        ;;                (update acc tag (fnil inc 0)))
        ;;              {}
        ;;              (flatten (mapv :tags formatted-data))) 
        ;; sorted-tags (sort-by val > tags)
        json-formatted-data (json/generate-string formatted-data {:pretty true})]

    (spit (str base-dir "/data/formatted_events.json") json-formatted-data)))