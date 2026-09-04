# Build stage
FROM rust:1.83-bookworm AS builder

WORKDIR /app

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

RUN apt-get update && apt-get install -y \
    ca-certificates \
    libssl3 \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Copy binary from builder
COPY --from=builder /app/target/release/home-nas-server /app/home-nas-server

# Create non-root user
RUN useradd -r -s /bin/false appuser && chown -R appuser:appuser /app
USER appuser

EXPOSE 8485

CMD ["./home-nas-server"]
