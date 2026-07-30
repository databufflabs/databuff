"""AI 模型失败可见性集成测试 — 单专家 / 多专家协同。

覆盖「工具中途或模型调用失败后，终态必须对用户暴露 error」回归
（对应 503 busy / invalid model 等场景，禁止把中间过程文案当成成功终答）。

门控：
  - ``AI_TEST_PROVIDER=minimax``（推荐；也可用 deepseek）
  - 对应 ``MINIMAX_API_KEY`` / ``DEEPSEEK_API_KEY``
  - ``TEST_SKIP_AI_MODEL_FAILURE=1`` 跳过

策略：
  - 先配置真实 MiniMax（或选定 provider）证明链路可用
  - 单专家：请求覆盖一个**必然失败**的 modelName，断言终态含 error
  - 多专家：给 ops/data 临时绑定坏 provider，brain 仍走好 provider 派发，
    断言子专家 ERROR / task.error / 终稿侧能看到失败信息，最后还原专家配置
"""

from __future__ import annotations

import os
import time
import urllib.error
from dataclasses import dataclass
from typing import Any

from ai_chat_integration import _http_json, _poll_session
from ai_session_memory import (
    API_TYPE,
    BASE_URL,
    ENV_API_KEY,
    MODEL,
    PROVIDER,
    deepseek_api_key,
    ensure_test_llm_provider,
)

MODULE_AI_PLATFORM = "AI平台"
GROUP_MODEL_FAILURE = "模型失败可见性"

# 故意不存在的模型，逼真实 MiniMax/DeepSeek API 返回错误
FORCE_FAIL_MODEL = os.environ.get(
    "TEST_AI_FORCE_FAIL_MODEL",
    "DataBuff-IT-ForceFail-Model-DoesNotExist",
)
BROKEN_PROVIDER_CODE = "databuff-it-fail"

ERROR_MARKERS = (
    "对话失败",
    "专家运行时调用失败",
    "LLM 调用失败",
    "执行失败",
    "status 503",
    "503",
    "server_error",
    "system is busy",
    "invalid",
    "not found",
    "does not exist",
    "unauthorized",
    "authentication",
    "api key",
    "model",
    "error",
    "失败",
    "Error",
    "ERROR",
)


@dataclass
class ModelFailureCaseResult:
    name: str
    session_id: str
    ok: bool
    elapsed_ms: float
    detail: str


