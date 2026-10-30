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
# PREP=on: apps with a first-run setup script in ci/prep_scripts.py (Aegis, MMEX, Markor) are set up before the run
# (ci/run_prep.py), as the Monkey baseline did for Aegis and MMEX. The setup runs before the first coverage dump, so its
# coverage is all in snapshot 0, which the comparison with Monkey leaves out (Monkey's merged.ec leaves out its setup).
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
# emulator restarts (see restart_emulator): the emulator is restarted when its process passes this size
EMU_RSS_LIMIT_MB=${EMU_RSS_LIMIT_MB:-9000}
EMU_RESTARTS=0
RESTART_SECONDS=0
PREP=${PREP:-off}
PREP_DONE=no

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
  # The 30-second dump loop and the main loop (before each ADGE restart) both call this. Without a lock, two dumps at
  # the same moment take the same index and the second file overwrites the first, losing that coverage segment.
  # mkdir is atomic, so it serves as the lock; the subshell releases it even if a command inside fails.
  local w=0
  while ! mkdir "$OUT/.dump.lock" 2>/dev/null; do
    sleep 0.2
    w=$((w + 1))
    if [ "$w" -ge 600 ]; then
      log "dump lock held for 120 s; taking it over"
      rm -rf "$OUT/.dump.lock"
    fi
  done
  ( dump_once_locked )
  rmdir "$OUT/.dump.lock" 2>/dev/null
}

