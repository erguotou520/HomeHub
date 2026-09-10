#!/usr/bin/env bash
# HomeHub end-to-end smoke test.
#
# Creates its own fixtures, starts nothing itself: point it at a running
# server (see `run.sh` below) and it exercises the admin + data-plane API.
#
#   BASE=http://127.0.0.1:8485 bash server/tests/smoke.sh
#
# Environment:
#   BASE       server base URL            (default http://127.0.0.1:8485)
#   PASS       admin password             (default smoke-pass-9137)
#   SMOKE_DIR  fixture working directory  (default /tmp/homehub-smoke)

set -uo pipefail

BASE=${BASE:-http://127.0.0.1:8485}
PASS=${PASS:-smoke-pass-9137}
SMOKE_DIR=${SMOKE_DIR:-/tmp/homehub-smoke}
FAILURES=0

check() { # name expected_substring actual
  if echo "$3" | grep -qF "$2"; then echo "  ok   $1"; else echo "  FAIL $1 (want '$2') got: $3"; FAILURES=$((FAILURES + 1)); fi
}
short() { python3 -c "import sys,json;d=json.load(sys.stdin);print(json.dumps(d,ensure_ascii=False)[:1500])" 2>/dev/null || echo "<non-json>"; }

# ─────────────────────────── fixtures ────────────────────────────

if [ ! -d "$SMOKE_DIR/photos" ]; then
  echo "== generating fixtures in $SMOKE_DIR =="
  mkdir -p "$SMOKE_DIR/photos/2024/05" "$SMOKE_DIR/photos/2025/01" "$SMOKE_DIR/docs"
  python3 - "$SMOKE_DIR" <<'PY'
import sys, zlib, struct, shutil, os
root = sys.argv[1]

def png(path, w, h, fn):
    raw = bytearray()
    for y in range(h):
        raw.append(0)
        for x in range(w):
            raw += bytes(fn(x, y))
    def chunk(t, d):
        return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    ihdr = struct.pack('>IIBBBBB', w, h, 8, 2, 0, 0, 0)
    open(path, 'wb').write(
        b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', ihdr) +
        chunk(b'IDAT', zlib.compress(bytes(raw), 6)) + chunk(b'IEND', b''))

def grad(a):
    return lambda x, y: ((x * 4 + a) % 256, (y * 4) % 256, ((x + y) * 2) % 256)

for i, (n, (w, h)) in enumerate([('a.png', (200, 150)), ('b.png', (320, 240)), ('c.png', (160, 160))]):
    png(os.path.join(root, 'photos/2024/05', n), w, h, grad(i * 40))
for i, (n, (w, h)) in enumerate([('d.png', (400, 200)), ('e.png', (256, 256))]):
    png(os.path.join(root, 'photos/2025/01', n), w, h, grad(120 + i * 30))
shutil.copy(os.path.join(root, 'photos/2024/05/a.png'),
            os.path.join(root, 'photos/2025/01/a-copy.png'))
png(os.path.join(root, 'photos/2024/05/flat.png'), 300, 300, lambda x, y: (10, 20, 30))
open(os.path.join(root, 'docs/readme.txt'), 'w').write('hello homehub')
print('  fixtures ready')
PY
fi

# Use a per-run tag so leftovers from an aborted run never collide.
TAG="smoke-$$"
rm -rf "$SMOKE_DIR"/photos/2024/05/smoke-* "$SMOKE_DIR"/photos/2024/05/.hh-upload.png \
       "$SMOKE_DIR"/photos/2024/05/chunked-smoke.png

echo "== health =="
check "health endpoint" '"status":"ok"' "$(curl -s "$BASE/api/health")"

echo "== admin login =="
TOKEN=$(curl -s -X POST "$BASE/api/admin/login" -H 'content-type: application/json' \
  -d "{\"password\":\"$PASS\"}" | python3 -c "import sys,json;print(json.load(sys.stdin).get('token',''))")
[ -n "$TOKEN" ] && echo "  ok   token acquired" || { echo "  FAIL login"; FAILURES=$((FAILURES + 1)); }
AUTH="Authorization: Bearer $TOKEN"

R=$(curl -s -X POST "$BASE/api/admin/login" -H 'content-type: application/json' -d '{"password":"nope"}')
check "rejects wrong password" "invalid password" "$R"

# Refresh the index first: a previous run may have deleted fixtures.
curl -s -X POST "$BASE/api/admin/tasks/rescan" -H "$AUTH" -H 'content-type: application/json' \
  -d '{"full": true}' > /dev/null
sleep 4

echo "== directory registry =="
R=$(curl -s -H "$AUTH" "$BASE/api/admin/dirs" | short); check "lists dirs" "photos" "$R"

echo "== task centre =="
R=$(curl -s -H "$AUTH" "$BASE/api/admin/tasks" | short); check "queue status" "queues" "$R"

echo "== stats =="
R=$(curl -s -H "$AUTH" "$BASE/api/admin/stats" | short); check "stats" "photos" "$R"

echo "== photo views =="
R=$(curl -s "$BASE/api/photos/timeline?group=month" | short); check "timeline" "groups" "$R"
R=$(curl -s "$BASE/api/photos/tree" | short);              check "tree" "groups" "$R"
R=$(curl -s "$BASE/api/photos/tags" | short);              check "tags" "tags" "$R"
R=$(curl -s "$BASE/api/photos/people" | short);            check "people" "people" "$R"
R=$(curl -s "$BASE/api/photos/geo" | short);               check "geo" "points" "$R"
R=$(curl -s "$BASE/api/photos/list?limit=3" | short);      check "list" "items" "$R"

echo "== thumbnails =="
THUMB_OK=0
for THUMB in $(curl -s "$BASE/api/photos/list?limit=10" |
  python3 -c "import sys,json;d=json.load(sys.stdin);print('\\n'.join(i['thumb_url'] for i in d.get('items',[])))"); do
  CODE=$(curl -s -o "$SMOKE_DIR/.thumb.jpg" -w '%{http_code}' "$BASE$THUMB")
  SIZE=$(stat -f%z "$SMOKE_DIR/.thumb.jpg" 2>/dev/null || stat -c%s "$SMOKE_DIR/.thumb.jpg" 2>/dev/null || echo 0)
  if [ "$CODE" = "200" ] && [ "$SIZE" -gt 100 ]; then echo "  ok   thumbnail (${SIZE} B)"; THUMB_OK=1; break; fi
done
[ "$THUMB_OK" = "1" ] || { echo "  FAIL thumbnail"; FAILURES=$((FAILURES + 1)); }

echo "== search (FTS5) =="
R=$(curl -s "$BASE/api/search?q=a.png" | short); check "search finds photo" "hits" "$R"
R=$(curl -s "$BASE/api/search?q=readme" | short); check "search finds document" "readme" "$R"

echo "== dedup =="
R=$(curl -s "$BASE/api/duplicates" | short); check "duplicate groups" "groups" "$R"

echo "== NAS files =="
R=$(curl -s "$BASE/api/dirs" | short);                  check "dir list" "photos" "$R"
R=$(curl -s "$BASE/api/files/photos/2024/05" | short);  check "file list" "entries" "$R"
R=$(curl -s "$BASE/api/files/docs/readme.txt");         check "file download" "hello homehub" "$R"
R=$(curl -s -X POST "$BASE/api/mkdir/photos/2024/05" -H 'content-type: application/json' -d "{\"name\":\"$TAG\"}")
check "mkdir" "success" "$R"
R=$(curl -s -X PATCH "$BASE/api/files/photos/2024/05/$TAG" -H 'content-type: application/json' -d "{\"name\":\"${TAG}-2\"}")
check "rename" "success" "$R"

echo "== upload (multipart) =="
R=$(curl -s -X POST "$BASE/api/files/photos/2024/05" -F "file=@$SMOKE_DIR/photos/2024/05/a.png" | short)
check "upload returns payload" "uploaded" "$R"
echo "$R" | grep -q "duplicate_of" && echo "  ok   dedup field present"

echo "== upload duplicate skip =="
R=$(curl -s -X POST "$BASE/api/files/photos/2024/05?on-duplicate=skip" -F "file=@$SMOKE_DIR/photos/2024/05/a.png")
echo "$R" | grep -q '"skipped":true' && echo "  ok   duplicate skipped" || { echo "  FAIL duplicate skip: $R"; FAILURES=$((FAILURES + 1)); }

echo "== resumable chunked upload =="
python3 - "$BASE" "$SMOKE_DIR" <<'PY'
import sys, urllib.request, urllib.parse, json
B, root = sys.argv[1], sys.argv[2]
data = open(f"{root}/photos/2025/01/e.png", "rb").read()
name, size, CH = "chunked-smoke.png", len(data), 700
q = urllib.parse.urlencode({"dir": "photos", "path": "2024/05", "name": name})

def get(u): return urllib.request.urlopen(u).read().decode()
def post(u, b):
    r = urllib.request.Request(u, data=b, headers={"content-type": "application/octet-stream"}, method="POST")
    return urllib.request.urlopen(r).read().decode()

post(f"{B}/api/upload/chunk?{q}&offset=0&total={size}", data[:CH])           # interrupted after 1 chunk
off = json.loads(get(f"{B}/api/upload/offset?{q}"))["offset"]
assert off == CH, f"expected a partial upload of {CH} bytes, got {off}"
while off < size:                                                            # resume
    end = min(off + CH, size)
    post(f"{B}/api/upload/chunk?{q}&offset={off}&total={size}", data[off:end])
    off = end
r = urllib.request.Request(f"{B}/api/upload/complete",
    data=json.dumps({"dir": "photos", "path": "2024/05", "name": name, "total": size}).encode(),
    headers={"content-type": "application/json"}, method="POST")
res = json.loads(urllib.request.urlopen(r).read())
assert res["uploaded"][0]["size"] == size, res
print("  ok   chunked upload + resume (dedup:", res["uploaded"][0]["duplicate_of"], ")")
PY
[ $? -ne 0 ] && FAILURES=$((FAILURES + 1))

rm -f "$SMOKE_DIR/photos/2024/05/chunked-smoke.png"
curl -s -X POST "$BASE/api/admin/tasks/rescan" -H "$AUTH" -H 'content-type: application/json' \
  -d '{"full": false}' > /dev/null
sleep 3

echo "== soft delete + trash =="
R=$(curl -s -X DELETE "$BASE/api/files/photos/2024/05/${TAG}-2" | short); check "delete" "trash_id" "$R"
R=$(curl -s "$BASE/api/trash" | short);                                 check "trash list" "entries" "$R"
TRASH_ID=$(curl -s "$BASE/api/trash" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['entries'][0]['id'] if d['entries'] else '')")
if [ -n "$TRASH_ID" ]; then
  R=$(curl -s -X POST "$BASE/api/trash/$TRASH_ID/restore" | short); check "restore" "success" "$R"
fi

echo "== rotation (destructive, archives .originals) =="
PID=$(curl -s "$BASE/api/photos/list?limit=1" | python3 -c "import sys,json;d=json.load(sys.stdin);print(d['items'][0]['id'] if d['items'] else '')")
if [ -n "$PID" ]; then
  R=$(curl -s -X POST "$BASE/api/photos/$PID/rotate" -H 'content-type: application/json' -d '{"angle":90}' | short)
  check "rotate 90" "photo" "$R"
fi

echo "== edit publishes geometry immediately =="
# after_edit() used to only enqueue the async scan, which lands ~200-400ms later.
# A client that refetches right after an edit therefore still saw the pre-edit
# width/height (stale info panel + thumbnail). This asserts the new geometry is
# readable with NO sleep/poll in between: a 90° rotate must swap the dimensions.
dim_of() { # $1 = file name -> "WxH" from the directory tree view
  curl -s "$BASE/api/photos/tree" | python3 -c "
import sys, json
name = sys.argv[1]
d = json.load(sys.stdin)
groups = d if isinstance(d, list) else d.get('groups', d)
for g in groups:
    if g.get('dir_name') == 'photos':
        for it in g.get('items', []):
            if it.get('name') == name:
                print('%sx%s' % (it.get('width'), it.get('height')))
" "$1"
}
DIM_BEFORE=$(dim_of d.png)
curl -s -X POST "$BASE/api/images/transform/photos/2025/01/d.png" \
  -H 'content-type: application/json' -d '{"ops":[{"op":"rotate","angle":90}]}' >/dev/null
DIM_AFTER=$(dim_of d.png)
check "transform publishes new size without waiting for the scan" \
  "$(echo "$DIM_BEFORE" | awk -F'x' '{print $2"x"$1}')" "$DIM_AFTER"

echo "== audit =="
sleep 2
R=$(curl -s -H "$AUTH" "$BASE/api/admin/traffic" | short); check "traffic by peer" "traffic" "$R"
R=$(curl -s -H "$AUTH" "$BASE/api/admin/peers" | short);   check "peer list" "peers" "$R"

echo "== settings round-trip =="
R=$(curl -s -H "$AUTH" "$BASE/api/admin/settings" |
  python3 -c "import sys,json;d=json.load(sys.stdin);print('tasks' if 'tasks' in d else str(d)[:200])")
check "settings read" "tasks" "$R"
curl -s -X PUT "$BASE/api/admin/settings" -H "$AUTH" -H 'content-type: application/json' \
  -d "$(curl -s -H "$AUTH" "$BASE/api/admin/settings" |
        python3 -c "import sys,json;d=json.load(sys.stdin);d['tasks']['rate-limit-per-sec']=33;print(json.dumps(d))")" \
  | grep -q success && echo "  ok   settings write" || { echo "  FAIL settings write"; FAILURES=$((FAILURES + 1)); }
