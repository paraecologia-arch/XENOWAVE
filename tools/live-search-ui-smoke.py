#!/usr/bin/env python3
"""Real emulator smoke test for the online search screen.

This runs against the debug application after connected instrumentation tests.
It uses only public, anonymous YouTube Music results and asserts that the screen
does not turn a parsed response into "No results found".
"""

from __future__ import annotations

import glob
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

PACKAGE = "com.dd3boh.xenowave.debug"
ACTIVITY = "com.dd3boh.xenowave.MainActivity"
QUERY = "rock dos anos 70"
NO_RESULTS = ("no results found", "nenhum resultado encontrado", "não há resultados")


def adb(*args: str, check: bool = True) -> subprocess.CompletedProcess:
    result = subprocess.run(["adb", *args], capture_output=True, text=True)
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip()}")
    return result


def dump_ui() -> ET.Element:
    adb("shell", "uiautomator", "dump", "/sdcard/xenowave-window.xml")
    xml = adb("exec-out", "cat", "/sdcard/xenowave-window.xml").stdout
    with open("live-search-ui-smoke.xml", "w", encoding="utf-8") as handle:
        handle.write(xml)
    return ET.fromstring(xml)


def node_text(node: ET.Element) -> str:
    return " ".join(
        part for part in (node.get("text"), node.get("content-desc")) if part
    ).strip()


def find_node(root: ET.Element, needles: tuple[str, ...]) -> ET.Element | None:
    for node in root.iter("node"):
        text = node_text(node).lower()
        if any(needle.lower() in text for needle in needles):
            return node
    return None


def has_no_results(root: ET.Element) -> bool:
    return any(
        needle in node_text(node).lower()
        for node in root.iter("node")
        for needle in NO_RESULTS
    )


def tap_node(node: ET.Element) -> None:
    match = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    if not match:
        raise RuntimeError("node has no usable bounds")
    left, top, right, bottom = map(int, match.groups())
    adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))


def wait_for(predicate, timeout: float = 20.0):
    deadline = time.time() + timeout
    last = None
    while time.time() < deadline:
        last = dump_ui()
        if predicate(last):
            return last
        time.sleep(1)
    return last


def ensure_installed() -> None:
    installed = adb("shell", "pm", "path", PACKAGE, check=False).returncode == 0
    if installed:
        return
    apks = glob.glob("app/build/outputs/apk/core/debug/*.apk")
    if not apks:
        raise RuntimeError("debug APK not found for live search smoke")
    adb("install", "-r", apks[0])


def main() -> int:
    ensure_installed()
    adb("shell", "am", "force-stop", PACKAGE)
    adb("shell", "am", "start", "-W", "-n", f"{PACKAGE}/{ACTIVITY}")
    time.sleep(3)

    root = dump_ui()
    search = find_node(root, ("search", "pesquisar"))
    if search is None:
        raise RuntimeError("search entry point was not found")
    tap_node(search)
    time.sleep(1)
    adb("shell", "input", "text", QUERY.replace(" ", "%s"))
    adb("shell", "input", "keyevent", "66")

    root = wait_for(
        lambda value: not has_no_results(value)
        and find_node(value, ("songs", "músicas")) is not None
        and find_node(value, ("rock", "70s", "70")) is not None,
        timeout=30,
    )
    if has_no_results(root):
        raise RuntimeError("live search still shows a no-results state")
    if find_node(root, ("rock", "70s")) is None:
        raise RuntimeError("live search returned no visible rock/70s result")

    evidence = {"query": QUERY, "chips": {}}

    for chip in ("Songs", "Músicas", "Videos", "Vídeos"):
        node = find_node(root, (chip,))
        if node is None:
            continue
        tap_node(node)
        time.sleep(3)
        root = dump_ui()
        evidence["chips"][chip] = {
            "noResults": has_no_results(root),
            "visible": len(list(root.iter("node"))),
        }
        if has_no_results(root):
            raise RuntimeError(f"chip {chip} shows no-results after live search")

    screenshot = subprocess.run(
        ["adb", "exec-out", "screencap", "-p"],
        capture_output=True,
    )
    with open("live-search-ui-smoke.png", "wb") as handle:
        handle.write(screenshot.stdout)
    with open("live-search-ui-smoke.json", "w", encoding="utf-8") as handle:
        json.dump(evidence, handle, ensure_ascii=False, indent=2)
    print(json.dumps(evidence, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as error:
        print(f"LIVE_SEARCH_UI_SMOKE_FAILED: {error}", file=sys.stderr)
        try:
            shot = subprocess.run(
                ["adb", "exec-out", "screencap", "-p"],
                capture_output=True,
            )
            with open("live-search-ui-smoke-failure.png", "wb") as handle:
                handle.write(shot.stdout)
        finally:
            sys.exit(1)
