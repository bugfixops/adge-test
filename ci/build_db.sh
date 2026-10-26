#!/usr/bin/env bash
# Build the static-analysis database ADGE reads (tables ResourceIdToMethodMap and Methods) for one app, before the
# emulator starts. ADGE's repository does not ship the tool that made its databases; adge/src/BuildDB.java rebuilds them
# with ADGE's own GUI analysis (see the comment there). MODE=empty writes the schema only (an unguided run).
#
#   bash ci/build_db.sh <App> <analysis|empty> <outdir>
#
# Writes <outdir>/db/<App>.db, <outdir>/db/stats.json and <outdir>/db/builddb.log.gz.
set -u -o pipefail

APP=${1:?usage: build_db.sh <App> <analysis|empty> <outdir>}
MODE=${2:-analysis}
OUT=${3:-out/$APP}

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$HERE")
mkdir -p "$OUT/db"
OUT=$(cd "$OUT" && pwd)
cd "$ROOT" || exit 2

eval "$(python3 -I ci/app_env.py "$APP")" || { echo "unknown app $APP"; exit 2; }
JAVA=${JAVA:-java}
CP="$ROOT/adge/target/classes:$ROOT/adge/target/lib/*:$ROOT/adge/dependency/*"
PLATFORMS=${ANDROID_PLATFORMS:-${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platforms}
DB="$OUT/db/$APP.db"
LOG="$OUT/db/builddb.log"
: > "$LOG"

if [ "$MODE" = analysis ]; then
  echo "platforms: $(ls "$PLATFORMS" 2>/dev/null | tr '\n' ' ')" | tee -a "$LOG"
  (cd "$OUT/db" && timeout -k 30 1200 "$JAVA" -Xmx8g -Xss8m -cp "$CP" BuildDB "$ROOT/$APK" "$DB" "$PLATFORMS" "$OUT/db/stats.json" 900) >> "$LOG" 2>&1
  rc=$?
  echo "BuildDB exit code $rc"
  if [ "$rc" -ne 0 ] || [ ! -s "$DB" ]; then
    echo "::warning title=$APP::static analysis failed (exit $rc); ADGE runs with an empty database"
    MODE=empty_after_failure
  fi
fi
if [ "$MODE" != analysis ]; then
  (cd "$OUT/db" && "$JAVA" -cp "$CP" BuildDB --empty "$DB" "$OUT/db/stats.json") >> "$LOG" 2>&1 || { echo "cannot create $DB"; exit 1; }
  python3 - "$OUT/db/stats.json" "$MODE" <<'EOF'
import json, sys
path, mode = sys.argv[1], sys.argv[2]
stats = json.load(open(path))
stats['mode'] = mode
json.dump(stats, open(path, 'w'), indent=2)
EOF
fi
rm -f "$OUT"/db/log4j.log*
gzip -f "$LOG"
echo "database for $APP: $(tr -d '\n ' < "$OUT/db/stats.json")"
