default:
  @just --list

cache_polymarket:
  bb run cache

# Run the ooohyeah.clj script to fetch and format the data (from Wiremock), then format the JSON files with deno fmt
wiremock:
  bb run cache --url http://localhost:8080/
  deno fmt data/*.json

clean:
  rm -rf data/*

# Wiremock recording and playback with Docker, pass the API URL as an argument to the recording command (e.g. `just docker_record "https://swapi.dev/api/"`)
docker_record api_url:
  docker run -it --rm \
  -p 8080:8080 \
  -v $(pwd)/mappings:/home/wiremock/mappings \
  -v $(pwd)/__files:/home/wiremock/__files \
  wiremock/wiremock \
  --record-mappings \
  --proxy-all="{{api_url}}" \
  --verbose

# Wiremock recording and playback with Docker, make sure to run `just docker_record api_url` first to record the interactions, then you can run `just docker_playback` to start the server with the recorded interactions
docker_playback:
  docker run -it --rm \
  -p 8080:8080 \
  -v $(pwd)/mappings:/home/wiremock/mappings \
  -v $(pwd)/__files:/home/wiremock/__files \
  wiremock/wiremock

# Build the TUI Docker image
docker_build:
  docker build -t ooohyeah-tui .

# Run the TUI in Docker (needs -it for interactive terminal)
docker_tui:
  docker run -it --rm ooohyeah-tui

# Wiremock recording and playback with Podman, pass the API URL as an argument to the recording command (e.g. `just podman_record "https://swapi.dev/api/"`)
podman_record api_url:
  podman run -it --rm \
  -p 8080:8080 \
  -v $(pwd)/mappings:/home/wiremock/mappings:Z \
  -v $(pwd)/__files:/home/wiremock/__files:Z \
  wiremock/wiremock \
  --record-mappings \
  --proxy-all="{{api_url}}" \
  --verbose

# Wiremock recording and playback with Podman, make sure to run `just podman_record api_url` first to record the interactions, then you can run `just podman_playback` to start the server with the recorded interactions
podman_playback:
  podman run -it --rm \
  -p 8080:8080 \
  -v $(pwd)/mappings:/home/wiremock/mappings:Z \
  -v $(pwd)/__files:/home/wiremock/__files:Z \
  wiremock/wiremock

wiremock_clean:
  rm -rf __files/* mappings/*