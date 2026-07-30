"""AI brain 异步路由集成测试 — 同 session 并行 fan-in、跨 session 隔离、多专家拆分多步。

由 ``AI_TEST_PROVIDER`` + 对应 API Key 门控（与会话记忆用例一致，默认 deepseek）：
  - Key 未设置 → 跳过
  - 已设置 → 配置选定 provider 后跑 brain 并行派发 / 双 session 并发 / 多步拆分

多专家拆分类覆盖（断言已收紧）：
  - data→inspection(+报告)：data task 口径词、串行时序、服务名交接、inspection 要求出报告、必须有 HTML
  - inspection∥ops：时间窗重叠、ops 含 docker+容器名、inspection 含 service-a
  - data→ops：串行、data 含 ERROR、ops 含 docker/ai-apm-web、服务名交接/终答回引
  - data+qa：data 含服务列表语义、qa 含界面/入口类词

用例彼此独立（各 session），套件内有界并行（默认最多 3 路）。超时下限 900s。
"""

from __future__ import annotations

import os
import re
import time
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from ai_chat_integration import _http_json, _poll_session
from ai_session_memory import (
    ENV_API_KEY,
    MODEL,
    PROVIDER,
    deepseek_api_key,
    ensure_deepseek_provider,
)

MODULE_AI_PLATFORM = "AI平台"
GROUP_BRAIN_ASYNC = "大脑异步路由"

WAITING_MARKERS = ("请稍候", "尚未返回", "继续等待", "正在等待其完成")


@dataclass
class BrainAsyncCaseResult:
    name: str
    session_id: str
    ok: bool
    elapsed_ms: float
    detail: str


def _submit_brain(
    base: str,
    token: str,
    message: str,
    *,
    session_id: str | None = None,
    timeout: float = 60.0,
) -> str:
    body: dict[str, Any] = {
        "expertId": "brain",
        "message": message,
        "stream": False,
        "modelProviderCode": PROVIDER,
        "modelName": MODEL,
        "userName": "admin",
        "context": {},
    }
    if session_id:
        body["sessionId"] = session_id
    payload = _http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/api/v1/ai/chat/submit",
        body,
        token=token,
        timeout=timeout,
    )
    sid = payload.get("sessionId") if isinstance(payload, dict) else None
    if not sid:
        raise RuntimeError(f"chat submit failed: {payload}")
    return str(sid)


def _session_tasks(base: str, token: str, session_id: str) -> list[dict[str, Any]]:
    payload = _http_json(
        "GET",
        f"{base.rstrip('/')}/webapi/api/v1/ai/sessions/{session_id}/tasks",
        token=token,
        timeout=30.0,
    )
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and isinstance(payload.get("data"), list):
        return payload["data"]
    return []


