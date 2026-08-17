#!/usr/bin/env python3
"""Assert Agent 观测 8 叶仍是 ComingSoon，禁止当成已出数页。"""

from __future__ import annotations

from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
AIMONITOR = REPO / "ai-apm-frontend" / "src" / "views" / "aiMonitor"

REQUIRED = (
    "applications/index.vue",
    "topology/index.vue",
    "skillCalls/index.vue",
    "toolCalls/index.vue",
    "modelCalls/index.vue",
    "sessions/index.vue",
    "tokens/index.vue",
    "errors/index.vue",
)


def test() -> None:
    missing = []
    not_coming_soon = []
    for rel in REQUIRED:
        path = AIMONITOR / rel
        if not path.is_file():
            missing.append(rel)
            continue
        text = path.read_text(encoding="utf-8")
        if "ComingSoon" not in text and "coming-soon" not in text:
            not_coming_soon.append(rel)
    if missing:
        raise AssertionError(f"aiMonitor views missing: {missing}")
    if not_coming_soon:
        raise AssertionError(
            f"aiMonitor views no longer ComingSoon (do not mark as live data): {not_coming_soon}"
        )


if __name__ == "__main__":
    test()
    print("coming_soon_guard: 8 aiMonitor leaves still ComingSoon")
