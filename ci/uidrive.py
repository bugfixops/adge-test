"""UI driver for ci/prep_scripts.py: adb for shell commands and input, uiautomator2 for the screen's widget tree.

prep_scripts.py came without its uidrive.py, so this one implements the calls the scripts make:
  Device(port)                         port number (5554) or serial (emulator-5554)
  sh(*args)                            run a command in the device shell
  start(pkg)                           launch the app's launcher activity
  top()                                the resumed activity, e.g. "com.money.manager.ex/.home.MainActivity"
  nodes() / find(id=, text=, desc=)    widgets on screen, each a dict: id (resource id without the package, e.g.
                                       'btnNext'), rid (full resource id), text, desc (content-desc), cls, pkg, bounds
  tap_id(id, timeout) / tap_text(text, timeout)
                                       wait up to timeout seconds for the widget, tap its centre; True if tapped
  back(), key(code), type_text(text)   key events and text input (adb shell input)
  no_keyboard()                        context manager: no soft keyboard inside it (every input method disabled, then
                                       restored), so a field can't be hidden behind the keyboard; typing still works,
                                       because `input text` sends key events
  screen(path=None)                    print the top activity and the widgets; with a path, also save a screenshot

The widget tree comes from uiautomator2's dump_hierarchy (all windows, uncompressed), the same source ADGE uses. Every
call is also recorded in TRACE, which ci/run_prep.py saves with each attempt.
"""
import contextlib
import os
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET

ADB = os.environ.get("ADB", "adb")
BOUNDS = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")
BAD_XML = re.compile(u"[\x00-\x08\x0b\x0c\x0e-\x1f]")
TRACE = []
_T0 = time.time()


def _trace(msg):
    TRACE.append("%7.1f  %s" % (time.time() - _T0, msg))