R=$(curl -s -H "$AUTH" "$BASE/api/admin/settings" |
  python3 -c "import sys,json;print(json.load(sys.stdin)['tasks']['rate-limit-per-sec'])")
check "settings persisted" "33" "$R"

echo "== geo cluster drill-down (map views) =="
GEO=$(curl -s "$BASE/api/photos/geo?precision=0.02" | python3 -c "
import sys, json
d = json.load(sys.stdin)
pts = d.get('points') or []
print(','.join(str(i) for p in pts for i in p.get('photo_ids', []))[:200])
")
if [ -n "$GEO" ]; then
  R=$(curl -s "$BASE/api/photos/list?ids=$GEO" | python3 -c "
import sys, json
d = json.load(sys.stdin)
ids = {str(i) for i in '$GEO'.split(',')}
got = {str(p['id']) for p in d['items']}
print('matched' if got and got <= ids else f'unexpected {got - ids}')
")
  check "list by geo ids" "matched" "$R"
else
  echo "  skip geo drill-down (no GPS fixtures)"
fi

echo "== sqlite backup =="
R=$(curl -s -X POST -H "$AUTH" "$BASE/api/admin/backups/run" | short)
check "backup run" "name" "$R"
R=$(curl -s -H "$AUTH" "$BASE/api/admin/backups" | short)
check "backup list" "dir" "$R"
R=$(curl -s -H "$AUTH" "$BASE/api/admin/backups" |
  python3 -c "import sys,json;n=json.load(sys.stdin);print('backup-ok' if n['items'] and n['items'][0]['name'].startswith('homehub-') else str(n)[:200])")
check "backup file named" "backup-ok" "$R"
R=$(curl -s -H "$AUTH" "$BASE/api/admin/system" |
  python3 -c "import sys,json;d=json.load(sys.stdin);b=d.get('backup') or {};print('has-backup' if b.get('count',0)>=1 and b.get('last_backup_at') else str(b)[:200])")
check "system reports backup" "has-backup" "$R"
curl -s -X POST -H "$AUTH" -H 'content-type: application/json' -d '{"kind":"backup"}' \
  "$BASE/api/admin/tasks/run" | grep -q backup && echo "  ok   backup task kind" || { echo "  FAIL backup task kind"; FAILURES=$((FAILURES + 1)); }

echo "== monitoring placeholder =="
R=$(curl -s "$BASE/api/monitor/config" | short); check "monitor config" "implemented" "$R"

echo
if [ "$FAILURES" -eq 0 ]; then echo "ALL SMOKE CHECKS PASSED"; else echo "$FAILURES CHECK(S) FAILED"; fi
exit $FAILURES
