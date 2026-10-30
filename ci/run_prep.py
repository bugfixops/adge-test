"""Run an app's first-run setup script from ci/prep_scripts.py and record what happened, as the Monkey runner did.

prep_scripts.py is used unchanged. This file only runs it, retries once, and saves the evidence:
  <outdir>/setup.json            app, script, ok, time, log (the script's LAST_LOG), and every attempt
  <outdir>/prep/attemptN.png     screenshot after attempt N
  <outdir>/prep/attemptN.txt     the widgets on screen after attempt N, then the trace of every call the script made

  python3 ci/run_prep.py <App> <serial> <outdir> <apk>    exit 0: setup done, 1: setup failed
  python3 ci/run_prep.py --has <App>                      prints yes if the app has a setup script, else no
  python3 ci/run_prep.py --check <App> <serial> <path>    open the app and print where it lands (last line), with a
                                                          screenshot and the widgets in <path>.png and <path>.txt;
                                                          used after an emulator restart, to show the setup survived

Each script gets twice its own time limit: GitHub's emulators are slower than the machine the scripts were written on,
and the scripts return as soon as the app reaches the final state, so a longer limit only matters on a slow device.
A failed attempt is retried once from a fresh install, because a half-finished setup can leave a screen the script
doesn't expect.
"""
import datetime
import inspect
import json
import os
import subprocess
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import prep_scripts  # noqa: E402
import uidrive  # noqa: E402

ADB = os.environ.get("ADB", "adb")
ATTEMPTS = 2


def adb(serial, *args, timeout=600):
    p = subprocess.run([ADB, "-s", serial] + list(args), stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout)
    return p.stdout.decode("utf-8", "replace").strip()


def check(app, serial, path):
    pkg = inspect.signature(prep_scripts.SCRIPTS[app]).parameters["pkg"].default
    os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
    d = uidrive.Device(serial)
    d.start(pkg)
    top, same = "", 0
    end = time.time() + 40
    while time.time() < end and same < 2:  # the same activity three times in a row: startup screens flash by
        time.sleep(3)
        now = d.top()
        same = same + 1 if now == top else 0
        top = now
    screen = d.screen(path + ".png")
    with open(path + ".txt", "w", encoding="utf-8") as f:
        f.write(screen + "\n")
    ids = sorted({n["id"] for n in d.nodes() if n["id"] and n["pkg"] == pkg})
    print("app opens at %s; its widget ids: %s" % (top, " ".join(ids[:20]) or "none"), flush=True)


def main():
    if sys.argv[1] == "--has":  # printed rather than an exit code, so a broken setup can't pass for "no script"
        print("yes" if sys.argv[2] in prep_scripts.SCRIPTS else "no")
        return
    if sys.argv[1] == "--check":
        check(*sys.argv[2:5])
        return
    app, serial, out, apk = sys.argv[1:5]
    fn = prep_scripts.SCRIPTS[app]
    params = inspect.signature(fn).parameters
    pkg = params["pkg"].default
    timeout = 2 * params["timeout"].default
    pdir = os.path.join(out, "prep")
    os.makedirs(pdir, exist_ok=True)
    record = {"app": app, "script": "prep_scripts." + fn.__name__, "ok": False, "timeout_seconds": timeout,
              "attempts": []}
    ok = False
    for attempt in range(1, ATTEMPTS + 1):
        if attempt > 1:
            print("attempt %d: fresh install of %s" % (attempt, pkg), flush=True)
            print("  uninstall:", adb(serial, "uninstall", pkg).splitlines()[-1:], flush=True)
            print("  install:", adb(serial, "install", "-g", "-t", apk).splitlines()[-1:], flush=True)
        prep_scripts.LAST_LOG.clear()
        del uidrive.TRACE[:]
        t0 = time.time()
        error = ""
        try:
            ok = bool(fn(serial, timeout=timeout))
        except Exception as e:  # a crash in the script counts as a failed attempt
            ok, error = False, repr(e)
        seconds = round(time.time() - t0, 1)
        try:
            screen = uidrive.Device(serial).screen(os.path.join(pdir, "attempt%d.png" % attempt))
        except Exception as e:
            screen = "screen not captured: %r" % (e,)
        with open(os.path.join(pdir, "attempt%d.txt" % attempt), "w", encoding="utf-8") as f:
            f.write("ok=%s seconds=%s error=%s\n\n%s\n\ntrace:\n%s\n" % (ok, seconds, error, screen, "\n".join(uidrive.TRACE)))
        record["attempts"].append({"attempt": attempt, "ok": ok, "seconds": seconds, "error": error,
                                   "log": list(prep_scripts.LAST_LOG)})
        print("attempt %d: %s after %ss %s" % (attempt, "OK" if ok else "FAILED", seconds, error), flush=True)
        if ok:
            break
    record["ok"] = ok
    record["time"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    record["log"] = list(prep_scripts.LAST_LOG)
    with open(os.path.join(out, "setup.json"), "w", encoding="utf-8") as f:
        json.dump(record, f, indent=1)
        f.write("\n")
    print(app, "prep", "OK" if ok else "FAILED", flush=True)
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
