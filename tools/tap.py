"""Tap a UI element on the connected phone by its visible text (or content description).

    python tools/tap.py "Готово"            # first match
    python tools/tap.py "Настройки" --desc   # match content-description instead of text
    python tools/tap.py --list               # print visible texts with their centers

Uses `adb shell uiautomator dump`; Compose exposes text through accessibility, so this works
for the app's screens as well as for system dialogs.
"""
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


def dump():
    subprocess.run(["adb", "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True)
    xml = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"], capture_output=True).stdout
    return ET.fromstring(xml)


def center(bounds):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", bounds))
    return (x1 + x2) // 2, (y1 + y2) // 2


def main():
    args = sys.argv[1:]
    root = dump()
    nodes = list(root.iter("node"))
    if "--list" in args:
        for n in nodes:
            t = n.get("text") or n.get("content-desc")
            if t:
                print(center(n.get("bounds")), repr(t))
        return
    want = args[0]
    attr = "content-desc" if "--desc" in args else "text"
    for n in nodes:
        if (n.get(attr) or "").strip() == want:
            x, y = center(n.get("bounds"))
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tapped {want!r} at {x},{y}")
            return
    for n in nodes:  # substring fallback
        if want in (n.get(attr) or ""):
            x, y = center(n.get("bounds"))
            subprocess.run(["adb", "shell", "input", "tap", str(x), str(y)])
            print(f"tapped ~{n.get(attr)!r} at {x},{y}")
            return
    print(f"not found: {want!r}")
    sys.exit(1)


if __name__ == "__main__":
    main()