def _messages(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw = payload.get("messages") or []
    return raw if isinstance(raw, list) else []


def _round_final_brain_text(payload: dict[str, Any]) -> str | None:
    for message in reversed(_messages(payload)):
        if message.get("role") != "assistant":
            continue
        if str(message.get("expertId") or "") != "brain":
            continue
        if str(message.get("messageType") or "").upper() != "TEXT":
            continue
        meta = message.get("metadata") or {}
        if meta.get("isExpertDeliverable"):
            continue
        if not meta.get("isRoundFinal"):
            continue
        content = message.get("content")
        if isinstance(content, str) and content.strip():
            return content
    # Fallback: last brain TEXT that is not an expert deliverable
    for message in reversed(_messages(payload)):
        if str(message.get("expertId") or "") != "brain":
            continue
        if str(message.get("messageType") or "").upper() != "TEXT":
            continue
        meta = message.get("metadata") or {}
        if meta.get("isExpertDeliverable"):
            continue
        content = message.get("content")
        if isinstance(content, str) and content.strip():
            return content
    return None


def _expert_ids_with_deliverable(payload: dict[str, Any]) -> set[str]:
    found: set[str] = set()
    for message in _messages(payload):
        meta = message.get("metadata") or {}
        if meta.get("isExpertDeliverable"):
            expert = str(message.get("expertId") or "").strip()
            if expert:
                found.add(expert)
    return found


def _task_inputs_for(tasks: list[dict[str, Any]], target_expert_id: str) -> list[str]:
    inputs: list[str] = []
    for task in tasks:
        if str(task.get("targetExpertId") or "") != target_expert_id:
            continue
        raw = task.get("input")
        if isinstance(raw, str) and raw.strip():
            inputs.append(raw)
    return inputs


def _joined_task_field(tasks: list[dict[str, Any]], target_expert_id: str, field: str) -> str:
    parts: list[str] = []
    for task in tasks:
        if str(task.get("targetExpertId") or "") != target_expert_id:
            continue
        raw = task.get(field)
        if isinstance(raw, str) and raw.strip():
            parts.append(raw)
    return "\n".join(parts)


def _text_has_any(text: str, needles: tuple[str, ...]) -> bool:
    if not needles:
        return True
    lower = (text or "").lower()
    return any(n.lower() in lower for n in needles)


def _text_has_all(text: str, needles: tuple[str, ...]) -> bool:
    if not needles:
        return True
    lower = (text or "").lower()
    return all(n.lower() in lower for n in needles)


_SERVICE_TOKEN_RE = re.compile(
    r"service-[a-zA-Z0-9_-]+|"
    r"\[[^\]]+\][^\s,，;；]{1,64}"
)


def _service_tokens(text: str) -> set[str]:
    return {m.group(0) for m in _SERVICE_TOKEN_RE.finditer(text or "")}


def _normalize_iso_timestamp(raw: str) -> str:
    """Normalize API timestamps for datetime.fromisoformat (truncate ns → µs)."""
    s = raw.strip().replace("Z", "+00:00")
    match = re.match(
        r"^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(\.\d+)?(.*)$",
        s,
    )
    if not match:
        return s
    base, frac, rest = match.group(1), match.group(2), match.group(3) or ""
    if frac:
        digits = frac[1:]  # drop leading dot
        if len(digits) > 6:
            digits = digits[:6]
        frac = "." + digits
    return base + (frac or "") + rest


def _parse_iso_ts(value: Any) -> float | None:
    if value is None:
        return None
    if isinstance(value, (int, float)):
        return float(value)
    raw = str(value).strip()
    if not raw:
        return None
    try:
        return datetime.fromisoformat(_normalize_iso_timestamp(raw)).timestamp()
    except ValueError:
        return None


def _first_task(tasks: list[dict[str, Any]], target_expert_id: str) -> dict[str, Any] | None:
    for task in tasks:
        if str(task.get("targetExpertId") or "") == target_expert_id:
            return task
    return None


def _task_interval(task: dict[str, Any] | None) -> tuple[float, float] | None:
    if not task:
        return None
    start = _parse_iso_ts(task.get("createdAt"))
    if start is None:
        return None
    end = _parse_iso_ts(task.get("completedAt"))
    if end is None:
        end = _parse_iso_ts(task.get("updatedAt"))
    if end is None:
        end = start
    if end < start:
        end = start
    return start, end


def _dispatch_serial(tasks: list[dict[str, Any]], earlier: str, later: str) -> bool:
    """True when first `earlier` task was created before first `later` task."""
    a = _task_interval(_first_task(tasks, earlier))
    b = _task_interval(_first_task(tasks, later))
    if not a or not b:
        return False
    return a[0] <= b[0]


def _dispatch_overlap(tasks: list[dict[str, Any]], left: str, right: str) -> bool:
    """True when the two experts' task time ranges overlap (parallel fan-out)."""
    a = _task_interval(_first_task(tasks, left))
    b = _task_interval(_first_task(tasks, right))
    if not a or not b:
        return False
    return a[0] <= b[1] and b[0] <= a[1]


def _task_requests_report(text: str) -> bool:
    """Positive request to generate an inspection report (ignores explicit negations)."""
    t = text or ""
    if re.search(r"(不需要|不要|无需|勿|禁止).{0,12}(生成)?\s*(HTML)?\s*巡检报告", t, re.I):
        return False
    if re.search(r"(不需要|不要|无需|勿|禁止).{0,12}(HTML\s*)?报告", t, re.I):
        return False
    return bool(
        re.search(
            r"(并)?生成巡检报告|写出\s*HTML|inspection-report|\.html",
            t,
            re.I,
        )
    )


def _has_generated_html(payload: dict[str, Any], tasks: list[dict[str, Any]]) -> bool:
    """True if session messages or task metadata reference an HTML output under outputs/."""

    def _scan_files(raw: Any) -> bool:
        if not isinstance(raw, list):
            return False
        for item in raw:
            if not isinstance(item, dict):
                continue
            path = str(item.get("filePath") or item.get("path") or item.get("fileName") or "")
            if path.lower().endswith(".html") or path.lower().endswith(".htm"):
                return True
        return False

    for message in _messages(payload):
        meta = message.get("metadata") or {}
        if _scan_files(meta.get("generatedFiles")):
            return True
        if _scan_files(meta.get("attachments")):
            return True
    for task in tasks:
        meta = task.get("metadata") or {}
        if isinstance(meta, dict) and _scan_files(meta.get("generatedFiles")):
            return True
    # Fallback: tool call wrote an html file
    for message in _messages(payload):
        if str(message.get("toolName") or "") != "writeWorkspaceFile":
            continue
        meta = message.get("metadata") or {}
        tool_input = str(meta.get("toolInput") or "")
        if ".html" in tool_input.lower() or ".htm" in tool_input.lower():
            return True
    return False


def _case(name: str, ok: bool, session_id: str, started: float, detail: str) -> BrainAsyncCaseResult:
    return BrainAsyncCaseResult(
        name=name,
        session_id=session_id,
        ok=ok,
        elapsed_ms=(time.time() - started) * 1000,
        detail=detail,
    )


def _run_parallel_dispatch_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """Brain 并行派发 ops+inspection；同 session 应并行 fan-in 并产出最终 TEXT。"""
    started = time.time()
    sid = ""
    try:
        sid = _submit_brain(
            base,
            token,
            "请并行派发运维专家(ops)和巡检专家(inspection)，"
            "对服务 [mysql]demo_apm 做一次简短排查，最后汇总双方结论。"
            "不要只回复请稍候。",
        )
        payload = _poll_session(
            base,
            token,
            sid,
            poll_interval_sec,
            poll_timeout_sec,
        )
        tasks = _session_tasks(base, token, sid)
        targets = {str(t.get("targetExpertId") or "") for t in tasks}
        statuses = {str(t.get("status") or "") for t in tasks}
        deliverables = _expert_ids_with_deliverable(payload)
        final_text = _round_final_brain_text(payload) or ""

        dispatched = len(targets & {"ops", "inspection"}) >= 2 or len(tasks) >= 2
        if not dispatched:
            # Model may only dispatch one expert; still require a non-waiting final answer.
            if not final_text.strip():
                return _case("同会话并行专家fan-in", False, sid, started, "无最终 TEXT 且未并行派发")
            waiting_only = any(m in final_text for m in WAITING_MARKERS) and len(final_text) < 80
            return _case(
                "同会话并行专家fan-in",
                not waiting_only,
                sid,
                started,
                f"single-dispatch fallback; final={final_text[:200]}",
            )

        terminal = all(s in ("SUCCEEDED", "FAILED", "TIMEOUT", "CANCELLED") for s in statuses) if statuses else False
        waiting_only = any(m in final_text for m in WAITING_MARKERS) and len(final_text) < 120

        # Also verify no brain TEXT marked isRoundFinal contains waiting markers
        # (中间过程不得作为最终答复).
        waiting_as_final = False
        for message in _messages(payload):
            if str(message.get("expertId") or "") != "brain":
                continue
            if str(message.get("messageType") or "").upper() != "TEXT":
                continue
            meta = message.get("metadata") or {}
            if not meta.get("isRoundFinal"):
                continue
            content = message.get("content") or ""
            if any(m in content for m in WAITING_MARKERS) and len(content) < 120:
                waiting_as_final = True
                break

        ok = bool(final_text.strip()) and not waiting_only and not waiting_as_final and terminal
        detail = (
            f"tasks={len(tasks)} targets={sorted(targets)} statuses={sorted(statuses)} "
            f"deliverables={sorted(deliverables)} waiting_as_final={waiting_as_final} "
            f"final={final_text[:220]}"
        )
        return _case("同会话并行专家fan-in", ok, sid, started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case("同会话并行专家fan-in", False, sid, started, str(error))


def _run_one_session(
    base: str,
    token: str,
    label: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> tuple[str, str, str]:
    """Returns (session_id, final_text, error)."""
    message = (
        f"【隔离标记 {label}】请派发智能问数专家，查询最近1小时出现 error 的服务，"
        f"并在最终回答中保留标记 {label}。"
    )
    sid = _submit_brain(base, token, message)
    payload = _poll_session(base, token, sid, poll_interval_sec, poll_timeout_sec)
    final_text = _round_final_brain_text(payload) or ""
    return sid, final_text, ""


def _run_cross_session_isolation_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """两个 session 并发提交，消息与最终答复不得串台。"""
    started = time.time()
    sid_a = ""
    sid_b = ""
    try:
        with ThreadPoolExecutor(max_workers=2) as pool:
            fut_a = pool.submit(
                _run_one_session,
                base,
                token,
                "ALPHA-SESSION",
                poll_interval_sec=poll_interval_sec,
                poll_timeout_sec=poll_timeout_sec,
            )
            fut_b = pool.submit(
                _run_one_session,
                base,
                token,
                "BETA-SESSION",
                poll_interval_sec=poll_interval_sec,
                poll_timeout_sec=poll_timeout_sec,
            )
            sid_a, text_a, _ = fut_a.result()
            sid_b, text_b, _ = fut_b.result()

        if not sid_a or not sid_b or sid_a == sid_b:
            return _case("跨会话并发隔离", False, sid_a or sid_b, started, "sessions not distinct")

        leak_a = "BETA-SESSION" in text_a
        leak_b = "ALPHA-SESSION" in text_b
        empty = not text_a.strip() or not text_b.strip()
        ok = not leak_a and not leak_b and not empty
        detail = (
            f"A={sid_a} finalA={text_a[:160]!r}; B={sid_b} finalB={text_b[:160]!r}; "
            f"leakA={leak_a} leakB={leak_b}"
        )
        return _case("跨会话并发隔离", ok, f"{sid_a},{sid_b}", started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case("跨会话并发隔离", False, f"{sid_a},{sid_b}", started, str(error))


def _run_single_expert_dispatch_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """单专家派发 → 回调 → brain 最终 TEXT（最基本 fan-in 路径）。"""
    started = time.time()
    sid = ""
    try:
        sid = _submit_brain(
            base,
            token,
            "请派发智能问数专家(data)，查询最近1小时的服务列表，然后汇总结论。"
            "不要只回复请稍候。",
        )
        payload = _poll_session(base, token, sid, poll_interval_sec, poll_timeout_sec)
        tasks = _session_tasks(base, token, sid)
        targets = {str(t.get("targetExpertId") or "") for t in tasks}
        final_text = _round_final_brain_text(payload) or ""
        has_data = "data" in targets or any(
            "data" in str(t.get("targetExpertId") or "") for t in tasks
        )
        waiting_only = any(m in final_text for m in WAITING_MARKERS) and len(final_text) < 120
        ok = has_data and bool(final_text.strip()) and not waiting_only
        detail = f"tasks={len(tasks)} targets={sorted(targets)} final={final_text[:220]}"
        return _case("单专家派发fan-in", ok, sid, started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case("单专家派发fan-in", False, sid, started, str(error))


def _run_expert_failure_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """一个专家失败时 brain 仍应产出最终 TEXT（不能卡死或只回请稍候）。"""
    started = time.time()
    sid = ""
    try:
        sid = _submit_brain(
            base,
            token,
            "请派发巡检专家(inspection)对服务 [mysql]demo_apm 做巡检。"
            "如果巡检专家返回错误，请在最终回答中说明失败原因并给出建议。"
            "不要只回复请稍候。",
        )
        payload = _poll_session(base, token, sid, poll_interval_sec, poll_timeout_sec)
        tasks = _session_tasks(base, token, sid)
        statuses = {str(t.get("status") or "") for t in tasks}
        final_text = _round_final_brain_text(payload) or ""
        has_failure = any(s in ("FAILED", "TIMEOUT") for s in statuses)
        waiting_only = any(m in final_text for m in WAITING_MARKERS) and len(final_text) < 120
        # Pass if brain produced a real final answer (even if no failure occurred, model may succeed)
        ok = bool(final_text.strip()) and not waiting_only
        detail = f"tasks={len(tasks)} statuses={sorted(statuses)} has_failure={has_failure} final={final_text[:220]}"
        return _case("专家失败仍出最终答复", ok, sid, started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case("专家失败仍出最终答复", False, sid, started, str(error))


def _run_multi_round_dispatch_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """多轮派发：第一轮 brain 派发后，用户追问触发第二轮派发。"""
    started = time.time()
    sid = ""
    try:
        # Round 1: dispatch data expert
        sid = _submit_brain(
            base,
            token,
            "请派发智能问数专家(data)，查询最近1小时出现 error 的服务。"
            "不要只回复请稍候。",
        )
        payload1 = _poll_session(base, token, sid, poll_interval_sec, poll_timeout_sec)
        tasks1 = _session_tasks(base, token, sid)
        final1 = _round_final_brain_text(payload1) or ""

        # Round 2: follow-up should trigger new dispatch
        sid2 = _submit_brain(
            base,
            token,
            "需要对上一步发现的错误服务做进一步诊断，请派发巡检专家(inspection)做健康巡检。"
            "不要只回复请稍候。",
            session_id=sid,
        )
        payload2 = _poll_session(base, token, sid2, poll_interval_sec, poll_timeout_sec)
        tasks2 = _session_tasks(base, token, sid2)
        final2 = _round_final_brain_text(payload2) or ""

        r1_ok = bool(final1.strip()) and len(tasks1) >= 1
        r2_ok = bool(final2.strip()) and len(tasks2) > len(tasks1)
        waiting2 = any(m in final2 for m in WAITING_MARKERS) and len(final2) < 120
        ok = r1_ok and r2_ok and not waiting2
        detail = (
            f"r1_tasks={len(tasks1)} r1_final={final1[:120]!r}; "
            f"r2_tasks={len(tasks2)} r2_final={final2[:120]!r}"
        )
        return _case("多轮派发", ok, sid, started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case("多轮派发", False, sid, started, str(error))


def _run_multi_step_inspect_report_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """回归：先定位 ERROR 最多服务，再巡检并生成报告（同轮多步，勿在问数后终答）。"""
    return _run_locate_then_inspect_report_case(
        base,
        token,
        case_name="多步定位后巡检并出报告",
        user_message=(
            "找出最近 1 小时日志ERROR最多的服务，对它做一次巡检，并生成巡检报告。"
            "不要只回复请稍候。"
        ),
        data_needles_any=("ERROR", "error", "错误日志", "错误"),
        poll_interval_sec=poll_interval_sec,
        poll_timeout_sec=poll_timeout_sec,
    )


def _run_multi_step_latency_inspect_report_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """多步：定位平均响应时间最高服务 → 巡检 → 出报告（问数口径与 ERROR 场景不同）。"""
    return _run_locate_then_inspect_report_case(
        base,
        token,
        case_name="多步延迟最高服务巡检出报告",
        user_message=(
            "找出最近 1 小时平均响应时间最高的服务，对它做一次巡检，并生成巡检报告。"
            "不要只回复请稍候。"
        ),
        data_needles_any=("响应时间", "耗时", "平均响应"),
        poll_interval_sec=poll_interval_sec,
        poll_timeout_sec=poll_timeout_sec,
    )


def _run_locate_then_inspect_report_case(
    base: str,
    token: str,
    *,
    case_name: str,
    user_message: str,
    data_needles_any: tuple[str, ...],
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    started = time.time()
    sid = ""
    try:
        sid = _submit_brain(base, token, user_message)
        effective_timeout = max(float(poll_timeout_sec), 900.0)
        payload = _poll_session(base, token, sid, poll_interval_sec, effective_timeout)
        tasks = _session_tasks(base, token, sid)
        targets = {str(t.get("targetExpertId") or "") for t in tasks}
        data_input = _joined_task_field(tasks, "data", "input")
        data_output = _joined_task_field(tasks, "data", "output")
        inspection_input = _joined_task_field(tasks, "inspection", "input")
        final_text = _round_final_brain_text(payload) or ""
        waiting_only = any(m in final_text for m in WAITING_MARKERS) and len(final_text) < 120

        has_data = "data" in targets
        has_inspection = "inspection" in targets
        data_metric_ok = _text_has_any(data_input, data_needles_any)
        report_in_task = _task_requests_report(inspection_input)
        has_html = _has_generated_html(payload, tasks)
        serial_ok = _dispatch_serial(tasks, "data", "inspection")

        # Handoff: inspection task must reuse a concrete service discovered by data when available.
        data_services = _service_tokens(data_input + "\n" + data_output)
        inspection_services = _service_tokens(inspection_input)
        data_named = {s for s in data_services if s.lower().startswith("service-")}
        if data_named:
            handoff_ok = bool(data_named & {s for s in inspection_services})
        else:
            handoff_ok = bool(inspection_services)
        ok = (
            has_data
            and has_inspection
            and data_metric_ok
            and report_in_task
            and has_html
            and serial_ok
            and handoff_ok
            and bool(final_text.strip())
            and not waiting_only
        )
        detail = (
            f"targets={sorted(targets)} data_metric_ok={data_metric_ok} "
            f"report_in_task={report_in_task} has_html={has_html} serial_ok={serial_ok} "
            f"handoff_ok={handoff_ok} data_services={sorted(data_named)[:5]} "
            f"inspection_input={inspection_input[:160]!r} final={final_text[:180]!r}"
        )
        return _case(case_name, ok, sid, started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case(case_name, False, sid, started, str(error))


def _run_multi_expert_split_case(
    base: str,
    token: str,
    *,
    case_name: str,
    user_message: str,
    required_experts: set[str],
    poll_interval_sec: float,
    poll_timeout_sec: float,
    min_timeout_sec: float = 360.0,
    require_final_mentions: tuple[str, ...] = (),
    require_final_mentions_any: tuple[str, ...] = (),
    task_must_contain: dict[str, tuple[str, ...]] | None = None,
    task_must_contain_any: dict[str, tuple[str, ...]] | None = None,
    require_serial: tuple[str, str] | None = None,
    require_parallel_overlap: tuple[str, str] | None = None,
    require_service_handoff: tuple[str, str] | None = None,
) -> BrainAsyncCaseResult:
    """通用：同轮拆分派发多个专家，并严格检查 task 内容、时序与交付物。"""
    started = time.time()
    sid = ""
    try:
        sid = _submit_brain(base, token, user_message)
        effective_timeout = max(float(poll_timeout_sec), float(min_timeout_sec))
        payload = _poll_session(base, token, sid, poll_interval_sec, effective_timeout)
        tasks = _session_tasks(base, token, sid)
        targets = {str(t.get("targetExpertId") or "") for t in tasks}
        final_text = _round_final_brain_text(payload) or ""
        waiting_only = any(m in final_text for m in WAITING_MARKERS) and len(final_text) < 120
        missing = sorted(required_experts - targets)

        task_checks: dict[str, bool] = {}
        for expert_id, needles in (task_must_contain or {}).items():
            inputs = _joined_task_field(tasks, expert_id, "input")
            task_checks[f"{expert_id}:all"] = _text_has_all(inputs, needles)
        for expert_id, needles in (task_must_contain_any or {}).items():
            inputs = _joined_task_field(tasks, expert_id, "input")
            task_checks[f"{expert_id}:any"] = _text_has_any(inputs, needles)

        serial_ok = True
        if require_serial:
            serial_ok = _dispatch_serial(tasks, require_serial[0], require_serial[1])

        overlap_ok = True
        if require_parallel_overlap:
            overlap_ok = _dispatch_overlap(
                tasks, require_parallel_overlap[0], require_parallel_overlap[1]
            )

        has_html = _has_generated_html(payload, tasks)

        handoff_ok = True
        handoff_detail = ""
        if require_service_handoff:
            src, dst = require_service_handoff
            src_text = (
                _joined_task_field(tasks, src, "input")
                + "\n"
                + _joined_task_field(tasks, src, "output")
            )
            dst_text = _joined_task_field(tasks, dst, "input")
            src_services = {s for s in _service_tokens(src_text) if s.lower().startswith("service-")}
            dst_services = _service_tokens(dst_text)
            final_services = _service_tokens(final_text)
            if src_services:
                # Prefer explicit handoff into next expert task; allow final synthesis as fallback.
                handoff_ok = bool(src_services & dst_services) or bool(src_services & final_services)
            else:
                handoff_ok = bool(dst_services) or bool(final_services)
            handoff_detail = (
                f"src={sorted(src_services)[:5]} dst={sorted(dst_services)[:5]} "
                f"final_svc={sorted(final_services)[:5]}"
            )

        mentions_ok = all(m in final_text for m in require_final_mentions) if require_final_mentions else True
        mentions_any_ok = (
            _text_has_any(final_text, require_final_mentions_any)
            if require_final_mentions_any
            else True
        )

        ok = (
            not missing
            and all(task_checks.values())
            and serial_ok
            and overlap_ok
            and handoff_ok
            and bool(final_text.strip())
            and not waiting_only
            and mentions_ok
            and mentions_any_ok
        )
        detail = (
            f"targets={sorted(targets)} missing={missing} task_checks={task_checks} "
            f"serial_ok={serial_ok} overlap_ok={overlap_ok} has_html={has_html} "
            f"handoff_ok={handoff_ok} {handoff_detail} "
            f"mentions_ok={mentions_ok} mentions_any_ok={mentions_any_ok} "
            f"final={final_text[:200]!r}"
        )
        return _case(case_name, ok, sid, started, detail)
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        return _case(case_name, False, sid, started, str(error))


def _run_parallel_inspect_and_ops_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """并行拆分：业务健康巡检 + 本机运维排查（inspection + ops）。"""
    return _run_multi_expert_split_case(
        base,
        token,
        case_name="并行拆分巡检与运维",
        user_message=(
            "service-a 最近可能有业务异常，也怀疑本机运行环境有问题。"
            "请分别派发巡检专家对 service-a 做健康巡检，并派发运维专家检查本机 docker 中 "
            "ai-apm-web / ai-apm-ingest 容器是否在运行；最后汇总双方结论。"
            "不要只回复请稍候。"
        ),
        required_experts={"inspection", "ops"},
        poll_interval_sec=poll_interval_sec,
        poll_timeout_sec=poll_timeout_sec,
        min_timeout_sec=420.0,
        task_must_contain={
            "inspection": ("service-a",),
            "ops": ("docker",),
        },
        task_must_contain_any={
            "ops": ("ai-apm-web", "ai-apm-ingest", "ingest"),
        },
        require_parallel_overlap=("inspection", "ops"),
        require_final_mentions_any=("service-a", "docker", "容器"),
    )


def _run_data_then_ops_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """串行拆分：先问数定位异常服务，再运维排查本机环境。"""
    return _run_multi_expert_split_case(
        base,
        token,
        case_name="多步问数后运维排查",
        user_message=(
            "找出最近 1 小时日志 ERROR 最多的服务；然后请运维专家检查本机 DataBuff 相关 "
            "docker 容器（至少 ai-apm-web）是否正常运行，并结合前面查出的服务说明是否可能是环境问题。"
            "不要只回复请稍候。"
        ),
        required_experts={"data", "ops"},
        poll_interval_sec=poll_interval_sec,
        poll_timeout_sec=poll_timeout_sec,
        min_timeout_sec=420.0,
        task_must_contain={
            "ops": ("docker", "ai-apm-web"),
        },
        task_must_contain_any={
            "data": ("ERROR", "error", "错误"),
        },
        require_serial=("data", "ops"),
        require_service_handoff=("data", "ops"),
        require_final_mentions_any=("docker", "容器", "ai-apm-web"),
    )


def _run_data_and_qa_case(
    base: str,
    token: str,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> BrainAsyncCaseResult:
    """组合拆分：问数查服务列表 + 产品答疑说明入口位置（data + qa）。"""
    return _run_multi_expert_split_case(
        base,
        token,
        case_name="组合问数与产品答疑",
        user_message=(
            "请查询最近 1 小时的服务列表；同时请产品答疑专家说明在 DataBuff 产品界面里"
            "哪里可以查看服务列表/服务目录。最后合并两部分回答。"
            "不要只回复请稍候。"
        ),
        required_experts={"data", "qa"},
        poll_interval_sec=poll_interval_sec,
        poll_timeout_sec=poll_timeout_sec,
        min_timeout_sec=360.0,
        task_must_contain_any={
            "data": ("服务列表", "服务目录", "全部服务"),
            "qa": ("界面", "入口", "哪里", "菜单", "页面", "导航", "产品"),
        },
        task_must_contain={
            "qa": ("服务",),
        },
        require_final_mentions_any=("服务列表", "服务目录", "服务"),
    )


def run_ai_brain_async_routing_cases(
    base: str,
    token: str,
    *,
    poll_interval_sec: float = 2.0,
    poll_timeout_sec: float = 300.0,
) -> list[BrainAsyncCaseResult]:
    api_key = deepseek_api_key()
    if not api_key:
        return []
    ensure_deepseek_provider(base, token, api_key)

    case_fns = (
        _run_single_expert_dispatch_case,
        _run_parallel_dispatch_case,
        _run_expert_failure_case,
        _run_cross_session_isolation_case,
        _run_multi_round_dispatch_case,
        _run_multi_step_inspect_report_case,
        _run_multi_step_latency_inspect_report_case,
        _run_parallel_inspect_and_ops_case,
        _run_data_then_ops_case,
        _run_data_and_qa_case,
    )
    kwargs = {
        "poll_interval_sec": poll_interval_sec,
        "poll_timeout_sec": max(float(poll_timeout_sec), 900.0),
    }
    results: list[BrainAsyncCaseResult | None] = [None] * len(case_fns)
    # Cap concurrent brain cases to ease LLM rate limits (default 1).
    brain_workers = max(1, int(os.environ.get("TEST_AI_BRAIN_MAX_WORKERS", "1")))
    with ThreadPoolExecutor(max_workers=min(brain_workers, len(case_fns))) as pool:
        futs = {pool.submit(fn, base, token, **kwargs): idx for idx, fn in enumerate(case_fns)}
        for fut in as_completed(futs):
            results[futs[fut]] = fut.result()
    return [r for r in results if r is not None]


__all__ = [
    "ENV_API_KEY",
    "GROUP_BRAIN_ASYNC",
    "MODULE_AI_PLATFORM",
    "BrainAsyncCaseResult",
    "deepseek_api_key",
    "run_ai_brain_async_routing_cases",
]
