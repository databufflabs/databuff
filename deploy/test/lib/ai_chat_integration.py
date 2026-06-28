"""AI 对话工具参数校验集成测试 — 验证思考过程中不出现 Parameter validation failed."""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

MODULE_AI_PLATFORM = "AI平台"
GROUP_DATA_EXPERT = "智能问数"

VALIDATION_MARKERS = (
    "Parameter validation failed",
    "required property",
    "Please correct the parameters",
)

AI_CHAT_QUESTIONS: list[tuple[str, str]] = [
    ("queryServicesAll", "列出最近1小时的所有服务列表"),
    ("queryServiceTopology", "查询 [elasticsearch]es:9200 最近1小时的上下游服务拓扑"),
    ("queryTraceListByCondition", "查询 service-a 最近1小时内 HTTP 慢请求的 trace 列表"),
    ("queryServiceAlarms", "查询 service-a 最近1小时的告警列表"),
]


@dataclass
class AiChatCaseResult:
    tool_hint: str
    question: str
    session_id: str
    ok: bool
    elapsed_ms: float
    detail: str


def _http_json(
    method: str,
    url: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
    timeout: float = 30.0,
) -> Any:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _submit_chat(base: str, token: str, message: str, expert_id: str = "data") -> str:
    payload = _http_json(
        "POST",
        f"{base}/webapi/api/v1/ai/chat/submit",
        {"expertId": expert_id, "message": message, "stream": False},
        token=token,
    )
    session_id = payload.get("sessionId")
    if not session_id:
        raise RuntimeError(f"chat submit failed: {payload}")
    return str(session_id)


def _poll_session(
    base: str,
    token: str,
    session_id: str,
    poll_interval_sec: float,
    poll_timeout_sec: float,
) -> dict[str, Any]:
    deadline = time.time() + poll_timeout_sec
    last_payload: dict[str, Any] = {}
    while time.time() < deadline:
        last_payload = _http_json(
            "GET",
            f"{base}/webapi/api/v1/ai/sessions/{session_id}/messages",
            token=token,
        )
        if not last_payload.get("running", True):
            return last_payload
        time.sleep(poll_interval_sec)
    raise TimeoutError(f"session {session_id} still running after {poll_timeout_sec}s")


def _collect_text(payload: dict[str, Any]) -> str:
    chunks: list[str] = []
    for message in payload.get("messages") or []:
        content = message.get("content")
        if isinstance(content, str) and content:
            chunks.append(content)
        metadata = message.get("metadata") or {}
        for key in ("toolInput", "toolResult", "reasoning"):
            value = metadata.get(key)
            if isinstance(value, str) and value:
                chunks.append(value)
    return "\n".join(chunks)


def _find_validation_errors(text: str) -> list[str]:
    hits: list[str] = []
    for line in text.splitlines():
        if any(marker in line for marker in VALIDATION_MARKERS):
            hits.append(line.strip())
    return hits


def is_llm_ready(base: str, token: str, timeout: float = 30.0) -> bool:
    """True when at least one enabled LLM provider has an API key configured."""
    try:
        payload = _http_json(
            "GET",
            f"{base}/webapi/api/v1/config/ai/status",
            token=token,
            timeout=timeout,
        )
    except (urllib.error.URLError, json.JSONDecodeError, KeyError, TypeError, ValueError):
        return False
    return bool(payload.get("ready"))


def run_ai_chat_tool_loop(
    base: str,
    token: str,
    *,
    rounds: int = 2,
    poll_interval_sec: float = 2.0,
    poll_timeout_sec: float = 180.0,
    expert_id: str = "data",
) -> list[AiChatCaseResult]:
    """Run multiple rounds of data-expert chat; fail when tool schema validation errors appear."""
    results: list[AiChatCaseResult] = []
    for round_no in range(1, rounds + 1):
        for tool_hint, question in AI_CHAT_QUESTIONS:
            started = time.time()
            name = f"R{round_no} {tool_hint}"
            try:
                session_id = _submit_chat(base, token, question, expert_id=expert_id)
                payload = _poll_session(
                    base,
                    token,
                    session_id,
                    poll_interval_sec,
                    poll_timeout_sec,
                )
                errors = _find_validation_errors(_collect_text(payload))
                elapsed_ms = (time.time() - started) * 1000
                if errors:
                    detail = "; ".join(errors[:3])
                    results.append(
                        AiChatCaseResult(tool_hint, name, session_id, False, elapsed_ms, detail)
                    )
                else:
                    results.append(
                        AiChatCaseResult(tool_hint, name, session_id, True, elapsed_ms, "no validation errors")
                    )
            except (urllib.error.URLError, TimeoutError, RuntimeError) as error:
                elapsed_ms = (time.time() - started) * 1000
                results.append(
                    AiChatCaseResult(tool_hint, name, "", False, elapsed_ms, str(error))
                )
    return results
