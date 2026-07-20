#!/usr/bin/env python3
"""Standalone runner for AI session memory integration cases."""

from __future__ import annotations

import os
import sys
from pathlib import Path

LIB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(LIB_ROOT))

from ai_session_memory import (  # noqa: E402
    ENV_API_KEY,
    deepseek_api_key,
    run_ai_session_memory_cases,
)
from run_tests import login  # noqa: E402


def main() -> int:
    if not deepseek_api_key():
        print(f"[ai-session-memory] skip: set {ENV_API_KEY} to enable")
        return 0

    base = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403").rstrip("/")
    username = os.environ.get("TEST_USERNAME", "admin")
    password = os.environ.get("TEST_PASSWORD", "Databuff@123")
    timeout = float(os.environ.get("TEST_TIMEOUT", "60"))
    poll_timeout = float(os.environ.get("TEST_AI_MEMORY_POLL_TIMEOUT", "240"))

    print(f"[ai-session-memory] login {base} ...")
    token = login(base, username, password, timeout)
    print(f"[ai-session-memory] running cases ({ENV_API_KEY} set) ...")
    results = run_ai_session_memory_cases(base, token, poll_timeout_sec=poll_timeout)

    failed = 0
    for item in results:
        mark = "PASS" if item.ok else "FAIL"
        print(f"[{mark}] {item.name} ({item.elapsed_ms:.0f}ms) session={item.session_id or '-'}")
        print(f"       {item.detail[:240]}")
        if not item.ok:
            failed += 1

    print(f"[ai-session-memory] summary: {len(results) - failed}/{len(results)} passed")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
