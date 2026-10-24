"""Evaluate one journey XML against one captured layout tree.

Reads $JOURNEY (the XML) and $LAYOUT (JSON from `android layout`), prints a line per action, and
exits non-zero if any action failed or could not be evaluated.

Only the mechanical assertion forms are handled here:

    Verify the screen shows "X"
    Verify the screen does NOT show "X"

Anything else exits non-zero as UNSUPPORTED rather than being skipped. A skipped assertion is
indistinguishable from a passing one in a summary count, and that exact confusion has already had
to be fixed twice in the SDK's own gate -- so this refuses to produce a green run it cannot stand
behind.

Matching is case-sensitive and substring-based against the concatenated `text` and `content-desc`
of every node in the app's own window. Case-sensitive on purpose: the Android CLI's selectors
match case-insensitive substrings, which is fine for finding something to tap but too loose for an
assertion -- "connect" would match "Paste the contract address someone shared to connect to their
bulletin board." and the check would pass on prose instead of the button.
"""

import json
import os
import re
import sys
import xml.etree.ElementTree as ET

SHOWS = re.compile(r'^verify\s+(?:that\s+)?the\s+screen\s+shows\s+"?(.+?)"?\s*$', re.I)
HIDES = re.compile(
    r'^verify\s+(?:that\s+)?the\s+screen\s+does\s+not\s+show\s+"?(.+?)"?\s*$', re.I
)
# "a <X> control" is how a journey names a button without asserting its exact rendering.
CONTROL = re.compile(r'^(?:a|an)\s+"?(.+?)"?\s+control$', re.I)


def visible_text(layout_path):
    """Every string the app's own window exposes. System UI and the IME are excluded: a journey
    asserts about the app, and the status bar clock or a floating keyboard would otherwise leak
    into the haystack and make an assertion pass for the wrong reason."""
    with open(layout_path) as fh:
        tree = json.load(fh)
    found = []

    def walk(node):
        if isinstance(node, dict):
            for key in ("text", "content-desc"):
                value = node.get(key)
                if value:
                    found.append(value)
            for child in node.get("children") or []:
                walk(child)
            for child in node.get("content") or []:
                walk(child)
        elif isinstance(node, list):
            for child in node:
                walk(child)

    for window in tree:
        if window.get("type") == "APPLICATION":
            walk(window)
    return found


def main():
    journey_path = os.environ["JOURNEY"]
    layout_path = os.environ["LAYOUT"]

    root = ET.parse(journey_path).getroot()
    name = root.get("name") or os.path.basename(journey_path)
    haystack = " | ".join(visible_text(layout_path))

    print(f"── {name}")
    failures = 0
    actions = root.findall("./actions/action")
    if not actions:
        print("   FAIL  journey declares no actions — it verifies nothing")
        sys.exit(1)

    for action in actions:
        text = " ".join((action.text or "").split())
        shows, hides = SHOWS.match(text), HIDES.match(text)
        if shows or hides:
            needle = (shows or hides).group(1)
            control = CONTROL.match(needle)
            if control:
                needle = control.group(1)
            present = needle in haystack
            ok = present if shows else not present
            print(f"   {'PASS' if ok else 'FAIL'}  {text}")
            if not ok:
                failures += 1
                print(
                    f"         expected {'to find' if shows else 'NOT to find'} "
                    f'"{needle}" in the app window'
                )
        else:
            print(f"   UNSUPPORTED  {text}")
            print("         this runner evaluates only: Verify the screen shows / does NOT show")
            failures += 1

    print(f"   {'PASS' if failures == 0 else 'FAIL'} — {len(actions) - failures}/{len(actions)}")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