class Device:
    def __init__(self, port):
        port = str(port)
        self.serial = port if port.startswith("emulator-") or ":" in port else "emulator-" + port
        self._u2 = None
        self._last_top = None

    # ------------------------------------------------------------------ adb
    def _adb(self, args, timeout=60):
        try:
            p = subprocess.run([ADB, "-s", self.serial] + [str(a) for a in args], stdout=subprocess.PIPE,
                               stderr=subprocess.STDOUT, timeout=timeout)
            return p.stdout.decode("utf-8", "replace")
        except subprocess.TimeoutExpired:
            _trace("adb timed out: %s" % " ".join(str(a) for a in args))
            return ""

    def sh(self, *args, timeout=60):
        """Run a command in the device shell. Each argument is quoted, so it reaches the command unchanged."""
        if args and args[0] not in ("input", "dumpsys", "ime", "settings"):
            _trace("sh " + " ".join(str(a) for a in args))
        return self._adb(["shell", " ".join(shlex.quote(str(a)) for a in args)], timeout=timeout)

    # ------------------------------------------------------------------ app and screen state
    def start(self, pkg):
        out = self.sh("cmd", "package", "resolve-activity", "--brief", "-a", "android.intent.action.MAIN",
                      "-c", "android.intent.category.LAUNCHER", pkg).strip().splitlines()
        comp = out[-1].strip() if out else ""
        if "/" in comp:
            self.sh("am", "start", "-a", "android.intent.action.MAIN", "-c", "android.intent.category.LAUNCHER", "-n", comp)
        else:
            self.sh("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")

    def top(self):
        out = self.sh("dumpsys", "activity", "activities")
        comp = ""
        for key in ("mResumedActivity", "mFocusedActivity"):
            for line in out.splitlines():
                if key in line:
                    m = re.search(r"(\S+/\S+)", line.split("{", 1)[-1])
                    if m:
                        comp = m.group(1).rstrip("}")
                        break
            if comp:
                break
        if not comp:
            m = re.search(r"mCurrentFocus=Window\{\S+ \S+ (\S+?)\}", self.sh("dumpsys", "window", "windows"))
            comp = m.group(1) if m else ""
        if comp != self._last_top:
            _trace("top " + comp)
            self._last_top = comp
        return comp

    def _dump(self):
        last = None
        for _ in range(3):
            try:
                if self._u2 is None:
                    import uiautomator2 as u2
                    self._u2 = u2.connect(self.serial)
                return self._u2.dump_hierarchy()
            except Exception as e:  # the uiautomator2 server can drop; reconnect and try again
                last = e
                self._u2 = None
                time.sleep(1)
        _trace("dump failed: %r" % (last,))
        return "<hierarchy/>"

    def nodes(self):
        xml = BAD_XML.sub("", self._dump())
        try:
            root = ET.fromstring(xml.encode("utf-8"))
        except ET.ParseError as e:
            _trace("dump not parsed: %s" % e)
            return []
        out = []
        for el in root.iter("node"):
            rid = el.get("resource-id") or ""
            m = BOUNDS.match(el.get("bounds") or "")
            out.append({
                "id": rid.split("/")[-1] if rid else "",
                "rid": rid,
                "text": el.get("text") or "",
                "desc": el.get("content-desc") or "",
                "cls": el.get("class") or "",
                "pkg": el.get("package") or "",
                "bounds": tuple(int(x) for x in m.groups()) if m else (0, 0, 0, 0),
                "clickable": el.get("clickable") == "true",
                "enabled": el.get("enabled") == "true",
            })
        return out

    def find(self, id=None, text=None, desc=None):
        for n in self.nodes():
            if (id is None or n["id"] == id) and (text is None or n["text"] == text) and (desc is None or n["desc"] == desc):
                return n
        return None

    # ------------------------------------------------------------------ input
    @staticmethod
    def _visible(n):
        x1, y1, x2, y2 = n["bounds"]
        return x2 > x1 and y2 > y1

    def _tap_node(self, n, what):
        x1, y1, x2, y2 = n["bounds"]
        x, y = (x1 + x2) // 2, (y1 + y2) // 2
        _trace("tap %s at %d,%d (%s %r)" % (what, x, y, n["cls"].split(".")[-1], n["text"] or n["desc"]))
        self.sh("input", "tap", x, y)

    def _wait_tap(self, what, pick, timeout):
        end = time.time() + timeout
        while True:
            n = pick([n for n in self.nodes() if self._visible(n)])
            if n is not None:
                self._tap_node(n, what)
                return True
            if time.time() >= end:
                _trace("tap %s: not found within %ss" % (what, timeout))
                return False
            time.sleep(0.5)

    def tap_id(self, id, timeout=10):
        return self._wait_tap("id=" + id, lambda ns: next((n for n in ns if n["id"] == id), None), timeout)

    def tap_text(self, text, timeout=10):
        """Exact text or content-desc first, then the same ignoring case (buttons often show their text in capitals)."""
        def pick(ns):
            exact = next((n for n in ns if text in (n["text"], n["desc"])), None)
            if exact is not None:
                return exact
            low = text.strip().lower()
            return next((n for n in ns if low in (n["text"].strip().lower(), n["desc"].strip().lower())), None)
        return self._wait_tap("text=%r" % text, pick, timeout)

    def back(self):
        _trace("key BACK")
        self.sh("input", "keyevent", 4)

    def key(self, code):
        _trace("key %s" % code)
        self.sh("input", "keyevent", code)

    def type_text(self, text):
        _trace("type %r" % text)
        self.sh("input", "text", str(text).replace(" ", "%s"))

    @contextlib.contextmanager
    def no_keyboard(self):
        enabled = [l.strip() for l in self.sh("ime", "list", "-s").splitlines() if l.strip()]
        default = self.sh("settings", "get", "secure", "default_input_method").strip()
        for ime in enabled:
            self.sh("ime", "disable", ime)
        _trace("soft keyboard off (disabled %s)" % ", ".join(enabled))
        try:
            yield self
        finally:
            for ime in enabled:
                self.sh("ime", "enable", ime)
            if default and default != "null":
                self.sh("ime", "set", default)
            _trace("soft keyboard back on (%s)" % default)

    # ------------------------------------------------------------------ evidence
    def screen(self, path=None):
        lines = ["top: %s" % self.top()]
        for n in self.nodes():
            if n["id"] or n["text"] or n["desc"]:
                lines.append("  %-30s %-34s %-30s %s" % (n["id"], n["text"][:34], n["desc"][:30], n["pkg"]))
        text = "\n".join(lines)
        print(text)
        if path:
            try:
                p = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"], stdout=subprocess.PIPE,
                                   timeout=60)
                with open(path, "wb") as f:
                    f.write(p.stdout)
            except subprocess.TimeoutExpired:
                pass
        return text
