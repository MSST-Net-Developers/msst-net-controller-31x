# syntax=docker/dockerfile:1
#
# MSST-Net WebUI Controller image.
#
# Build from the repository root, with Core's repository as the `core`
# context (the protocol crate is a path dependency) and the web frontend
# built first (its Gradle build needs the Android SDK, so it runs outside):
#
#   (cd compose && ./gradlew :webApp:wasmJsBrowserDistribution)
#   docker buildx build -f docker/webui/Dockerfile \
#       --build-context core=../msst-net-core-31x -t msstnet/webui .
#
# See docs/DOCKER.md for running it next to the Core container.

FROM rust:1.90-bookworm AS build
WORKDIR /src
# Core's workspace manifest: msst-ipc-protocol inherits version and edition
# from it.
COPY --from=core Cargo.toml msst-net-core-31x/Cargo.toml
COPY --from=core crates msst-net-core-31x/crates
WORKDIR /src/msst-net-controller-31x
COPY Cargo.toml Cargo.lock build.rs ./
COPY src src
COPY compose/webApp/build/dist/wasmJs/productionExecutable compose/webApp/build/dist/wasmJs/productionExecutable
RUN test -f compose/webApp/build/dist/wasmJs/productionExecutable/index.html \
    || { echo "Web frontend missing: run ./gradlew :webApp:wasmJsBrowserDistribution in compose/ first" >&2; exit 1; }
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/msst-net-controller-31x/target \
    cargo build --release --locked --features webui --bin msst-net-webui \
    && cp target/release/msst-net-webui /usr/local/bin/

FROM debian:bookworm-slim
COPY --from=build /usr/local/bin/msst-net-webui /usr/local/bin/msst-net-webui
# Password, config.toml and the self-signed certificate: mount a volume.
ENV MSST_WEBUI_CONFIG_DIR=/var/lib/msst-webui
VOLUME ["/var/lib/msst-webui"]
EXPOSE 18080
HEALTHCHECK --interval=30s --timeout=10s --start-period=10s CMD ["msst-net-webui", "healthcheck"]
ENTRYPOINT ["msst-net-webui"]
