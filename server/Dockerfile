# Build stage
# ort-sys (ONNX Runtime) requires rustc >= 1.88, and the prebuilt ONNX Runtime
# library needs libstdc++ symbols newer than Debian bookworm's GCC 12 —
# build on trixie (GCC 14).
FROM rust:1.92-trixie AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    pkg-config libsqlite3-dev \
    # libssl-dev is only needed for the optional `--features onnx` build
    libssl-dev \
    && rm -rf /var/lib/apt/lists/*

# Copy manifests
COPY Cargo.toml Cargo.lock* ./

# Create dummy main.rs for dependency caching
RUN mkdir src && echo "fn main() {}" > src/main.rs

# Build dependencies only (with the same feature set as the final build)
RUN cargo build --release --features onnx && rm -rf src

# Copy actual source code
COPY src ./src
COPY migrations ./migrations

# Build for release — with the ONNX runtime so YOLO/scene/face detection works
# out of the box (drop model files under /data/models, see README).
RUN touch src/main.rs && cargo build --release --features onnx

# Runtime stage — must match the builder's glibc (trixie) since the binary is
# linked against trixie's glibc / libstdc++.
FROM debian:trixie-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    libsqlite3-0 \
    libjpeg62-turbo \
    # `jpegtran` powers the lossless JPEG optimisation; optional.
    libjpeg-turbo-progs \
    # `ffmpeg`/`ffprobe` power the video pipeline (probe, posters, scene frames).
    ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/target/release/homehub-server /app/homehub-server

# Create non-root user
RUN useradd -r -s /bin/false appuser \
    && mkdir -p /data \
    && chown -R appuser:appuser /app /data
USER appuser

ENV CONFIG_PATH=/app/config.yaml
ENV DATA_DIR=/data

EXPOSE 8485

VOLUME ["/data"]

CMD ["./homehub-server"]
