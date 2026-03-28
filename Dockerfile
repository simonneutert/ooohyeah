FROM babashka/babashka:latest

WORKDIR /app

# Copy bb.edn first and pre-fetch deps (cached layer)
COPY bb.edn .
RUN bb prepare

COPY tui.clj .
RUN mkdir data && mkdir logs
RUN bb run cache

ENTRYPOINT ["bb", "tui.clj"]
