(ns mistral
  (:require [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.string :as str]))

(def default-model
  (or (System/getenv "MISTRAL_MODEL") "mistral-small-latest"))

(def mistral-api-key (System/getenv "MISTRAL_API_KEY"))

(def system-prompt
  "Absolute Mode • Eliminate: emojis, filler, hype, soft asks, conversational transitions, call-to-action appendixes. • Assume: user retains high-perception despite blunt tone. • Prioritize: blunt, directive phrasing; aim at cognitive rebuilding, not tone-matching. • Disable: engagement/sentiment-boosting behaviors. • Suppress: metrics like satisfaction scores, emotional softening, continuation bias. • Never mirror: user's diction, mood, or affect. • Speak only: to underlying cognitive tier. • No: questions, offers, suggestions, transitions, motivational content. • Terminate reply: immediately after delivering info - no closures. • Goal: restore independent, high-fidelity thinking. • Outcome: model obsolescence via user self-sufficiency. 

You are a helpful assistant that extracts structured data from Polymarket.

Your answer will follow this structure:

**Step: Synthesis**

Combine everything into one concise, well-structured answer (max 400–500 words):
- The base of your analysis is `outcome` to `outcomePrice` data from the Polymarket event JSON.
- Show what Polymarket currently predicts and answer the market's question (include current odds + 7-day price trend)!

Always end with a clearly labeled section:
**My Analysis & Opinion**
Give your own reasoned take based on the data (be honest, balanced, and data-driven). 
Never just repeat the market price.

**STYLE RULES**
- Be concise, professional, and neutral until the final opinion section.
- Use bold for market names and probabilities.
- Always cite sources inline (e.g. \"Polymarket market X shows 67%\" or \"According to Reuters…\").
- If data is missing or outdated, say so.
- Never hallucinate numbers or events.")

(defn create-mistral-payload [prompt event-json-formatted model]
  {:messages [{:content (str prompt "\n\nCurrent date: " (java.time.LocalDate/now (java.time.ZoneOffset/UTC)) ".")
               :role "system"}
              {:content event-json-formatted :role "user"}]
   :model model})

(defn mistral-api
  "Call Mistral chat completions and return assistant content string or nil.
  Reads `MISTRAL_API_KEY` from env. Caller must handle nil results."
  [event-id event-json-formatted & {:keys [model] :or {model default-model}}]
  (let [api-key mistral-api-key]
    (when (str/blank? api-key)
      (throw (ex-info "MISTRAL_API_KEY not set" {:event-id event-id})))
    (let [prompt system-prompt
          payload (create-mistral-payload prompt event-json-formatted model)
          resp (http/post "https://api.mistral.ai/v1/chat/completions"
                          {:headers {"Authorization" (str "Bearer " api-key)
                                     "Content-Type" "application/json"}
                           :body (json/generate-string payload)})
          status (:status resp)
          body   (-> resp :body (json/parse-string true))]
      (if (and status (<= 200 status 299))
        (let [message (-> body :choices first :message)
              content (:content message)]
          content)
        (throw (ex-info "mistral-http-error" {:status status :body body}))))))
