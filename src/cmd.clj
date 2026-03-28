(ns cmd
  (:require [polymarket-api]
            [tui]))

(defn -main [& args]
  (let [command (first args)
        remaining-args (rest args)]
    (cond
      (= command "cache") (polymarket-api/-main remaining-args)
      (= command "tui") (tui/-main remaining-args)
      :else (do
              (println "Usage:")
              (println "  ooohyeah cache - Caches data from the Polymarket API")
              (println "  ooohyeah tui - Starts a TUI to explore the Polymarket events")))))