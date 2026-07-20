"""AI 会话记忆集成测试 — (sessionId, expertId) 多轮上下文是否保留.

由环境变量 ``DEEPSEEK_API_KEY`` 门控：
  - 未设置 → 跳过（CI 安全）
  - 已设置 → 写入 deepseek provider，再跑多轮追问 / 专家隔离 / 「生成」续写
"""

from __future__ import annotations

import os
import time
import urllib.error
from dataclasses import dataclass
from typing import Any

from ai_chat_integration import _http_json

MODULE_AI_PLATFORM = "AI平台"
GROUP_SESSION_MEMORY = "会话记忆"

PROVIDER = "deepseek"
MODEL = "deepseek-v4-flash"
BASE_URL = "https://api.deepseek.com"

ENV_API_KEY = "DEEPSEEK_API_KEY"


@dataclass
class MemoryCaseResult:
    name: str
    session_id: str
    ok: bool
    elapsed_ms: float
    detail: str


def deepseek_api_key() -> str | None:
    value = os.environ.get(ENV_API_KEY, "").strip()
    return value or None


def ensure_deepseek_provider(base: str, token: str, api_key: str, timeout: float = 30.0) -> None:
    """Configure / enable DeepSeek from env key so local/demo stacks can run memory cases."""
    _http_json(
        "PUT",
        f"{base.rstrip('/')}/webapi/api/v1/config/ai/providers/{PROVIDER}",
        {
            "baseUrl": BASE_URL,
            "apiKey": api_key,
            "defaultModel": MODEL,
            "enabled": True,
        },
        token=token,
        timeout=timeout,
    )
    _http_json(
        "PUT",
        f"{base.rstrip('/')}/webapi/api/v1/config/ai/providers/{PROVIDER}/default",
        {},
        token=token,
        timeout=timeout,
    )


