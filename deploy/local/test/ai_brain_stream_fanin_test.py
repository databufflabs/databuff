#!/usr/bin/env python3
"""Real-model integration regression for brain stream/fan-in semantics.

This test intentionally talks to the running ``deploy/local`` stack and a real LLM.
It covers the two protocol boundaries that unit tests cannot prove end to end:

1. A completed child result is consumed by one brain stream round.  The terminal
   response must not be persisted once as ``pre_tool_text`` and then produced again
   by a second chat call.
2. With two pending children, the first callback remains ``REASONING``; only the
   callback that drains the pending set may create round-final ``TEXT``.
"""

from __future__ import annotations

import os
import sys
import time
import urllib.error
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urlparse


SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[2]
SHARED_TEST_LIB = REPO_ROOT / "deploy" / "test" / "lib"
sys.path.insert(0, str(SHARED_TEST_LIB))

from ai_chat_integration import _http_json, _poll_session  # noqa: E402
from ai_session_memory import (  # noqa: E402
    ENV_API_KEY,
    MODEL,
    PROVIDER,
    deepseek_api_key,
    ensure_test_llm_provider,
)
from run_tests import login  # noqa: E402


TERMINAL_TASK_STATUSES = {"SUCCEEDED", "FAILED", "TIMEOUT", "CANCELLED"}
DUPLICATE_CALLBACK_MARKERS = (
    "重复回调",
    "重复回传",
    "重复返回",
    "重复传递",
    "重复提交",
    "重复通知",
    "重复内容",
    "不再重复",
    "无需重复",
    "已经处理过",
    "已处理过",
    "duplicate callback",
    "duplicate delivery",
    "duplicate result",
    "already delivered",
)


@dataclass(frozen=True)
class CaseResult:
    name: str
    session_id: str
    elapsed_ms: float
    detail: str


