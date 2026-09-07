#!/usr/bin/env bash
# Build the server, start it against throw-away fixtures and run the smoke test.
#
#   bash server/tests/run.sh
#
# Environment:
#   SMOKE_DIR  working directory   (default /tmp/homehub-smoke)
#   BIND       listen address      (default 127.0.0.1:8485)
#   KEEP       set to 1 to leave the server running afterwards

set -uo pipefail
cd "$(dirname "$0")/.."          # -> server/
SERVER_DIR=$PWD
cd ..                            # -> repo root
ROOT=$PWD

SMOKE_DIR=${SMOKE_DIR:-/tmp/homehub-smoke}
BIND=${BIND:-127.0.0.1:8485}
KEEP=${KEEP:-0}
PORT=${BIND##*:}

if ! command -v cargo >/dev/null 2>&1; then
  . "$HOME/.cargo/env" 2>/dev/null || true
fi

echo "== build =="
cargo build --manifest-path "$SERVER_DIR/Cargo.toml" || exit 1

echo "== fixtures =="
rm -rf "$SMOKE_DIR"
python3 "$SERVER_DIR/tests/make_fixtures.py" "$SMOKE_DIR" "$BIND" || exit 1

echo "== start server =="
CONFIG_PATH="$SMOKE_DIR/config.yaml" \
RUST_LOG=info \
ADMIN_DIST="$ROOT/admin/dist" \
nohup "$SERVER_DIR/target/debug/homehub-server" > "$SMOKE_DIR/server.log" 2>&1 < /dev/null &
SERVER_PID=$!
# macOS 没有 setsid，用 disown 脱离作业表即可（cleanup 仍按 PID 清理）
disown "$SERVER_PID" 2>/dev/null || true

cleanup() {
  if [ "$KEEP" != "1" ]; then
    kill "$SERVER_PID" 2>/dev/null
    pkill -f "homehub-server" 2>/dev/null
  fi
}
trap cleanup EXIT

for _ in $(seq 1 30); do
  if curl -s -o /dev/null "http://127.0.0.1:$PORT/api/health"; then break; fi
  sleep 1
done
curl -s "http://127.0.0.1:$PORT/api/health" || { echo "server did not start"; exit 1; }
echo

echo "== smoke test =="
BASE="http://127.0.0.1:$PORT" SMOKE_DIR="$SMOKE_DIR" bash "$SERVER_DIR/tests/smoke.sh"
STATUS=$?

echo
echo "server log: $SMOKE_DIR/server.log"
exit $STATUS