def _submit_chat(
    base: str,
    token: str,
    message: str,
    *,
    expert_id: str,
    session_id: str | None = None,
    timeout: float = 60.0,
) -> str:
    body: dict[str, Any] = {
        "expertId": expert_id,
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


def _latest_assistant_text(payload: dict[str, Any]) -> str:
    text = ""
    for message in payload.get("messages") or []:
        if message.get("role") != "assistant":
            continue
        message_type = str(message.get("messageType") or "TEXT").upper()
        if message_type not in ("TEXT",):
            continue
        content = message.get("content")
        if not isinstance(content, str) or not content.strip():
            continue
        metadata = message.get("metadata") or {}
        if metadata.get("isExpertDeliverable"):
            continue
        text = content
    return text


def _message_count(payload: dict[str, Any]) -> int:
    return len(payload.get("messages") or [])


def _poll_new_assistant(
    base: str,
    token: str,
    session_id: str,
    prev_count: int,
    *,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> str:
    deadline = time.time() + poll_timeout_sec
    saw_running = False
    last_payload: dict[str, Any] = {}
    while time.time() < deadline:
        last_payload = _http_json(
            "GET",
            f"{base.rstrip('/')}/webapi/api/v1/ai/sessions/{session_id}/messages",
            token=token,
        )
        running = bool(last_payload.get("running"))
        if running:
            saw_running = True
        count = _message_count(last_payload)
        reply = _latest_assistant_text(last_payload)
        if saw_running and (not running) and count > prev_count and reply:
            return reply
        if (not running) and (not saw_running) and count > prev_count and reply:
            return reply
        time.sleep(poll_interval_sec)
    raise TimeoutError(
        f"session {session_id} no new assistant after {poll_timeout_sec}s; "
        f"last={_latest_assistant_text(last_payload)[:200]!r}"
    )


def _turn(
    base: str,
    token: str,
    message: str,
    *,
    expert_id: str,
    session_id: str | None = None,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> tuple[str, str]:
    prev_count = 0
    if session_id:
        snap = _http_json(
            "GET",
            f"{base.rstrip('/')}/webapi/api/v1/ai/sessions/{session_id}/messages",
            token=token,
        )
        prev_count = _message_count(snap)
    sid = _submit_chat(base, token, message, expert_id=expert_id, session_id=session_id)
    if prev_count == 0:
        time.sleep(0.4)
        snap = _http_json(
            "GET",
            f"{base.rstrip('/')}/webapi/api/v1/ai/sessions/{sid}/messages",
            token=token,
        )
        prev_count = max(0, _message_count(snap) - 1)
    reply = _poll_new_assistant(
        base,
        token,
        sid,
        prev_count,
        poll_interval_sec=poll_interval_sec,
        poll_timeout_sec=poll_timeout_sec,
    )
    return sid, reply


def _case(
    name: str,
    ok: bool,
    session_id: str,
    started: float,
    detail: str,
) -> MemoryCaseResult:
    return MemoryCaseResult(
        name=name,
        session_id=session_id,
        ok=ok,
        elapsed_ms=(time.time() - started) * 1000,
        detail=detail,
    )


def run_ai_session_memory_cases(
    base: str,
    token: str,
    *,
    poll_interval_sec: float = 1.2,
    poll_timeout_sec: float = 240.0,
) -> list[MemoryCaseResult]:
    """Run multi-turn memory scenarios. Caller must ensure DEEPSEEK_API_KEY is set."""
    api_key = deepseek_api_key()
    if not api_key:
        return []

    ensure_deepseek_provider(base, token, api_key)
    results: list[MemoryCaseResult] = []
    turn_kw = {
        "poll_interval_sec": poll_interval_sec,
        "poll_timeout_sec": poll_timeout_sec,
    }

    # 1) inspection multi-turn
    started = time.time()
    sid = ""
    try:
        sid, r1 = _turn(
            base,
            token,
            "不要调用任何工具。请记住暗号「蓝莓派」，只用一句话确认你已记住。",
            expert_id="inspection",
            **turn_kw,
        )
        sid, r2 = _turn(
            base,
            token,
            "不要调用任何工具。刚才的暗号是什么？只回答暗号本身。",
            expert_id="inspection",
            session_id=sid,
            **turn_kw,
        )
        ok = "蓝莓派" in r2
        results.append(_case("巡检多轮记忆", ok, sid, started, r2[:240]))
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        results.append(_case("巡检多轮记忆", False, sid, started, str(error)))

    # 2) cross-expert isolation (reuse inspection session if available)
    started = time.time()
    try:
        if not sid:
            sid, _ = _turn(
                base,
                token,
                "不要调用任何工具。请记住暗号「蓝莓派」，只用一句话确认。",
                expert_id="inspection",
                **turn_kw,
            )
        _, r3 = _turn(
            base,
            token,
            "不要调用任何工具。刚才巡检专家的暗号是什么？如果不知道就回答「不知道」。",
            expert_id="data",
            session_id=sid,
            **turn_kw,
        )
        ok = ("不知道" in r3) or ("蓝莓派" not in r3)
        results.append(_case("同会话跨专家隔离", ok, sid, started, r3[:240]))
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        results.append(_case("同会话跨专家隔离", False, sid, started, str(error)))

    # 3) data multi-turn
    started = time.time()
    sid_d = ""
    try:
        sid_d, _ = _turn(
            base,
            token,
            "不要调用任何工具。请记住暗号「芒果冰」，只用一句话确认。",
            expert_id="data",
            **turn_kw,
        )
        sid_d, r = _turn(
            base,
            token,
            "不要调用任何工具。暗号是什么？只回答暗号。",
            expert_id="data",
            session_id=sid_d,
            **turn_kw,
        )
        results.append(_case("问数多轮记忆", "芒果冰" in r, sid_d, started, r[:240]))
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        results.append(_case("问数多轮记忆", False, sid_d, started, str(error)))

    # 4) brain multi-turn
    started = time.time()
    sid_b = ""
    try:
        sid_b, _ = _turn(
            base,
            token,
            "不要派发子专家、不要调用工具。请记住暗号「桂花糕」，一句话确认。",
            expert_id="brain",
            **turn_kw,
        )
        sid_b, r = _turn(
            base,
            token,
            "不要派发子专家、不要调用工具。暗号是什么？只回答暗号。",
            expert_id="brain",
            session_id=sid_b,
            **turn_kw,
        )
        results.append(_case("大脑多轮记忆", "桂花糕" in r, sid_b, started, r[:240]))
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        results.append(_case("大脑多轮记忆", False, sid_b, started, str(error)))

    # 5) inspection follow-up "生成" (original amnesia bug)
    started = time.time()
    sid_i = ""
    try:
        sid_i, _ = _turn(
            base,
            token,
            "不要调用工具。假设你刚完成 service-b 巡检，结论是库存不足。"
            "请用一句话询问是否需要导出 HTML 报告。",
            expert_id="inspection",
            **turn_kw,
        )
        sid_i, r = _turn(
            base,
            token,
            "生成",
            expert_id="inspection",
            session_id=sid_i,
            **turn_kw,
        )
        amnesia = ("生成什么" in r) or ("想让我生成什么" in r) or ("请问您想让我生成" in r)
        remembers = (
            "service-b" in r.lower()
            or "HTML" in r
            or "报告" in r
            or "库存" in r
        )
        ok = (not amnesia) and remembers
        results.append(_case("巡检追问生成", ok, sid_i, started, r[:300]))
    except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
        results.append(_case("巡检追问生成", False, sid_i, started, str(error)))

    return results
