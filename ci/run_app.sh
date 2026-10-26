#!/usr/bin/env bash
# Run ADGE on one app on an emulator that is already booted: install it, push the fixtures, start frida-server and the
# uiautomator2 agent, then run ADGE again and again for N minutes (as ADGE's run.bat does) while dumping JaCoCo coverage
# every DUMP_INTERVAL seconds; finally merge the dumps and write the reports.
#
#   bash ci/run_app.sh <App> <minutes> <outdir>
#
# Needs adb, java and python3 on PATH, ADGE built in adge/target (mvn package), the JaCoCo CLI (ci/get_jacoco.sh puts it
# in .cache/jacococli.jar), the database from ci/build_db.sh in <outdir>/db/<App>.db, FRIDA_SERVER (frida-server binary
# for the emulator's ABI) and PY (a python with frida 16 and uiautomator2).
# Install, fixtures, coverage dumps and reports are the same as in the Monkey/APE baseline harness (work-emulator).
# Test knobs, not used in CI: RUN_SECONDS (replaces minutes*60), DUMP_INTERVAL (30), ADB (adb), JAVA (java), JACOCO.
set -u -o pipefail

APP=${1:?usage: run_app.sh <App> <minutes> <outdir>}
MINUTES=${2:-60}
OUT=${3:-out/$APP}

HERE=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
ROOT=$(dirname "$HERE")
mkdir -p "$OUT"
OUT=$(cd "$OUT" && pwd)
cd "$ROOT" || exit 2

ADB=${ADB:-adb}
JAVA=${JAVA:-java}
PY=${PY:-python3}
FRIDA_SERVER=${FRIDA_SERVER:-}
JACOCO=${JACOCO:-$ROOT/.cache/jacococli.jar}
INTERVAL=${DUMP_INTERVAL:-30}
RUN_SECONDS=${RUN_SECONDS:-$((MINUTES * 60))}
ADGE_DIR=$ROOT/adge
ADGE_CP="target/classes:target/lib/*:dependency/*"
STATUS=ok
STARTED=''
ENDED=''
APP_ALIVE=''
ITERATIONS=0
COMPLETED_RUNS=0
HOME_PKG=''

log() { printf '[%s] %s\n' "$(date -u +%H:%M:%S)" "$*" | tee -a "$OUT/run.log"; }
group() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::group::$*"; fi; }
endgroup() { if [ -n "${GITHUB_ACTIONS:-}" ]; then echo "::endgroup::"; fi; }
prop() { $ADB shell getprop "$1" 2>/dev/null | tr -d '\r'; }

if ! command -v "$ADB" >/dev/null 2>&1; then
  for d in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" /usr/local/lib/android/sdk; do
    if [ -n "$d" ] && [ -x "$d/platform-tools/adb" ]; then export PATH="$d/platform-tools:$PATH"; break; fi
  done
fi

eval "$(python3 -I ci/app_env.py "$APP")" || { echo "unknown app $APP"; exit 2; }
DEVICE_EC=/data/data/$PKG/files/coverage.ec
DB=$OUT/db/$APP.db
mkdir -p "$OUT/adge/logs"
: > "$OUT/segments.csv"
mkdir -p "$OUT/segments"

# One coverage dump. The receiver writes only what ran since the previous dump (it resets its counters), so no dump may be lost.
dump_once() {
  local idx f t reply bytes magic valid
  idx=$(wc -l < "$OUT/segments.csv" | tr -d ' ')
  f="$OUT/segments/$(printf '%05d' "$idx").ec"
  t=$(date -u +%Y-%m-%dT%H:%M:%SZ)
  reply=$($ADB shell am broadcast -a edu.gatech.m3.emma.COLLECT_COVERAGE -n "$PKG/$RECEIVER" 2>&1 | tr -d '\r' | tr '\n,' '  ')
  rm -f "$f"
  if ! $ADB pull "$DEVICE_EC" "$f" >/dev/null 2>&1; then
    $ADB exec-out run-as "$PKG" cat files/coverage.ec > "$f" 2>/dev/null || rm -f "$f"
  fi
  $ADB shell rm -f "$DEVICE_EC" >/dev/null 2>&1
  bytes=0
  valid=false
  if [ -f "$f" ]; then
    bytes=$(wc -c < "$f" | tr -d ' ')
    magic=$(head -c 3 "$f" | od -An -tx1 | tr -d ' \n')
    if [ "$bytes" -gt 5 ] && [ "$magic" = 01c0c0 ]; then valid=true; fi
  fi
  printf '%s,%s,%s,%s,%s\n' "$idx" "$t" "$bytes" "$valid" "$reply" >> "$OUT/segments.csv"
}

