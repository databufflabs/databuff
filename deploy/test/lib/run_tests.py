#!/usr/bin/env python3
"""应用性能后端接口集成测试，输出 JSON + HTML 报告."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional
from zoneinfo import ZoneInfo

LIB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(LIB_ROOT))

from cases import ApiCase, build_cases  # noqa: E402
from cases.common import DEMO_CHECKOUT_RESOURCE, DEMO_SERVICE_A, trace_spans_body  # noqa: E402
from ai_chat_integration import (  # noqa: E402
    AI_CHAT_QUESTIONS,
    GROUP_DATA_EXPERT,
    MODULE_AI_PLATFORM,
    is_llm_ready,
    run_ai_chat_tool_loop,
)
from ai_provider_formats import (  # noqa: E402
    ENV_DEEPSEEK as PROVIDER_FORMAT_DEEPSEEK_ENV,
    ENV_MINIMAX as PROVIDER_FORMAT_MINIMAX_ENV,
    FORMAT_QUESTIONS,
    GROUP_PROVIDER_FORMATS,
    available_provider_formats,
    run_ai_provider_format_cases,
)
from ai_session_memory import (  # noqa: E402
    ENV_API_KEY as DEEPSEEK_API_KEY_ENV,
    GROUP_SESSION_MEMORY,
    deepseek_api_key,
    run_ai_session_memory_cases,
)
from ai_brain_async_routing import (  # noqa: E402
    GROUP_BRAIN_ASYNC,
    run_ai_brain_async_routing_cases,
)
from demo_window import MIN_WARMUP_SECONDS, QUERY_WINDOW_MS, aligned_query_window, trace_batch_bounds  # noqa: E402
from ingest_warmup import wait_for_ingest_warmup  # noqa: E402
from json_assert import assert_matches  # noqa: E402

SHANGHAI_TZ = ZoneInfo("Asia/Shanghai")
DATETIME_FMT = "%Y-%m-%d %H:%M:%S"


def fmt_duration(seconds: float | int | None) -> str:
    if seconds is None:
        return "未知"
    total = int(max(0, seconds))
    if total < 60:
        return f"{total} 秒"
    minutes, secs = divmod(total, 60)
    if minutes < 60:
        return f"{minutes} 分 {secs} 秒" if secs else f"{minutes} 分钟"
    hours, minutes = divmod(minutes, 60)
    return f"{hours} 小时 {minutes} 分"


def fmt_ms(ms: int) -> str:
    return datetime.fromtimestamp(ms / 1000, tz=SHANGHAI_TZ).strftime(DATETIME_FMT)


def fmt_iso_short(value: str) -> str:
    try:
        dt = datetime.fromisoformat(value)
        if dt.tzinfo is None:
            dt = dt.replace(tzinfo=timezone.utc)
        return dt.astimezone(SHANGHAI_TZ).strftime(DATETIME_FMT)
    except ValueError:
        return value


def test_duration_seconds(report: TestReport) -> float:
    try:
        start = datetime.fromisoformat(report.started_at)
        end = datetime.fromisoformat(report.finished_at)
        if start.tzinfo is None:
            start = start.replace(tzinfo=timezone.utc)
        if end.tzinfo is None:
            end = end.replace(tzinfo=timezone.utc)
        return max(0.0, (end - start).total_seconds())
    except ValueError:
        return 0.0


def warmup_summary(report: TestReport) -> str:
    required = fmt_duration(report.min_warmup_seconds)
    waited = report.warmup_waited_seconds
    uptime = report.ingest_uptime_seconds
    if report.min_warmup_seconds <= 0:
        return "已跳过"
    if uptime is None:
        return "无法检测 ingest 启动时间，已跳过预热等待（由外部保障就绪）"
    uptime_text = fmt_duration(uptime)
    if waited <= 0 and uptime >= report.min_warmup_seconds:
        return f"ingest 已运行 {uptime_text}（≥ {required}），无需等待"
    if waited > 0:
        return f"ingest 已运行 {uptime_text}，额外等待 {fmt_duration(waited)}（要求 ≥ {required}）"
    return f"ingest 已运行 {uptime_text}（要求 ≥ {required}）"


@dataclass
class CaseResult:
    module: str
    group: str
    name: str
    path: str
    method: str
    ok: bool
    http_status: int
    elapsed_ms: float
    detail: str
    expected_file: str = ""
    expected_json: str = ""


@dataclass
class TestReport:
    started_at: str
    finished_at: str
    base_url: str
    query_from_ms: int = 0
    query_to_ms: int = 0
    min_warmup_seconds: int = 0
    warmup_waited_seconds: int = 0
    ingest_uptime_seconds: Optional[float] = None
    total: int = 0
    passed: int = 0
    failed: int = 0
    results: list[CaseResult] = field(default_factory=list)

    @property
    def success_rate(self) -> float:
        return (self.passed / self.total * 100) if self.total else 0.0


def http_json(
    method: str,
    url: str,
    *,
    body: Optional[dict[str, Any]] = None,
    token: Optional[str] = None,
    timeout: float = 60.0,
) -> tuple[int, float, Any]:
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    started = time.time()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            text = response.read().decode("utf-8", errors="replace")
            elapsed = (time.time() - started) * 1000
            try:
                payload = json.loads(text) if text else {}
            except json.JSONDecodeError:
                payload = {"_raw": text[:500]}
            return response.status, elapsed, payload
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", errors="replace")
        elapsed = (time.time() - started) * 1000
        try:
            payload = json.loads(text) if text else {}
        except json.JSONDecodeError:
            payload = {"_raw": text[:500]}
        return error.code, elapsed, payload
    except Exception as error:  # noqa: BLE001
        elapsed = (time.time() - started) * 1000
        return -1, elapsed, {"_error": str(error)}


def login(base: str, username: str, password: str, timeout: float) -> str:
    url = f"{base.rstrip('/')}/webapi/api/v1/auth/login"
    code, _, payload = http_json("POST", url, body={"username": username, "password": password}, timeout=timeout)
    if code != 200:
        raise RuntimeError(f"login failed HTTP {code}: {payload}")
    token = payload.get("token")
    if not token:
        raise RuntimeError(f"login response missing token: {payload}")
    return str(token)


def fetch_latest_checkout_trace_id(
    base: str,
    token: str,
    frm_ms: int,
    to_ms: int,
    timeout: float,
) -> str:
    body = {
        "service": DEMO_SERVICE_A,
        "serviceId": DEMO_SERVICE_A,
        "from": frm_ms,
        "to": to_ms,
        "limit": 20,
    }
    code, _, payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/trace/list",
        body=body,
        token=token,
        timeout=timeout,
    )
    if code != 200:
        raise RuntimeError(f"trace list failed HTTP {code}: {payload}")
    data = payload.get("data") if isinstance(payload, dict) else None
    items = data.get("list") if isinstance(data, dict) else None
    if not isinstance(items, list) or not items:
        raise RuntimeError(f"trace list empty in query window: {payload}")
    for item in items:
        if not isinstance(item, dict):
            continue
        if item.get("resource") == DEMO_CHECKOUT_RESOURCE and item.get("trace_id"):
            return str(item["trace_id"])
    for item in items:
        if isinstance(item, dict) and item.get("trace_id"):
            return str(item["trace_id"])
    raise RuntimeError(f"no trace_id found in trace list: {payload}")


def inject_checkout_trace_ids(
    base: str,
    token: str,
    cases: list[ApiCase],
    frm_ms: int,
    to_ms: int,
    timeout: float,
) -> None:
    needs_trace_id = any(case.needs_checkout_trace_id for case in cases)
    if not needs_trace_id:
        return
    trace_id = fetch_latest_checkout_trace_id(base, token, frm_ms, to_ms, timeout)
    print(f"[test] checkout traceId={trace_id}")
    for case in cases:
        if not case.needs_checkout_trace_id:
            continue
        body = dict(case.body or trace_spans_body())
        body["traceId"] = trace_id
        case.body = body


def wait_for_demo_data_in_window(base: str, token: str, service: str, frm_ms: int, to_ms: int, timeout_sec: int) -> None:
    """Ensure demo traces exist in the last 2-minute query window."""
    deadline = time.time() + timeout_sec
    low, high = trace_batch_bounds()
    body = {"service": service, "serviceId": service, "from": frm_ms, "to": to_ms, "limit": 20}
    while time.time() < deadline:
        code, _, payload = http_json(
            "POST",
            f"{base}/webapi/api/v1/apm/metric/httpEndpoints",
            body=body,
            token=token,
        )
        if code != 200:
            time.sleep(5)
            continue
        text = json.dumps(payload, ensure_ascii=False)
        total = 0
        if isinstance(payload, dict):
            data = payload.get("data")
            if isinstance(data, list):
                total = len(data)
            elif isinstance(data, dict):
                total = int(data.get("total") or len(data.get("list") or []))
        if service in text and "/demo/checkout" in text:
            print(f"[test] demo data ready in last {QUERY_WINDOW_MS // 1000}s window")
            return
        if total >= low:
            print(f"[test] demo endpoints visible in query window (count>={low})")
            return
        time.sleep(5)
    raise RuntimeError(
        f"demo data not ready in last {QUERY_WINDOW_MS // 1000}s after {timeout_sec}s; "
        f"expected >= {low} checkout batches"
    )


def run_cases(base: str, token: str, cases: list[ApiCase], timeout: float) -> TestReport:
    started = datetime.now(timezone.utc).isoformat()
    report = TestReport(started_at=started, finished_at="", base_url=base)

    for case in cases:
        url = f"{base.rstrip('/')}{case.path}"
        code, elapsed, payload = http_json(case.method, url, body=case.body, token=token, timeout=timeout)
        expected = case.expected_json
        expected_text = json.dumps(expected, ensure_ascii=False, sort_keys=True)
        ok = code == case.expect_status
        detail = f"HTTP {code}"
        expected_file = str(case.expected_path.relative_to(LIB_ROOT))

        if ok:
            v_ok, v_msg = assert_matches(payload, expected)
            ok = v_ok
            detail = f"HTTP {code}; {v_msg}" if v_ok else f"HTTP {code}; json mismatch: {v_msg}"

        result = CaseResult(
            module=case.module,
            group=case.group,
            name=case.name,
            path=case.path,
            method=case.method,
            ok=ok,
            http_status=code,
            elapsed_ms=round(elapsed, 1),
            detail=detail,
            expected_file=expected_file,
            expected_json=expected_text,
        )
        report.results.append(result)
        report.total += 1
        if ok:
            report.passed += 1
        else:
            report.failed += 1

    report.finished_at = datetime.now(timezone.utc).isoformat()
    return report


def write_json_report(report: TestReport, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "started_at": report.started_at,
        "finished_at": report.finished_at,
        "base_url": report.base_url,
        "min_warmup_seconds": report.min_warmup_seconds,
        "warmup_waited_seconds": report.warmup_waited_seconds,
        "ingest_uptime_seconds": report.ingest_uptime_seconds,
        "query_from_ms": report.query_from_ms,
        "query_to_ms": report.query_to_ms,
        "query_window_seconds": QUERY_WINDOW_MS // 1000,
        "query_from": fmt_ms(report.query_from_ms),
        "query_to": fmt_ms(report.query_to_ms),
        "warmup_summary": warmup_summary(report),
        "duration_seconds": round(test_duration_seconds(report), 2),
        "total": report.total,
        "passed": report.passed,
        "failed": report.failed,
        "success_rate": round(report.success_rate, 2),
        "results": [asdict(r) for r in report.results],
    }
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def write_html_report(report: TestReport, path: Path) -> None:
    rows = []
    for r in report.results:
        status = "pass" if r.ok else "fail"
        rows.append(
            f"<tr class='{status}'><td>{r.module}</td><td>{r.group}</td><td>{r.name}</td>"
            f"<td>{r.method}</td><td><code>{r.path}</code></td>"
            f"<td>{r.http_status}</td><td>{r.elapsed_ms}</td>"
            f"<td><code>{r.expected_file}</code></td>"
            f"<td>{r.detail}</td></tr>"
        )

    all_pass = report.failed == 0
    banner_class = "pass" if all_pass else "fail"
    banner_text = "全部通过" if all_pass else "存在失败"
    duration = fmt_duration(test_duration_seconds(report))
    window_sec = QUERY_WINDOW_MS // 1000
    query_range = f"{fmt_ms(report.query_from_ms)} ~ {fmt_ms(report.query_to_ms)}"

    html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8"/>
  <title>应用性能接口集成测试报告</title>
  <style>
    body {{ font-family: -apple-system, BlinkMacSystemFont, sans-serif; margin: 24px; color: #111827; }}
    h1 {{ font-size: 1.4rem; margin-bottom: 16px; }}
    .summary {{ margin: 16px 0 20px; padding: 16px; background: #f8fafc; border: 1px solid #e5e7eb; border-radius: 12px; }}
    .result-banner {{ display: inline-block; padding: 8px 14px; border-radius: 999px; font-weight: 600; margin-bottom: 14px; }}
    .result-banner.pass {{ background: #dcfce7; color: #166534; }}
    .result-banner.fail {{ background: #fee2e2; color: #991b1b; }}
    .summary-grid {{ display: grid; grid-template-columns: repeat(auto-fit, minmax(280px, 1fr)); gap: 12px 20px; }}
    .summary-item {{ display: flex; flex-direction: column; gap: 4px; }}
    .summary-item .label {{ font-size: 12px; color: #6b7280; }}
    .summary-item .value {{ font-size: 14px; line-height: 1.5; }}
    .summary-item .value strong {{ color: #111827; }}
    table {{ border-collapse: collapse; width: 100%; font-size: 13px; }}
    th, td {{ border: 1px solid #e5e7eb; padding: 8px; text-align: left; vertical-align: top; }}
    th {{ background: #eef2ff; }}
    tr.pass td {{ background: #f0fdf4; }}
    tr.fail td {{ background: #fef2f2; }}
    code {{ font-size: 12px; word-break: break-all; }}
  </style>
</head>
<body>
  <h1>应用性能接口集成测试报告</h1>
  <div class="summary">
    <div class="result-banner {banner_class}">{banner_text} · {report.passed}/{report.total} · {report.success_rate:.1f}%</div>
    <div class="summary-grid">
      <div class="summary-item">
        <span class="label">服务地址</span>
        <span class="value"><code>{report.base_url}</code></span>
      </div>
      <div class="summary-item">
        <span class="label">ingest 预热</span>
        <span class="value">{warmup_summary(report)}</span>
      </div>
      <div class="summary-item">
        <span class="label">查询窗口</span>
        <span class="value">最近 <strong>{window_sec} 秒</strong><br/>{query_range}</span>
      </div>
      <div class="summary-item">
        <span class="label">执行时间</span>
        <span class="value">{fmt_iso_short(report.started_at)}<br/>耗时 <strong>{duration}</strong></span>
      </div>
    </div>
  </div>
  <table>
    <thead><tr><th>一级目录</th><th>二级目录</th><th>接口</th><th>方法</th><th>路径</th><th>HTTP</th><th>耗时(ms)</th><th>预期文件</th><th>说明</th></tr></thead>
    <tbody>
      {''.join(rows)}
    </tbody>
  </table>
</body>
</html>"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(html, encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description="App Monitor API integration tests")
    parser.add_argument("--base-url", default=os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403"))
    parser.add_argument("--username", default=os.environ.get("TEST_USERNAME", "admin"))
    parser.add_argument("--password", default=os.environ.get("TEST_PASSWORD", "Databuff@123"))
    parser.add_argument("--timeout", type=float, default=float(os.environ.get("TEST_TIMEOUT", "60")))
    parser.add_argument("--warmup", type=int, default=int(os.environ.get("TEST_WARMUP_SECONDS", str(MIN_WARMUP_SECONDS))))
    parser.add_argument("--service", default=os.environ.get("TEST_DEMO_SERVICE", "service-a"))
    parser.add_argument("--report-dir", default=str(LIB_ROOT / "reports"))
    parser.add_argument(
        "--skip-ai-chat",
        action="store_true",
        default=os.environ.get("TEST_SKIP_AI_CHAT", "0") == "1",
        help="skip AI chat tool parameter integration tests",
    )
    parser.add_argument(
        "--ai-chat-rounds",
        type=int,
        default=int(os.environ.get("TEST_AI_CHAT_ROUNDS", "2")),
        help="rounds per AI chat tool case (default: 2)",
    )
    parser.add_argument(
        "--ai-chat-poll-timeout",
        type=float,
        default=float(os.environ.get("TEST_AI_CHAT_POLL_TIMEOUT", "180")),
        help="seconds to wait for each AI chat session",
    )
    parser.add_argument(
        "--skip-ai-memory",
        action="store_true",
        default=os.environ.get("TEST_SKIP_AI_MEMORY", "0") == "1",
        help="skip AI session memory integration tests (also skipped when DEEPSEEK_API_KEY unset)",
    )
    parser.add_argument(
        "--skip-ai-provider-formats",
        action="store_true",
        default=os.environ.get("TEST_SKIP_AI_PROVIDER_FORMATS", "0") == "1",
        help="skip OpenAI/Anthropic provider format tool-arg tests "
        "(also skipped when DEEPSEEK_API_KEY and MINIMAX_API_KEY unset)",
    )
    parser.add_argument(
        "--ai-provider-format-rounds",
        type=int,
        default=int(os.environ.get("TEST_AI_PROVIDER_FORMAT_ROUNDS", "1")),
        help="rounds per provider-format tool case (default: 1)",
    )
    parser.add_argument(
        "--ai-provider-format-poll-timeout",
        type=float,
        default=float(os.environ.get("TEST_AI_PROVIDER_FORMAT_POLL_TIMEOUT", "180")),
        help="seconds to wait for each provider-format chat session",
    )
    parser.add_argument(
        "--ai-memory-poll-timeout",
        type=float,
        default=float(os.environ.get("TEST_AI_MEMORY_POLL_TIMEOUT", "240")),
        help="seconds to wait for each AI memory turn",
    )
    parser.add_argument(
        "--skip-ai-brain-async",
        action="store_true",
        default=os.environ.get("TEST_SKIP_AI_BRAIN_ASYNC", "0") == "1",
        help="skip AI brain async routing tests (also skipped when DEEPSEEK_API_KEY unset)",
    )
    parser.add_argument(
        "--ai-brain-async-poll-timeout",
        type=float,
        default=float(os.environ.get("TEST_AI_BRAIN_ASYNC_POLL_TIMEOUT", "300")),
        help="seconds to wait for each AI brain async routing session",
    )
    args = parser.parse_args()

    min_warmup = int(os.environ.get("TEST_MIN_WARMUP_SECONDS", str(MIN_WARMUP_SECONDS)))
    if args.warmup <= 0:
        warmup = 0
    else:
        warmup = max(args.warmup, min_warmup)
    base = args.base_url.rstrip("/")
    report_dir = Path(args.report_dir)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")

    print(f"[test] login {base} ...")
    token = login(base, args.username, args.password, args.timeout)

    ingest_uptime, warmup_waited = wait_for_ingest_warmup(warmup)

    frm_ms, to_ms = aligned_query_window()
    wait_for_demo_data_in_window(base, token, args.service, frm_ms, to_ms, max(min(warmup, 120), 60))

    cases = build_cases(frm_ms, to_ms)
    inject_checkout_trace_ids(base, token, cases, frm_ms, to_ms, args.timeout)
    print(f"[test] running {len(cases)} cases on last {QUERY_WINDOW_MS // 1000}s window ...")
    report = run_cases(base, token, cases, args.timeout)
    report.min_warmup_seconds = warmup
    report.warmup_waited_seconds = warmup_waited
    report.ingest_uptime_seconds = ingest_uptime
    report.query_from_ms = frm_ms
    report.query_to_ms = to_ms

    if not args.skip_ai_chat:
        if is_llm_ready(base, token, args.timeout):
            print(
                f"[test] running AI chat tool loop: {args.ai_chat_rounds} rounds x "
                f"{len(AI_CHAT_QUESTIONS)} questions ..."
            )
            ai_results = run_ai_chat_tool_loop(
                base,
                token,
                rounds=args.ai_chat_rounds,
                poll_timeout_sec=args.ai_chat_poll_timeout,
            )
            for item in ai_results:
                report.results.append(
                    CaseResult(
                        module=MODULE_AI_PLATFORM,
                        group=GROUP_DATA_EXPERT,
                        name=item.question,
                        path=f"/webapi/api/v1/ai/sessions/{item.session_id or '-'}/messages",
                        method="CHAT",
                        ok=item.ok,
                        http_status=200 if item.ok else 0,
                        elapsed_ms=item.elapsed_ms,
                        detail=item.detail,
                        expected_file="ai-chat/no-parameter-validation-errors",
                        expected_json="no Parameter validation failed",
                    )
                )
                report.total += 1
                if item.ok:
                    report.passed += 1
                else:
                    report.failed += 1
        else:
            print("[test] skip AI chat: no enabled LLM provider with API key configured")

    if not args.skip_ai_provider_formats:
        formats = available_provider_formats(base, token)
        if formats:
            labels = ", ".join(f"{p.label}({p.provider_code})" for p in formats)
            print(
                f"[test] running AI provider format cases: {labels}; "
                f"{args.ai_provider_format_rounds} rounds x "
                f"{len(FORMAT_QUESTIONS)} questions ..."
            )
            format_results = run_ai_provider_format_cases(
                base,
                token,
                rounds=args.ai_provider_format_rounds,
                poll_timeout_sec=args.ai_provider_format_poll_timeout,
            )
            for item in format_results:
                report.results.append(
                    CaseResult(
                        module=MODULE_AI_PLATFORM,
                        group=GROUP_PROVIDER_FORMATS,
                        name=item.question,
                        path=f"/webapi/api/v1/ai/sessions/{item.session_id or '-'}/messages",
                        method="CHAT",
                        ok=item.ok,
                        http_status=200 if item.ok else 0,
                        elapsed_ms=item.elapsed_ms,
                        detail=item.detail,
                        expected_file="ai-provider-formats/no-parameter-validation-errors",
                        expected_json="OpenAI/Anthropic formats: no Parameter validation failed",
                    )
                )
                report.total += 1
                if item.ok:
                    report.passed += 1
                else:
                    report.failed += 1
        else:
            print(
                f"[test] skip AI provider formats: set {PROVIDER_FORMAT_DEEPSEEK_ENV}/"
                f"{PROVIDER_FORMAT_MINIMAX_ENV}, or enable matching providers in UI"
            )

    if not args.skip_ai_memory:
        if deepseek_api_key():
            print(
                f"[test] running AI session memory cases "
                f"(env {DEEPSEEK_API_KEY_ENV} set) ..."
            )
            memory_results = run_ai_session_memory_cases(
                base,
                token,
                poll_timeout_sec=args.ai_memory_poll_timeout,
            )
            for item in memory_results:
                report.results.append(
                    CaseResult(
                        module=MODULE_AI_PLATFORM,
                        group=GROUP_SESSION_MEMORY,
                        name=item.name,
                        path=f"/webapi/api/v1/ai/sessions/{item.session_id or '-'}/messages",
                        method="CHAT",
                        ok=item.ok,
                        http_status=200 if item.ok else 0,
                        elapsed_ms=item.elapsed_ms,
                        detail=item.detail,
                        expected_file="ai-session-memory/multi-turn-context",
                        expected_json="same expert retains prior turn context",
                    )
                )
                report.total += 1
                if item.ok:
                    report.passed += 1
                else:
                    report.failed += 1
        else:
            print(
                f"[test] skip AI session memory: set {DEEPSEEK_API_KEY_ENV} to enable"
            )

    if not args.skip_ai_brain_async:
        if deepseek_api_key():
            print(
                f"[test] running AI brain async routing cases "
                f"(env {DEEPSEEK_API_KEY_ENV} set) ..."
            )
            brain_async_results = run_ai_brain_async_routing_cases(
                base,
                token,
                poll_timeout_sec=args.ai_brain_async_poll_timeout,
            )
            for item in brain_async_results:
                report.results.append(
                    CaseResult(
                        module=MODULE_AI_PLATFORM,
                        group=GROUP_BRAIN_ASYNC,
                        name=item.name,
                        path=f"/webapi/api/v1/ai/sessions/{item.session_id or '-'}/messages",
                        method="CHAT",
                        ok=item.ok,
                        http_status=200 if item.ok else 0,
                        elapsed_ms=item.elapsed_ms,
                        detail=item.detail,
                        expected_file="ai-brain-async/session-parallel-fan-in",
                        expected_json="same-session parallel fan-in; cross-session isolation",
                    )
                )
                report.total += 1
                if item.ok:
                    report.passed += 1
                else:
                    report.failed += 1
        else:
            print(
                f"[test] skip AI brain async routing: set {DEEPSEEK_API_KEY_ENV} to enable"
            )

    json_path = report_dir / f"report-{stamp}.json"
    html_path = report_dir / f"report-{stamp}.html"
    latest_json = report_dir / "report-latest.json"
    latest_html = report_dir / "report-latest.html"

    write_json_report(report, json_path)
    write_html_report(report, html_path)
    write_json_report(report, latest_json)
    write_html_report(report, latest_html)

    print(
        f"[test] {('PASS' if report.failed == 0 else 'FAIL')} "
        f"{report.passed}/{report.total} ({report.success_rate:.1f}%) "
        f"· {fmt_duration(test_duration_seconds(report))}"
    )
    print(
        f"[test] window: last {QUERY_WINDOW_MS // 1000}s "
        f"({fmt_ms(frm_ms)} ~ {fmt_ms(to_ms)})"
    )
    print(f"[test] JSON: {json_path}")
    print(f"[test] HTML: {html_path}")

    return 0 if report.failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