dump_once_locked() {
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

# Resource monitor. In bugfixops/adge-test the runner was shut down twice at ~95 min (restart ~177) with nothing in the
# log, so disk and memory use are printed every 2 minutes; the job log survives a runner shutdown, the artifact does not.
qemu_rss_mb() {
  ps -eo rss,comm --no-headers | awk '$2 ~ /^qemu-system/ {s += $1} END {print int(s / 1024)}'
}

# frida-server as root, as ADGE's frida-hook/run_frida_server.py does: SELinux permissive, then the server (-D: daemon)
start_frida() {
  $ADB push "$FRIDA_SERVER" /data/local/tmp/frida-server >/dev/null 2>&1
  $ADB shell chmod 755 /data/local/tmp/frida-server
  $ADB shell setenforce 0 >/dev/null 2>&1 || true
  timeout 30 $ADB shell "/data/local/tmp/frida-server -D" >/dev/null 2>&1 || true
  sleep 3
  "$PY" - >> "$OUT/run.log" 2>&1 <<'EOF'
import frida
d = frida.get_usb_device(timeout=30)
print("frida %s: %d processes visible on %s" % (frida.__version__, len(d.enumerate_processes()), d.name))
EOF
}

# number of files in the app's data directory (the lib symlink is not followed)
app_files() {
  local n
  n=$($ADB shell "find /data/data/$PKG -type f 2>/dev/null | wc -l" 2>/dev/null | tr -cd '0-9')
  echo "${n:-0}"
}

# Restart the emulator to give back memory. The emulator process grows by about 90 MB per ADGE restart and is never
# freed (2.1 GB -> 13.9 GB in 70 min for MMEX); the hosted runner has 16 GB and was shut down at ~95 min.
# The emulator is cold-booted on the same AVD, whose data partition is kept, so the app stays installed with its data,
# including the first-run setup. The data is also backed up, and restored only if files are missing after the boot.
# Because the app stays installed, its BOOT_COMPLETED receivers run during the boot (MMEX has two), and a process they
# start can outlive the reinstall (a late broadcast, or an alarm or job they schedule). So the app is force-stopped at
# the end and the coverage file deleted: the coverage of that code only lives in the app's memory until a dump, so
# nothing from the restart is counted. A coverage dump before the shutdown keeps everything collected so far.
# Returns non-zero if the new emulator does not come up.
restart_emulator() {
  local t0 rss_before files_before files_after reply
  t0=$(date -u +%s)
  rss_before=$(qemu_rss_mb)
  log "emulator restart $((EMU_RESTARTS + 1)): emulator process at ${rss_before} MB"
  dump_once
  touch "$OUT/.restarting"
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
  files_before=$(app_files)
  rm -rf "$OUT/appdata"
  mkdir -p "$OUT/appdata"
  if $ADB pull "/data/data/$PKG" "$OUT/appdata/" >/dev/null 2>&1; then
    log "  app data backed up ($files_before files, $(du -sh "$OUT/appdata" | cut -f1))"
  else
    log "  app data backup failed"
  fi
  kill "$LOGCAT_PID" 2>/dev/null
  wait "$LOGCAT_PID" 2>/dev/null
  # write the guest's cached data to its disk before the emulator is killed
  timeout 60 $ADB shell sync >/dev/null 2>&1 || true
  $ADB emu kill >/dev/null 2>&1 || true
  for _ in $(seq 60); do pgrep -f 'qemu-system' >/dev/null || break; sleep 1; done
  pkill -9 -f 'qemu-system' 2>/dev/null || true
  sleep 2
  $ADB kill-server >/dev/null 2>&1 || true
  # same command as the workflow's emulator step (from android-emulator-runner)
  sh -c "$EMU_CMD" > "$OUT/emulator-restart-$((EMU_RESTARTS + 1)).log" 2>&1 &
  $ADB start-server >/dev/null 2>&1
  timeout 300 $ADB wait-for-device || { log "  emulator did not come back"; return 1; }
  for _ in $(seq 150); do [ "$(prop sys.boot_completed)" = 1 ] && break; sleep 2; done
  if [ "$(prop sys.boot_completed)" != 1 ]; then log "  emulator did not finish booting"; return 1; fi
  $ADB root >/dev/null 2>&1 || true
  $ADB wait-for-device
  for _ in $(seq 30); do [ "$($ADB shell id -u 2>/dev/null | tr -d '\r')" = 0 ] && break; sleep 1; done
  # what the emulator runner does after boot: animations off
  $ADB shell settings put global window_animation_scale 0.0 >/dev/null 2>&1
  $ADB shell settings put global transition_animation_scale 0.0 >/dev/null 2>&1
  $ADB shell settings put global animator_duration_scale 0.0 >/dev/null 2>&1
  # the harness's own device setup, as at the start of the run
  $ADB shell svc power stayon true >/dev/null 2>&1 || true
  $ADB shell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
  $ADB shell wm dismiss-keyguard >/dev/null 2>&1 || true
  reply=$($ADB install -r -g -t "$APK" 2>&1 | tr -d '\r')
  log "  reinstall: $(echo "$reply" | tail -1)"
  echo "$reply" | grep -q '^Success' || return 1
  for d in Download Music Pictures Documents; do
    if [ -d "fixtures/$d" ]; then $ADB push "fixtures/$d" /sdcard/ >/dev/null 2>&1; fi
  done
  for f in fixtures/Music/* fixtures/Pictures/*; do
    [ -f "$f" ] || continue
    $ADB shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
      -d "file:///sdcard/$(basename "$(dirname "$f")")/$(basename "$f")" >/dev/null 2>&1
  done
  files_after=$(app_files)
  if [ "$files_after" -lt "$files_before" ] && [ -d "$OUT/appdata/$PKG" ]; then
    local uid
    uid=$($ADB shell stat -c %u "/data/data/$PKG" 2>/dev/null | tr -d '\r')
    $ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
    $ADB shell rm -rf "/data/data/$PKG/*" >/dev/null 2>&1
    $ADB push "$OUT/appdata/$PKG/." "/data/data/$PKG/" >/dev/null 2>&1
    if [ -n "$uid" ]; then $ADB shell chown -R "$uid:$uid" "/data/data/$PKG" >/dev/null 2>&1; fi
    $ADB shell restorecon -R "/data/data/$PKG" >/dev/null 2>&1 || true
    log "  app data: $files_after of $files_before files left after the boot; restored from the backup (uid $uid, now $(app_files) files)"
  else
    log "  app data kept by the emulator ($files_after files, $files_before before the shutdown)"
  fi
  rm -rf "$OUT/appdata"
  start_frida || { log "  frida-server does not answer"; return 1; }
  "$PY" -c 'import uiautomator2 as u2; d = u2.connect(); print("uiautomator2:", d.info)' >> "$OUT/run.log" 2>&1 \
    || { log "  uiautomator2 agent did not start"; return 1; }
  # with a first-run setup: open the app once and record where it lands, to show the setup survived the restart
  if [ "$PREP_DONE" = yes ]; then
    reply=$(ADB="$ADB" "$PY" -u ci/run_prep.py --check "$APP" "$SERIAL" "$OUT/prep/after-restart-$((EMU_RESTARTS + 1))" 2>&1)
    printf '%s\n' "--- after emulator restart $((EMU_RESTARTS + 1))" "$reply" >> "$OUT/prep.log"
    log "  setup state: $(printf '%s\n' "$reply" | tail -1)"
  fi
  # nothing from the restart may count: stop what the boot (and the check above) started in the app, which also
  # discards its coverage (kept in the app's memory until a dump), and delete any coverage file it wrote
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
  $ADB shell rm -f "$DEVICE_EC" >/dev/null 2>&1
  $ADB logcat -b crash -v time >> "$OUT/logcat_crash.txt" 2>&1 &
  LOGCAT_PID=$!
  rm -f "$OUT/.restarting"
  EMU_RESTARTS=$((EMU_RESTARTS + 1))
  RESTART_SECONDS=$((RESTART_SECONDS + $(date -u +%s) - t0))
  log "  emulator restart done in $(( $(date -u +%s) - t0 )) s; emulator process now at $(qemu_rss_mb) MB"
  return 0
}

monitor_loop() {
  while [ ! -e "$OUT/.stop" ]; do
    {
      echo "[$(date -u +%H:%M:%S)] resources:"
      df -h / /tmp /mnt 2>/dev/null | sed 's/^/  /'
      free -m | sed 's/^/  /'
      echo "  du: tmp=$(du -sh /tmp 2>/dev/null | cut -f1) avd=$(du -sh "$HOME/.android/avd" 2>/dev/null | cut -f1) out=$(du -sh "$OUT" 2>/dev/null | cut -f1) processes=$(ps -e --no-headers | wc -l)"
      ps -eo rss,comm --sort=-rss --no-headers | head -5 | awk '{printf "  rss %6.0f MB  %s\n", $1/1024, $2}'
    } 2>&1 | tee -a "$OUT/resources.log"
    for _ in $(seq 120); do [ -e "$OUT/.stop" ] && break; sleep 1; done
  done
}

dump_loop() {
  local n
  while true; do
    n=0
    while [ "$n" -lt "$INTERVAL" ] && [ ! -e "$OUT/.stop" ]; do sleep 1; n=$((n + 1)); done
    if [ -e "$OUT/.stop" ]; then break; fi
    # paused while the emulator restarts: restart_emulator dumps right before the shutdown, and nothing from the
    # reboot or reinstall may be recorded
    if [ -e "$OUT/.restarting" ]; then continue; fi
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
    "emulator_restarts=$EMU_RESTARTS" "emulator_restart_seconds=$RESTART_SECONDS" "prep=$PREP" "prep_done=$PREP_DONE" \
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
SERIAL=$($ADB get-serialno 2>/dev/null | tr -d '\r')
# the command that starts the emulator, so restart_emulator can start it again with the same options. The workflow
# passes EMU_OPTIONS (the same string as the emulator step's emulator-options); the AVD is "test" in
# android-emulator-runner.
SDK_ROOT=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/usr/local/lib/android/sdk}}
EMU_CMD="$SDK_ROOT/emulator/emulator -port 5554 -avd ${EMU_AVD:-test} ${EMU_OPTIONS:--no-window -gpu swiftshader_indirect -no-snapshot -noaudio -no-boot-anim -camera-back none -camera-front none}"
log "emulator command (for restarts): $EMU_CMD"
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
if ! start_frida; then
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

# ---------------------------------------------------------------- first-run setup (PREP=on)
# ci/run_prep.py runs the app's script from ci/prep_scripts.py (unchanged), retries once from a fresh install, and writes
# setup.json plus a screenshot and the screen's widgets after each attempt (prep/). A run never starts on an app whose
# setup failed: its results would not be comparable.
if [ "$PREP" = on ]; then
  HAS_PREP=$("$PY" ci/run_prep.py --has "$APP" 2>&1 | tail -1)
  if [ "$HAS_PREP" = yes ]; then
    group "first-run setup"
    if ADB="$ADB" "$PY" -u ci/run_prep.py "$APP" "$SERIAL" "$OUT" "$APK" 2>&1 | tee -a "$OUT/prep.log"; then
      PREP_DONE=yes
      log "first-run setup done: $(tr -d '\n' < "$OUT/setup.json" | cut -c1-400)"
      endgroup
    else
      log "first-run setup failed; see prep.log, setup.json and prep/"
      STATUS=prep_failed
      endgroup
      finalize
      exit 3
    fi
  elif [ "$HAS_PREP" = no ]; then
    log "no first-run setup script for $APP"
  else
    log "ci/run_prep.py does not run: $HAS_PREP"
    STATUS=prep_failed
    finalize
    exit 3
  fi
fi

# ---------------------------------------------------------------- run
$ADB logcat -c >/dev/null 2>&1 || true
$ADB logcat -b crash -v time > "$OUT/logcat_crash.txt" 2>&1 &
LOGCAT_PID=$!
dump_once
dump_loop &
LOOP_PID=$!
monitor_loop &
MON_PID=$!
STARTED=$(date -u +%Y-%m-%dT%H:%M:%SZ)
T0=$(date -u +%s)
LAST_BEAT=$T0
FAST=0
echo "iteration,exit_code,seconds" > "$OUT/adge/iterations.csv"
while true; do
  NOW=$(date -u +%s)
  # exploration time left; time spent restarting the emulator is not counted, so ADGE gets the full RUN_SECONDS
  LEFT=$((RUN_SECONDS - (NOW - T0 - RESTART_SECONDS)))
  if [ "$LEFT" -le 15 ]; then break; fi
  # Restart the emulator before its memory use can take the runner down (see restart_emulator). The job budget allows
  # it: the step time-out is 340 min and a restart takes about 2 min.
  # TEST_RESTART_AFTER_RUNS=N (test knob, unset in real runs) forces one restart after N ADGE runs, to check the
  # restart path early instead of 45 minutes into a 2-hour run
  if [ "$(qemu_rss_mb)" -ge "$EMU_RSS_LIMIT_MB" ] \
     || { [ -n "${TEST_RESTART_AFTER_RUNS:-}" ] && [ "$EMU_RESTARTS" -eq 0 ] && [ "$ITERATIONS" -ge "$TEST_RESTART_AFTER_RUNS" ]; }; then
    if ! restart_emulator; then
      log "emulator restart failed; stopping with the results collected so far"
      STATUS=emulator_restart_failed
      break
    fi
    NOW=$(date -u +%s)
    LEFT=$((RUN_SECONDS - (NOW - T0 - RESTART_SECONDS)))
  fi
  # Last resort: stop early, keeping the results, if the runner is about to run out of disk or memory (it would
  # otherwise be shut down and the artifact upload skipped).
  DISK_FREE_MB=$(df -Pm / | awk 'NR == 2 {print $4}')
  MEM_AVAIL_MB=$(awk '/^MemAvailable:/ {print int($2 / 1024)}' /proc/meminfo)
  if [ "${DISK_FREE_MB:-99999}" -lt 2048 ] || [ "${MEM_AVAIL_MB:-99999}" -lt 1024 ]; then
    log "resource guard: ${DISK_FREE_MB} MB disk free, ${MEM_AVAIL_MB} MB memory available; stopping early to keep the results"
    STATUS=resource_guard
    break
  fi
  ITERATIONS=$((ITERATIONS + 1))
  i=$(printf '%03d' "$ITERATIONS")
  # Clear the app's task before each run. ADGE starts the app with a LAUNCHER intent, which only brings the existing
  # task to the front; a sub-activity left on top from the previous run (e.g. the camera opened via
  # startActivityForResult, which lives in the app's own task) would otherwise trap every restart on a screen with no
  # app widgets to tap. Dump coverage first so force-stop loses nothing (the receiver resets its counters on each dump).
  dump_once
  $ADB shell am force-stop "$PKG" >/dev/null 2>&1 || true
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
  log "ADGE run $ITERATIONS: exit code $RC after $SPENT s ($((RUN_SECONDS - ($(date -u +%s) - T0 - RESTART_SECONDS))) s left)"
  # A short run that ends by itself (exit 0: every action tried, or the app was left) is normal for apps with a gate
  # screen; it is restarted like run.bat does. Only quick failures (crash, app not starting) count towards giving up.
  if [ "$SPENT" -lt 30 ]; then sleep 5; fi
  if [ "$SPENT" -lt 30 ] && [ "$RC" -ne 0 ]; then FAST=$((FAST + 1)); else FAST=0; fi
  if [ "$FAST" -ge 10 ]; then
    log "ADGE failed within seconds 10 times in a row; stopping (see adge/logs.tar.gz)"
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
log "ADGE runs finished after $ELAPSED s ($((ELAPSED - RESTART_SECONDS)) s exploring, $EMU_RESTARTS emulator restart(s) taking $RESTART_SECONDS s): $ITERATIONS run(s), $COMPLETED_RUNS ended by themselves"
APP_ALIVE=$([ -n "$($ADB shell pidof "$PKG" 2>/dev/null | tr -d '\r')" ] && echo yes || echo no)
touch "$OUT/.stop"
wait "$LOOP_PID" 2>/dev/null
wait "$MON_PID" 2>/dev/null
dump_once
kill "$LOGCAT_PID" 2>/dev/null
wait "$LOGCAT_PID" 2>/dev/null

finalize
exit $?