dump_loop() {
  local n
  while true; do
    n=0
    while [ "$n" -lt "$INTERVAL" ] && [ ! -e "$OUT/.stop" ]; do sleep 1; n=$((n + 1)); done
    if [ -e "$OUT/.stop" ]; then break; fi
    dump_once
  done
}

finalize() {
  group "merge and report"
  local valid_files=() cls=() idx valid actions
  while IFS=, read -r idx _ _ valid _; do
    if [ "$valid" = true ]; then valid_files+=("$OUT/segments/$(printf '%05d' "$idx").ec"); fi
  done < "$OUT/segments.csv"
  for c in $CLASSES; do cls+=(--classfiles "$ROOT/$c"); done
  if [ "${#valid_files[@]}" -eq 0 ]; then
    [ "$STATUS" = ok ] && STATUS=no_coverage
  elif [ ! -f "$JACOCO" ]; then
    STATUS=no_jacoco
  elif ! { "$JAVA" -jar "$JACOCO" merge "${valid_files[@]}" --destfile "$OUT/merged.ec" \
           && "$JAVA" -jar "$JACOCO" report "$OUT/merged.ec" "${cls[@]}" --xml "$OUT/report.xml" --csv "$OUT/report.csv"; } >"$OUT/jacoco.log" 2>&1; then
    STATUS=report_failed
  fi
  [ -f "$OUT/logcat_crash.txt" ] && head -c 5000000 "$OUT/logcat_crash.txt" > "$OUT/logcat_crash.tmp" && mv "$OUT/logcat_crash.tmp" "$OUT/logcat_crash.txt"
  # ADGE's own output: results/<package>/result.json (activity coverage, time to reach each activity) and path-*.txt
  if [ -d "$ADGE_DIR/results" ]; then cp -R "$ADGE_DIR/results" "$OUT/adge/"; fi
  cp "$ADGE_DIR"/log4j.log* "$OUT/adge/logs/" 2>/dev/null
  cp "$ADGE_DIR/configures/config.json" "$OUT/adge/config.json" 2>/dev/null
  cp "$ADGE_DIR/frida-hook/hook.js" "$OUT/adge/hook.js" 2>/dev/null
  actions=$(cat "$OUT"/adge/logs/*-adge.log 2>/dev/null | grep -c 'perform action:')
  python3 -I ci/report.py "$OUT" "app=$APP" "package=$PKG" "tool=adge" "minutes=$MINUTES" "run_seconds=$RUN_SECONDS" \
    "status=$STATUS" "started=$STARTED" "ended=$ENDED" "app_alive_at_end=$APP_ALIVE" "iterations=$ITERATIONS" \
    "completed_runs=$COMPLETED_RUNS" "actions=$actions" "home_package=$HOME_PKG" \
    "sdk=${SDK:-}" "abi=${ABI:-}" "fingerprint=${FINGERPRINT:-}" "screen=${SCREEN:-}" "root=${IS_ROOT:-}" \
    "github_run_id=${GITHUB_RUN_ID:-}" "github_sha=${GITHUB_SHA:-}" >> "$OUT/run.log" 2>&1
  gzip -f "$OUT/report.xml" 2>/dev/null
  if [ -d "$OUT/segments" ]; then tar -czf "$OUT/segments.tar.gz" -C "$OUT" segments && rm -rf "$OUT/segments"; fi
  if [ -d "$OUT/adge/logs" ]; then tar -czf "$OUT/adge/logs.tar.gz" -C "$OUT/adge" logs && rm -rf "$OUT/adge/logs"; fi
  rm -f "$OUT/.stop"
  endgroup
  log "result: $APP status=$STATUS $(grep -E '"(methods_hit|methods_total|line_pct|activities_triggered|activities_total|iterations|actions)"' "$OUT/meta.json" 2>/dev/null | tr -d ' \n')"
  if [ "$STATUS" = ok ]; then
    echo "::notice title=$APP::$(tr -d '\n ' < "$OUT/meta.json" | cut -c1-900)"
    return 0
  fi
  echo "::error title=$APP::run finished with status $STATUS $(tr -d '\n ' < "$OUT/meta.json" 2>/dev/null | cut -c1-600)"
  return 1
}

# ---------------------------------------------------------------- device
group "device"
$ADB wait-for-device
for _ in $(seq 60); do [ "$(prop sys.boot_completed)" = 1 ] && break; sleep 2; done
$ADB root >/dev/null 2>&1 || true
$ADB wait-for-device
for _ in $(seq 30); do [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && break; sleep 1; done
IS_ROOT=$([ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && echo yes || echo no)
SDK=$(prop ro.build.version.sdk)
ABI=$(prop ro.product.cpu.abi)
FINGERPRINT=$(prop ro.build.fingerprint)
SCREEN="$($ADB shell wm size 2>/dev/null | tr -d '\r' | tail -1) $($ADB shell wm density 2>/dev/null | tr -d '\r' | tail -1)"
log "device: sdk=$SDK abi=$ABI root=$IS_ROOT screen=[$SCREEN] fingerprint=$FINGERPRINT"
$ADB shell svc power stayon true >/dev/null 2>&1 || true
$ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
$ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true
# the home screen package: ADGE ends a run when the app is left and the home screen shows
HOME_PKG=$($ADB shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null \
  | tr -d '\r' | tail -n 1 | cut -d/ -f1)
case "$HOME_PKG" in *.*) ;; *) HOME_PKG=com.android.launcher3 ;; esac
log "home screen: $HOME_PKG"
endgroup

# ---------------------------------------------------------------- install and fixtures
group "install and fixtures"
reply=$($ADB install -r -g -t "$APK" 2>&1 | tr -d '\r')
log "install $APK: $(echo "$reply" | tail -1)"
if ! echo "$reply" | grep -q '^Success'; then
  STATUS=install_failed
  finalize
  exit 3
fi
for d in Download Music Pictures Documents; do
  if [ -d "fixtures/$d" ]; then $ADB push "fixtures/$d" /sdcard/ >/dev/null 2>&1 || log "push of fixtures/$d failed"; fi
done
for f in fixtures/Music/* fixtures/Pictures/*; do
  [ -f "$f" ] || continue
  $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file:///sdcard/$(basename "$(dirname "$f")")/$(basename "$f")" >/dev/null 2>&1
done
endgroup

# ---------------------------------------------------------------- ADGE helpers: frida-server, uiautomator2, config
group "frida-server, uiautomator2 and ADGE config"
if [ -z "$FRIDA_SERVER" ] || [ ! -f "$FRIDA_SERVER" ]; then
  log "FRIDA_SERVER is not set or missing"
  STATUS=no_frida_server
  finalize
  exit 2
fi
$ADB push "$FRIDA_SERVER" /data/local/tmp/frida-server >/dev/null 2>&1
$ADB shell chmod 755 /data/local/tmp/frida-server
# as ADGE's frida-hook/run_frida_server.py: SELinux permissive, then frida-server as root (-D: run as a daemon)
$ADB shell setenforce 0 >/dev/null 2>&1 || true
timeout 30 $ADB shell "/data/local/tmp/frida-server -D" >/dev/null 2>&1 || true
sleep 3
if ! "$PY" - >> "$OUT/run.log" 2>&1 <<'EOF'
import frida
d = frida.get_usb_device(timeout=30)
print("frida %s: %d processes visible on %s" % (frida.__version__, len(d.enumerate_processes()), d.name))
EOF
then
  log "frida-server does not answer"
  STATUS=frida_failed
  finalize
  exit 2
fi
if ! "$PY" -c 'import uiautomator2 as u2; d = u2.connect(); print("uiautomator2:", d.info)' >> "$OUT/run.log" 2>&1; then
  log "uiautomator2 agent did not start"
  STATUS=uiautomator2_failed
  finalize
  exit 2
fi
if [ ! -s "$DB" ]; then
  log "no database from ci/build_db.sh; using an empty one"
  mkdir -p "$OUT/db"
  (cd "$OUT/db" && "$JAVA" -cp "$ADGE_DIR/target/classes:$ADGE_DIR/target/lib/*:$ADGE_DIR/dependency/*" BuildDB --empty "$DB" "$OUT/db/stats.json") >/dev/null 2>&1
fi
mkdir -p "$ADGE_DIR/configures"
rm -rf "$ADGE_DIR/results" "$ADGE_DIR/window_dump.xml" "$ADGE_DIR"/log4j.log*
CFG_DB=$DB CFG_APK=$ROOT/$APK CFG_PKG=$PKG CFG_HOME=$HOME_PKG \
CFG_PLATFORMS=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}/platforms \
python3 - "$ADGE_DIR/configures/config.json" <<'EOF'
import json, os, sys
config = {
    "DB_PATH": os.environ["CFG_DB"],
    "APK_PATH": os.environ["CFG_APK"],
    "PACKAGE_NAME": os.environ["CFG_PKG"],
    "SLEEP_TIME": 1000,
    "defaultPlatformDir": os.environ["CFG_PLATFORMS"],
    "SKIP_INSTALL": True,
    "HOME_PACKAGE": os.environ["CFG_HOME"],
}
with open(sys.argv[1], "w") as f:
    json.dump(config, f, indent=1)
EOF
log "ADGE config: $(tr -d '\n' < "$ADGE_DIR/configures/config.json")"
endgroup

# ---------------------------------------------------------------- run
$ADB logcat -c >/dev/null 2>&1 || true
$ADB logcat -b crash -v time > "$OUT/logcat_crash.txt" 2>&1 &
LOGCAT_PID=$!
dump_once
dump_loop &
LOOP_PID=$!
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
T0=$(date -u +%s)
LAST_BEAT=$T0
FAST=0
echo "iteration,exit_code,seconds" > "$OUT/adge/iterations.csv"
while true; do
  NOW=$(date -u +%s)
  LEFT=$((RUN_SECONDS - (NOW - T0)))
  if [ "$LEFT" -le 15 ]; then break; fi
  ITERATIONS=$((ITERATIONS + 1))
  i=$(printf '%03d' "$ITERATIONS")
  # the three processes of one ADGE run, started as run.bat starts them
  ( cd "$ADGE_DIR/uiautomator2" && exec "$PY" -u run.py ) > "$OUT/adge/logs/$i-uiautomator2.log" 2>&1 &
  U2_PID=$!
  ( cd "$ADGE_DIR/frida-hook" && exec "$PY" -u frida_wrapper.py ) > "$OUT/adge/logs/$i-frida.log" 2>&1 &
  FRIDA_PID=$!
  sleep 2
  ( cd "$ADGE_DIR" && exec timeout -s TERM -k 30 "$LEFT" "$JAVA" -Xmx2g -cp "$ADGE_CP" Main ) > "$OUT/adge/logs/$i-adge.log" 2>&1
  RC=$?
  kill "$FRIDA_PID" "$U2_PID" 2>/dev/null
  wait "$FRIDA_PID" "$U2_PID" 2>/dev/null
  SPENT=$(($(date -u +%s) - NOW))
  echo "$ITERATIONS,$RC,$SPENT" >> "$OUT/adge/iterations.csv"
  if [ "$RC" -eq 0 ]; then COMPLETED_RUNS=$((COMPLETED_RUNS + 1)); fi
  log "ADGE run $ITERATIONS: exit code $RC after $SPENT s ($((RUN_SECONDS - ($(date -u +%s) - T0))) s left)"
  if [ "$SPENT" -lt 30 ]; then FAST=$((FAST + 1)); sleep 5; else FAST=0; fi
  if [ "$FAST" -ge 10 ]; then
    log "ADGE ended within seconds 10 times in a row; stopping (see adge/logs.tar.gz)"
    STATUS=tool_failing
    break
  fi
  NOW=$(date -u +%s)
  if [ $((NOW - LAST_BEAT)) -ge 300 ]; then
    LAST_BEAT=$NOW
    log "running for $((NOW - T0)) s, $(wc -l < "$OUT/segments.csv" | tr -d ' ') coverage dumps so far"
  fi
done
ENDED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
ELAPSED=$(( $(date -u +%s) - T0 ))
log "ADGE runs finished after $ELAPSED s: $ITERATIONS run(s), $COMPLETED_RUNS ended by themselves"
APP_ALIVE=$([ -n "$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && echo yes || echo no)
touch "$OUT/.stop"
wait "$LOOP_PID" 2>/dev/null
dump_once
kill "$LOGCAT_PID" 2>/dev/null
wait "$LOGCAT_PID" 2>/dev/null

finalize
exit $?
