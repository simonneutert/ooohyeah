#!/usr/bin/env bb
(ns tags
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [babashka.cli :as cli]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def json-data (json/parse-string (slurp (fs/expand-home "~") "/.ooohyeah/data/formatted_events.json") true))

;; instead of using a set, we can use a map to count occurrences of each tag, sorted by most common
(def tags (reduce (fn [acc tag]
                    (update acc tag (fnil inc 0)))
                  {}
                  (flatten (mapv :tags json-data))))

(def sorted-tags (sort-by val > tags))

;; drop with 2 or less
(def cleaned-sorted-tags (filter #(> (val %) 2) sorted-tags))

(println cleaned-sorted-tags)
(println (count cleaned-sorted-tags))