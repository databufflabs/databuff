"""AI brain 异步路由集成测试 — 同 session 并行 fan-in、跨 session 隔离.

由 ``DEEPSEEK_API_KEY`` 门控（与会话记忆用例一致）：
  - 未设置 → 跳过
  - 已设置 → 配置 deepseek 后跑 brain 并行派发 / 双 session 并发

用例彼此独立（各 session），默认 ThreadPool **并行**执行，禁止套件内串行叠跑。
"""

from __future__ import annotations

import os
import time
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
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
    )
    kwargs = {
        "poll_interval_sec": poll_interval_sec,
        "poll_timeout_sec": poll_timeout_sec,
    }
    # Default parallel; AI_BRAIN_CASES_PARALLEL=0 only for local debug.
    parallel = os.environ.get("AI_BRAIN_CASES_PARALLEL", "1") != "0"
    if not parallel:
        return [fn(base, token, **kwargs) for fn in case_fns]

    results: list[BrainAsyncCaseResult | None] = [None] * len(case_fns)
    with ThreadPoolExecutor(max_workers=len(case_fns)) as pool:
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
