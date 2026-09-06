# Build stage
FROM rust:1.85-bookworm AS builder

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

# Build dependencies only
RUN cargo build --release && rm -rf src

# Copy actual source code
COPY src ./src
COPY migrations ./migrations

# Build for release
RUN touch src/main.rs && cargo build --release

# Runtime stage
FROM debian:bookworm-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    libsqlite3-0 \
    libjpeg62-turbo \
    # `jpegtran` powers the lossless JPEG optimisation; optional.
    libjpeg-turbo-progs \
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
