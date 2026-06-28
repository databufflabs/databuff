"""Record live API responses into expected/*.json (maintainer baseline refresh)."""

from __future__ import annotations

import json
from pathlib import Path

from cases import ApiCase, build_cases
from demo_window import QUERY_WINDOW_MS, aligned_query_window

LIB_ROOT = Path(__file__).resolve().parent


def run_snapshot(
    base: str,
    token: str,
    cases: list[ApiCase],
    timeout: float,
) -> tuple[int, int]:
    """Write HTTP payloads to expected/*.json. Returns (written, skipped)."""
    from run_tests import http_json  # noqa: E402

    ok = 0
    failed = 0
    for case in cases:
        url = f"{base.rstrip('/')}{case.path}"
        code, _, payload = http_json(case.method, url, body=case.body, token=token, timeout=timeout)
        if code != case.expect_status:
            print(f"[snapshot] SKIP {case.group}/{case.name}: HTTP {code}")
            failed += 1
            continue
        out = case.expected_path
        out.parent.mkdir(parents=True, exist_ok=True)
        out.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        print(f"[snapshot] wrote {out.relative_to(LIB_ROOT)}")
        ok += 1
    return ok, failed


def snapshot_cases(
    base: str,
    token: str,
    *,
    service: str,
    timeout: float,
    warmup: int,
) -> int:
    from run_tests import wait_for_demo_data_in_window  # noqa: E402

    frm_ms, to_ms = aligned_query_window()
    wait_for_demo_data_in_window(base, token, service, frm_ms, to_ms, max(min(warmup, 120), 60))
    cases = build_cases(frm_ms, to_ms)
    print(f"[snapshot] recording {len(cases)} cases, window last {QUERY_WINDOW_MS // 1000}s ...")
    ok, failed = run_snapshot(base, token, cases, timeout)
    print(f"[snapshot] done {ok} written, {failed} skipped")
    return 0 if failed == 0 else 1