def _submit(
    base: str,
    token: str,
    message: str,
    *,
    expert_id: str,
    model_provider_code: str | None = None,
    model_name: str | None = None,
    session_id: str | None = None,
    timeout: float = 60.0,
) -> str:
    body: dict[str, Any] = {
        "expertId": expert_id,
        "message": message,
        "stream": False,
        "userName": "admin",
        "context": {},
        "modelProviderCode": model_provider_code or PROVIDER,
        "modelName": model_name or MODEL,
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


def _messages(payload: dict[str, Any]) -> list[dict[str, Any]]:
    raw = payload.get("messages") or []
    return raw if isinstance(raw, list) else []


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


def _get_expert(base: str, token: str, expert_id: str) -> dict[str, Any]:
    payload = _http_json(
        "GET",
        f"{base.rstrip('/')}/webapi/api/v1/ai/experts/{expert_id}",
        token=token,
        timeout=30.0,
    )
    if not isinstance(payload, dict):
        raise RuntimeError(f"expert {expert_id} not found: {payload}")
    return payload


def _put_expert(base: str, token: str, expert: dict[str, Any]) -> None:
    expert_id = str(expert.get("expertId") or "")
    body = {
        "expertId": expert_id,
        "name": expert.get("name"),
        "category": expert.get("category"),
        "description": expert.get("description"),
        "type": expert.get("type"),
        "modelProviderCode": expert.get("modelProviderCode"),
        "modelName": expert.get("modelName"),
        "systemPrompt": expert.get("systemPrompt"),
        "toolIds": expert.get("toolIds") or [],
        "skillIds": expert.get("skillIds") or [],
        "options": expert.get("options"),
        "enabled": expert.get("enabled", True),
    }
    _http_json(
        "PUT",
        f"{base.rstrip('/')}/webapi/api/v1/ai/experts/{expert_id}",
        body,
        token=token,
        timeout=30.0,
    )


def _ensure_broken_provider(base: str, token: str) -> None:
    """坏 provider：同协议端点 + 无效 key，不污染真实 MiniMax。"""
    root = base.rstrip("/")
    providers = _http_json(
        "GET",
        f"{root}/webapi/api/v1/config/ai/providers",
        token=token,
        timeout=30.0,
    )
    codes = {
        str(item.get("providerCode") or "")
        for item in (providers if isinstance(providers, list) else [])
    }
    if BROKEN_PROVIDER_CODE not in codes:
        _http_json(
            "POST",
            f"{root}/webapi/api/v1/config/ai/providers",
            {
                "providerCode": BROKEN_PROVIDER_CODE,
                "displayName": "DataBuff IT Fail",
                "baseUrl": BASE_URL,
                "defaultModel": FORCE_FAIL_MODEL,
                "apiKey": "sk-databuff-it-intentionally-invalid",
                "enabled": True,
            },
            token=token,
            timeout=30.0,
        )
    _http_json(
        "PUT",
        f"{root}/webapi/api/v1/config/ai/providers/{BROKEN_PROVIDER_CODE}/detail",
        {
            "providerCode": BROKEN_PROVIDER_CODE,
            "apiType": API_TYPE,
            "baseUrl": BASE_URL,
            "apiKey": "sk-databuff-it-intentionally-invalid",
            "enabled": True,
            "defaultProvider": False,
            "defaultModelId": FORCE_FAIL_MODEL,
            "models": [
                {
                    "modelId": FORCE_FAIL_MODEL,
                    "displayName": FORCE_FAIL_MODEL,
                    "defaultModel": True,
                }
            ],
        },
        token=token,
        timeout=30.0,
    )


def _text_blob(messages: list[dict[str, Any]]) -> str:
    chunks: list[str] = []
    for message in messages:
        content = message.get("content")
        if isinstance(content, str) and content.strip():
            chunks.append(content)
        err = message.get("error")
        if isinstance(err, str) and err.strip():
            chunks.append(err)
        meta = message.get("metadata") or {}
        for key in ("toolResult", "error", "failure"):
            value = meta.get(key)
            if isinstance(value, str) and value.strip():
                chunks.append(value)
    return "\n".join(chunks)


def _has_error_marker(text: str) -> bool:
    lower = text.lower()
    for marker in ERROR_MARKERS:
        if marker.lower() in lower:
            return True
    return False


def _assert_single_expert_failure(payload: dict[str, Any], *, expert_id: str) -> str:
    """严苛：终态必须出现 FAILED/ERROR，且正文含 error 信息；禁止静默成功终答。"""
    messages = _messages(payload)
    if payload.get("running"):
        raise AssertionError("session still running")

    failed_finals: list[dict[str, Any]] = []
    error_rows: list[dict[str, Any]] = []
    silent_success_finals: list[dict[str, Any]] = []

    for message in messages:
        role = str(message.get("role") or "")
        mtype = str(message.get("messageType") or "TEXT").upper()
        status = str(message.get("messageStatus") or "").upper()
        expert = str(message.get("agent") or message.get("expertId") or "")
        content = str(message.get("content") or "")
        meta = message.get("metadata") or {}
        is_final = meta.get("isRoundFinal") is True or (
            meta.get("isRoundFinal") is not False
            and mtype == "TEXT"
            and role == "assistant"
            and not meta.get("isExpertDeliverable")
        )

        if mtype == "ERROR" and (not expert or expert == expert_id):
            error_rows.append(message)
        if (
            role == "assistant"
            and mtype == "TEXT"
            and status == "FAILED"
            and (not expert or expert == expert_id)
        ):
            failed_finals.append(message)
        if (
            role == "assistant"
            and mtype == "TEXT"
            and status == "COMPLETED"
            and is_final
            and (not expert or expert == expert_id)
            and content.strip()
            and not _has_error_marker(content)
            and not meta.get("isExpertDeliverable")
        ):
            silent_success_finals.append(message)

    blob = _text_blob(messages)
    if not _has_error_marker(blob):
        raise AssertionError(
            f"final messages missing error markers; sample={blob[:400]!r}"
        )
    if not failed_finals and not error_rows:
        raise AssertionError(
            "expected FAILED TEXT or ERROR row in final messages; "
            f"types={[ (m.get('messageType'), m.get('messageStatus')) for m in messages[-8:] ]}"
        )
    # 至少一条失败终态正文本身就要带 error 信息
    visible = ""
    for row in failed_finals + error_rows:
        visible = str(row.get("content") or row.get("error") or "")
        if _has_error_marker(visible):
            break
    else:
        raise AssertionError(
            f"FAILED/ERROR rows present but content has no error text; "
            f"failed={[r.get('content') for r in failed_finals]!r} "
            f"errors={[r.get('content') for r in error_rows]!r}"
        )
    if silent_success_finals:
        raise AssertionError(
            "found COMPLETED round-final TEXT without error markers "
            f"(silent success): {[r.get('content')[:120] for r in silent_success_finals]!r}"
        )
    return visible[:240]


def _assert_multi_expert_failure(
    payload: dict[str, Any],
    tasks: list[dict[str, Any]],
    *,
    expect_failed_experts: set[str],
) -> str:
    messages = _messages(payload)
    if payload.get("running"):
        raise AssertionError("session still running")

    error_by_expert: dict[str, str] = {}
    for message in messages:
        mtype = str(message.get("messageType") or "").upper()
        expert = str(message.get("agent") or message.get("expertId") or "")
        content = str(message.get("content") or "")
        if mtype == "ERROR" and expert in expect_failed_experts and _has_error_marker(content):
            error_by_expert[expert] = content

    failed_tasks: dict[str, str] = {}
    for task in tasks:
        target = str(task.get("targetExpertId") or "")
        status = str(task.get("status") or "").upper()
        err = str(task.get("error") or "")
        if target in expect_failed_experts and status in {"FAILED", "TIMEOUT", "ERROR"}:
            if not _has_error_marker(err) and not _has_error_marker(
                str(task.get("output") or "")
            ):
                # task 行本身没 error 字段时，允许仅靠消息 ERROR
                failed_tasks[target] = err or status
            else:
                failed_tasks[target] = err or str(task.get("output") or status)

    missing = expect_failed_experts - set(error_by_expert) - set(failed_tasks)
    if not expect_failed_experts:
        raise AssertionError("expect_failed_experts is empty")
    if missing:
        raise AssertionError(
            f"missing visible failure for experts {sorted(missing)}; "
            f"error_msgs={list(error_by_expert)} failed_tasks={list(failed_tasks)} "
            f"task_dump={tasks!r}"
        )

    blob = _text_blob(messages) + "\n" + "\n".join(
        str(t.get("error") or "") for t in tasks
    )
    if not _has_error_marker(blob):
        raise AssertionError(f"multi-expert final blob missing error markers: {blob[:400]!r}")

    # 若有 brain 终稿，也不得把失败伪装成纯成功（允许终稿，但全会话必须已有 error）
    return (
        next(iter(error_by_expert.values()), None)
        or next(iter(failed_tasks.values()), "")
    )[:240]


def _run_case(name: str, fn) -> ModelFailureCaseResult:
    started = time.time()
    session_id = ""
    try:
        session_id, detail = fn()
        return ModelFailureCaseResult(
            name=name,
            session_id=session_id,
            ok=True,
            elapsed_ms=(time.time() - started) * 1000.0,
            detail=detail,
        )
    except Exception as exc:  # noqa: BLE001 - surface to suite runner
        return ModelFailureCaseResult(
            name=name,
            session_id=session_id,
            ok=False,
            elapsed_ms=(time.time() - started) * 1000.0,
            detail=f"{type(exc).__name__}: {exc}",
        )


def run_ai_model_failure_cases(
    base: str,
    token: str,
    *,
    poll_interval_sec: float = 2.0,
    poll_timeout_sec: float = 240.0,
) -> list[ModelFailureCaseResult]:
    api_key = deepseek_api_key()
    if not api_key:
        raise RuntimeError(f"{ENV_API_KEY} unset")

    ensure_test_llm_provider(base, token, api_key)
    _ensure_broken_provider(base, token)

    results: list[ModelFailureCaseResult] = []

    def case_single_ops() -> tuple[str, str]:
        # 用坏 provider（无效 key）强制失败；比“假模型名”更稳（MiniMax 可能忽略未知 model）
        sid = _submit(
            base,
            token,
            "不要调用工具。只回复一个字：好",
            expert_id="ops",
            model_provider_code=BROKEN_PROVIDER_CODE,
            model_name=FORCE_FAIL_MODEL,
        )
        payload = _poll_session(base, token, sid, poll_interval_sec, poll_timeout_sec)
        visible = _assert_single_expert_failure(payload, expert_id="ops")
        return sid, f"ops visible error: {visible}"

    def case_single_data() -> tuple[str, str]:
        sid = _submit(
            base,
            token,
            "不要调用工具。只回复一个字：好",
            expert_id="data",
            model_provider_code=BROKEN_PROVIDER_CODE,
            model_name=FORCE_FAIL_MODEL,
        )
        payload = _poll_session(base, token, sid, poll_interval_sec, poll_timeout_sec)
        visible = _assert_single_expert_failure(payload, expert_id="data")
        return sid, f"data visible error: {visible}"

    def case_multi_brain_dispatch() -> tuple[str, str]:
        originals = {
            "ops": _get_expert(base, token, "ops"),
            "data": _get_expert(base, token, "data"),
        }
        try:
            for expert_id, original in originals.items():
                patched = dict(original)
                patched["modelProviderCode"] = BROKEN_PROVIDER_CODE
                patched["modelName"] = FORCE_FAIL_MODEL
                _put_expert(base, token, patched)

            # brain 用好的 MiniMax 显式派发；子专家绑定坏 provider → 必须出现 ERROR
            sid = _submit(
                base,
                token,
                (
                    "请并行派发智能问数专家(data)和运维专家(ops)，不要自己编造结果："
                    "data 查询最近1小时服务列表；ops 执行 bash 命令 echo databuff-it-ok。"
                    "两者都返回后再汇总。"
                ),
                expert_id="brain",
                model_provider_code=PROVIDER,
                model_name=MODEL,
            )
            timeout = max(poll_timeout_sec, 420.0)
            payload = _poll_session(base, token, sid, poll_interval_sec, timeout)
            tasks = _session_tasks(base, token, sid)
            deadline = time.time() + 90.0
            while time.time() < deadline:
                if tasks and all(
                    str(t.get("status") or "").upper()
                    in {"SUCCEEDED", "FAILED", "TIMEOUT", "CANCELLED", "ERROR"}
                    for t in tasks
                ):
                    break
                time.sleep(poll_interval_sec)
                tasks = _session_tasks(base, token, sid)
                payload = _http_json(
                    "GET",
                    f"{base.rstrip('/')}/webapi/api/v1/ai/sessions/{sid}/messages",
                    token=token,
                    timeout=30.0,
                )

            if not tasks:
                # 未派发时：brain 自身若因链路失败，也必须在终态露出 error；否则判失败
                try:
                    visible = _assert_single_expert_failure(payload, expert_id="brain")
                    return sid, f"brain failed before dispatch (acceptable): {visible}"
                except AssertionError as exc:
                    raise AssertionError(
                        f"brain did not dispatch ops/data and final has no error: {exc}"
                    ) from exc

            visible = _assert_multi_expert_failure(
                payload,
                tasks,
                expect_failed_experts={"ops", "data"} & {
                    str(t.get("targetExpertId") or "") for t in tasks
                },
            )
            # 至少派发到一个预期专家且失败可见；若只派了一个，也要求其 error 可见
            failed_targets = {
                str(t.get("targetExpertId") or "")
                for t in tasks
                if str(t.get("status") or "").upper() in {"FAILED", "TIMEOUT", "ERROR"}
            }
            if not (failed_targets & {"ops", "data"}):
                raise AssertionError(
                    f"dispatched tasks but none of ops/data FAILED with error; tasks={tasks!r}"
                )
            return sid, f"multi-expert visible error: {visible}; tasks={len(tasks)}"
        finally:
            for expert_id, original in originals.items():
                try:
                    _put_expert(base, token, original)
                except (urllib.error.URLError, RuntimeError, TypeError, ValueError):
                    pass

    results.append(_run_case("single_ops_force_model_fail", case_single_ops))
    results.append(_run_case("single_data_force_model_fail", case_single_data))
    results.append(_run_case("multi_brain_ops_data_force_fail", case_multi_brain_dispatch))
    return results


__all__ = [
    "ModelFailureCaseResult",
    "GROUP_MODEL_FAILURE",
    "MODULE_AI_PLATFORM",
    "run_ai_model_failure_cases",
]