def _messages(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw = payload.get("messages") or []
    return raw if isinstance(raw, list) else []


def _metadata(message: dict[str, Any]) -> dict[str, Any]:
    raw = message.get("metadata") or {}
    return raw if isinstance(raw, dict) else {}


def _position(message: dict[str, Any], fallback: int) -> int:
    try:
        return int(message.get("messageIndex"))
    except (TypeError, ValueError):
        return fallback


def _ordered(payload: dict[str, Any]) -> list[tuple[int, dict[str, Any]]]:
    return [
        (_position(message, index), message)
        for index, message in enumerate(_messages(payload), start=1)
    ]


def _is_brain(message: dict[str, Any]) -> bool:
    return str(message.get("expertId") or "") == "brain"


def _message_type(message: dict[str, Any]) -> str:
    return str(message.get("messageType") or "").upper()


def _content(message: dict[str, Any]) -> str:
    raw = message.get("content")
    return raw if isinstance(raw, str) else ""


def _submit_brain(base: str, token: str, message: str) -> str:
    payload = _http_json(
        "POST",
        f"{base}/webapi/api/v1/ai/chat/submit",
        {
            "expertId": "brain",
            "message": message,
            "stream": False,
            "modelProviderCode": PROVIDER,
            "modelName": MODEL,
            "userName": "admin",
            "context": {},
        },
        token=token,
        timeout=60.0,
    )
    session_id = payload.get("sessionId") if isinstance(payload, dict) else None
    if not session_id:
        raise AssertionError(f"brain submit returned no sessionId: {payload}")
    return str(session_id)


def _session_tasks(base: str, token: str, session_id: str) -> list[dict[str, Any]]:
    payload = _http_json(
        "GET",
        f"{base}/webapi/api/v1/ai/sessions/{session_id}/tasks",
        token=token,
        timeout=30.0,
    )
    if isinstance(payload, list):
        return payload
    if isinstance(payload, dict) and isinstance(payload.get("data"), list):
        return payload["data"]
    return []


def _deliverables(payload: dict[str, Any]) -> list[tuple[int, dict[str, Any]]]:
    return [
        (position, message)
        for position, message in _ordered(payload)
        if _metadata(message).get("isExpertDeliverable") is True
    ]


def _round_final_texts_after(
    payload: dict[str, Any], position_exclusive: int
) -> list[tuple[int, dict[str, Any]]]:
    return [
        (position, message)
        for position, message in _ordered(payload)
        if position > position_exclusive
        and _is_brain(message)
        and _message_type(message) == "TEXT"
        and _metadata(message).get("isRoundFinal") is True
    ]


def _terminal_pre_tool_reasoning(
    payload: dict[str, Any], start_exclusive: int, final_exclusive: int
) -> list[tuple[int, str]]:
    ordered = _ordered(payload)
    orphaned: list[tuple[int, str]] = []
    for position, message in ordered:
        if not start_exclusive < position < final_exclusive:
            continue
        if not _is_brain(message) or _message_type(message) != "REASONING":
            continue
        if str(_metadata(message).get("source") or "") != "pre_tool_text":
            continue
        has_later_tool_call = any(
            position < later_position < final_exclusive
            and _is_brain(later)
            and _message_type(later) == "TOOL_CALL"
            for later_position, later in ordered
        )
        if not has_later_tool_call:
            orphaned.append((position, _content(message)[:160]))
    return orphaned


def _marker_occurrences(
    payload: dict[str, Any], marker: str, start_inclusive: int, end_inclusive: int
) -> int:
    return sum(
        _content(message).count(marker)
        for position, message in _ordered(payload)
        if start_inclusive <= position <= end_inclusive
        and _is_brain(message)
        # Real reasoning models commonly quote the instruction marker in private thinking.
        # The broken stream->chat path is observable as pre_tool_text + final TEXT, so only
        # exclude source=thinking from the protocol-level duplication assertion.
        and str(_metadata(message).get("source") or "") != "thinking"
    )


def _assert_no_duplicate_callback_text(
    payload: dict[str, Any], start_inclusive: int, end_inclusive: int
) -> None:
    text = "\n".join(
        _content(message)
        for position, message in _ordered(payload)
        if start_inclusive <= position <= end_inclusive and _is_brain(message)
    ).lower()
    hits = [marker for marker in DUPLICATE_CALLBACK_MARKERS if marker.lower() in text]
    assert not hits, f"brain reported duplicate callback/content: {hits}"


def _assert_unique_deliverable_task_ids(
    deliverables: list[tuple[int, dict[str, Any]]]
) -> None:
    task_ids = [str(_metadata(message).get("taskId") or "") for _, message in deliverables]
    assert all(task_ids), f"expert deliverable missing taskId: {task_ids}"
    duplicates = sorted({task_id for task_id in task_ids if task_ids.count(task_id) > 1})
    assert not duplicates, f"same expert task delivered more than once: {duplicates}"


def _assert_one_visible_brain_reply_per_callback(
    payload: dict[str, Any], deliverables: list[tuple[int, dict[str, Any]]]
) -> dict[str, list[int]]:
    """Require one protocol reply for each child callback, keyed by taskId.

    Private ``source=thinking`` messages are model internals and do not count.  A broken
    stream->chat fallback produces two non-thinking artifacts for the same callback --
    typically terminal ``pre_tool_text`` from stream plus round-final ``TEXT`` from chat.
    """
    ordered = _ordered(payload)
    replies_by_task: dict[str, list[int]] = {}
    for _, deliverable in deliverables:
        task_id = str(_metadata(deliverable).get("taskId") or "")
        reply_positions = [
            position
            for position, message in ordered
            if _is_brain(message)
            and str(_metadata(message).get("taskId") or "") == task_id
            and _message_type(message) in {"REASONING", "TEXT"}
            and str(_metadata(message).get("source") or "") != "thinking"
            and bool(_content(message).strip())
        ]
        replies_by_task[task_id] = reply_positions
        assert len(reply_positions) == 1, (
            "expert callback must produce exactly one non-thinking brain reply; "
            f"taskId={task_id} replyPositions={reply_positions}"
        )
    return replies_by_task


def _assert_terminal_tasks(tasks: list[dict[str, Any]]) -> None:
    statuses = [str(task.get("status") or "") for task in tasks]
    assert statuses, "session produced no expert task"
    assert all(status in TERMINAL_TASK_STATUSES for status in statuses), (
        f"non-terminal expert tasks: {statuses}"
    )


def _run_single_child_stream_once(
    base: str, token: str, poll_interval: float, poll_timeout: float
) -> CaseResult:
    started = time.time()
    marker = f"LOCAL_STREAM_ONCE_{uuid.uuid4().hex[:12]}"
    session_id = _submit_brain(
        base,
        token,
        "请只派发一次智能问数专家(data)，查询最近1小时服务列表。"
        "不要派发其他专家，也不要重复处理同一个专家结果。"
        f"只有收到专家结果后的最终答复才允许包含标记 {marker}，"
        "任何中间思考、派发说明和等待说明都不要包含该标记，最终答复中该标记也只能出现一次。",
    )
    payload = _poll_session(base, token, session_id, poll_interval, poll_timeout)
    tasks = _session_tasks(base, token, session_id)
    _assert_terminal_tasks(tasks)

    targets = [str(task.get("targetExpertId") or "") for task in tasks]
    assert targets == ["data"], f"expected exactly one data task, got {targets}"

    deliverables = _deliverables(payload)
    assert len(deliverables) == 1, f"expected one expert deliverable, got {len(deliverables)}"
    _assert_unique_deliverable_task_ids(deliverables)
    deliverable_position, deliverable = deliverables[0]
    assert str(deliverable.get("expertId") or "") == "data", (
        f"expected data deliverable, got {deliverable.get('expertId')}"
    )
    assert _metadata(deliverable).get("isRoundFinal") is False

    finals = _round_final_texts_after(payload, deliverable_position)
    assert len(finals) == 1, f"expected one round-final TEXT after deliverable, got {len(finals)}"
    final_position, final_message = finals[0]
    marker_count = _marker_occurrences(
        payload, marker, deliverable_position + 1, final_position
    )
    assert marker_count == 1, (
        f"expected one real-model final marker after deliverable, got {marker_count}"
    )
    orphaned = _terminal_pre_tool_reasoning(
        payload, deliverable_position, final_position
    )
    assert not orphaned, (
        "stream terminal reply was persisted as pre_tool_text before final TEXT; "
        f"possible stream->chat double invocation: {orphaned}"
    )
    _assert_no_duplicate_callback_text(payload, deliverable_position + 1, final_position)
    callback_replies = _assert_one_visible_brain_reply_per_callback(payload, deliverables)

    final_text = _content(final_message).replace("\n", " ")[:180]
    return CaseResult(
        name="single child result uses one stream round",
        session_id=session_id,
        elapsed_ms=(time.time() - started) * 1000,
        detail=(
            f"tasks={targets} deliverableIndex={deliverable_position} "
            f"finalIndex={final_position} markerCount={marker_count} "
            f"orphanPreTool={len(orphaned)} callbackReplies={callback_replies} "
            f"final={final_text!r}"
        ),
    )


def _run_non_last_reasoning_last_final(
    base: str, token: str, poll_interval: float, poll_timeout: float
) -> CaseResult:
    started = time.time()
    marker = f"LOCAL_FANIN_FINAL_{uuid.uuid4().hex[:12]}"
    session_id = _submit_brain(
        base,
        token,
        "请在同一轮并行且各只派发一次以下两个任务："
        "智能问数专家(data)查询最近1小时服务列表；"
        "巡检专家(inspection)巡检服务 [mysql]demo_apm。"
        "必须等待两个专家都返回后再汇总；第一个专家返回时只能继续推理，不能给最终答复。"
        f"只有汇总两位专家结论的最终答复才允许包含标记 {marker}，"
        "中间思考和等待说明不要包含该标记，最终答复中该标记只能出现一次。",
    )
    payload = _poll_session(base, token, session_id, poll_interval, poll_timeout)
    tasks = _session_tasks(base, token, session_id)
    _assert_terminal_tasks(tasks)

    targets = [str(task.get("targetExpertId") or "") for task in tasks]
    assert len(targets) == 2 and set(targets) == {"data", "inspection"}, (
        f"expected exactly data+inspection tasks, got {targets}"
    )
    statuses = [str(task.get("status") or "") for task in tasks]
    assert all(status == "SUCCEEDED" for status in statuses), (
        f"both experts must succeed for classification test, got {statuses}"
    )

    deliverables = _deliverables(payload)
    assert len(deliverables) == 2, f"expected two expert deliverables, got {len(deliverables)}"
    _assert_unique_deliverable_task_ids(deliverables)
    assert {str(message.get("expertId") or "") for _, message in deliverables} == {
        "data",
        "inspection",
    }
    assert all(_metadata(message).get("isRoundFinal") is False for _, message in deliverables)

    first_position, first_deliverable = deliverables[0]
    last_position, _ = deliverables[-1]
    first_task_id = str(_metadata(first_deliverable).get("taskId") or "")
    between = [
        (position, message)
        for position, message in _ordered(payload)
        if first_position < position < last_position and _is_brain(message)
    ]
    intermediate_reasoning = [
        (position, message)
        for position, message in between
        if _message_type(message) == "REASONING"
        and (
            not first_task_id
            or str(_metadata(message).get("taskId") or "") == first_task_id
        )
    ]
    assert intermediate_reasoning, (
        "first child callback did not produce brain REASONING before the second deliverable"
    )
    premature_finals = [
        position
        for position, message in between
        if _message_type(message) == "TEXT"
        and _metadata(message).get("isRoundFinal") is True
    ]
    assert not premature_finals, (
        f"non-last child callback produced premature final TEXT at {premature_finals}"
    )

    finals = _round_final_texts_after(payload, last_position)
    assert len(finals) == 1, f"expected one final TEXT after last child, got {len(finals)}"
    final_position, final_message = finals[0]
    marker_count = _marker_occurrences(payload, marker, first_position + 1, final_position)
    assert marker_count == 1, (
        f"expected final marker exactly once across fan-in callbacks, got {marker_count}"
    )
    orphaned = _terminal_pre_tool_reasoning(payload, last_position, final_position)
    assert not orphaned, (
        "last child callback contains orphan terminal pre_tool_text; "
        f"possible stream->chat double invocation: {orphaned}"
    )
    _assert_no_duplicate_callback_text(payload, first_position + 1, final_position)
    callback_replies = _assert_one_visible_brain_reply_per_callback(payload, deliverables)

    final_text = _content(final_message).replace("\n", " ")[:180]
    return CaseResult(
        name="non-last child is reasoning and last child is final text",
        session_id=session_id,
        elapsed_ms=(time.time() - started) * 1000,
        detail=(
            f"targets={targets} statuses={statuses} "
            f"deliverables={[position for position, _ in deliverables]} "
            f"intermediateReasoning={[position for position, _ in intermediate_reasoning]} "
            f"prematureFinals={premature_finals} finalIndex={final_position} "
            f"markerCount={marker_count} orphanPreTool={len(orphaned)} "
            f"callbackReplies={callback_replies} final={final_text!r}"
        ),
    )


def _run_case(
    case: Callable[[str, str, float, float], CaseResult],
    base: str,
    token: str,
    poll_interval: float,
    poll_timeout: float,
) -> CaseResult:
    try:
        return case(base, token, poll_interval, poll_timeout)
    except (AssertionError, TimeoutError, RuntimeError, urllib.error.URLError) as error:
        name = case.__name__.removeprefix("_run_").replace("_", " ")
        raise AssertionError(f"{name} failed: {error}") from error


def main() -> int:
    base = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403").rstrip("/")
    host = (urlparse(base).hostname or "").lower()
    if host not in {"127.0.0.1", "localhost", "::1"}:
        raise RuntimeError(f"refusing non-local TEST_BASE_URL={base}")

    username = os.environ.get("TEST_USERNAME", "admin")
    password = os.environ.get("TEST_PASSWORD", "Databuff@123")
    poll_interval = float(os.environ.get("TEST_AI_POLL_INTERVAL", "2"))
    poll_timeout = max(float(os.environ.get("TEST_AI_POLL_TIMEOUT", "900")), 120.0)

    api_key = deepseek_api_key()
    if not api_key:
        raise RuntimeError(
            f"{ENV_API_KEY} is required: this deploy/local integration test must use a real model"
        )

    print(
        f"[local-ai-test] base={base} provider={PROVIDER}/{MODEL} "
        f"key={ENV_API_KEY}:set pollTimeout={poll_timeout:.0f}s",
        flush=True,
    )
    token = login(base, username, password, 60.0)
    ensure_test_llm_provider(base, token, api_key)

    cases = (
        _run_single_child_stream_once,
        _run_non_last_reasoning_last_final,
    )
    results: list[CaseResult] = []
    for index, case in enumerate(cases, start=1):
        print(f"[local-ai-test] running {index}/{len(cases)} {case.__name__} ...", flush=True)
        result = _run_case(case, base, token, poll_interval, poll_timeout)
        results.append(result)
        print(
            f"[PASS] {result.name} ({result.elapsed_ms:.0f}ms) "
            f"session={result.session_id}\n       {result.detail}",
            flush=True,
        )

    print(f"[local-ai-test] passed={len(results)}/{len(cases)} failed=0", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
