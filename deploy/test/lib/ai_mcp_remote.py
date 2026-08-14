"""AI 远程 MCP 集成测试 — 必须走 LLM 对话。

SSE / Streamable HTTP + header 认证。对话结束时同时成立：
  1. 假 MCP 服务端执行了 tools/call（计数增加，last_call 对得上）
  2. 会话消息/toolResult 里出现远端合法原文 echo:{token}:{suffix}
     （后缀只在假服务端生成，模型编不出来）
"""

from __future__ import annotations

import json
import os
import subprocess
import time
import uuid
from dataclasses import dataclass
from typing import Any

from ai_chat_integration import _collect_text, _http_json, _poll_session, _submit_chat
from ai_session_memory import MODEL, PROVIDER
from mcp_fake_server import (
    AUTH,
    SSE_SUFFIX,
    STREAMABLE_SUFFIX,
    TOOL_NAME,
    McpFakeServer,
)
from run_tests import http_json

GROUP_MCP_REMOTE = "远程MCP"

SYSTEM_PROMPT = (
    "你只做一件事：调用工具 echo_ping。"
    "参数 token 必须等于用户给出的 token，一个字都不要改。"
    "拿到工具返回后，把返回原文完整写进最终回答，禁止改写、禁止省略、禁止翻译。"
    "不要调用其它工具。"
)


@dataclass
class McpCaseResult:
    name: str
    ok: bool
    elapsed_ms: float
    session_id: str
    detail: str


def _docker_tcp_ok(host: str, port: int) -> bool:
    try:
        result = subprocess.run(
            [
                "docker",
                "exec",
                "ai-apm-web",
                "bash",
                "-c",
                f"exec 3<>/dev/tcp/{host}/{port}",
            ],
            check=False,
            capture_output=True,
            timeout=4,
        )
        return result.returncode == 0
    except (OSError, subprocess.TimeoutExpired):
        return False


def resolve_advertise_host(port: int) -> str:
    override = (os.environ.get("TEST_MCP_ADVERTISE_HOST") or "").strip()
    if override:
        return override
    for host in ("host.docker.internal", "172.20.80.1"):
        if _docker_tcp_ok(host, port):
            return host
    raise RuntimeError(
        "ai-apm-web cannot reach the fake MCP server on the host. "
        "Set TEST_MCP_ADVERTISE_HOST, or add extra_hosts host.docker.internal:host-gateway "
        "on the web container and recreate it."
    )


def _create_tool(
    base: str,
    token: str,
    tool_id: str,
    endpoint: str,
    transport: str,
    headers: dict[str, str],
    timeout: float,
) -> None:
    _delete_tool(base, token, tool_id, timeout)
    code, _, payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/api/v1/ai/tools",
        body={
            "toolId": tool_id,
            "name": tool_id,
            "category": "集成测试",
            "description": "AI MCP IT fake remote",
            "type": "MCP",
            "implementation": endpoint,
            "schemaJson": "{}",
            "configJson": json.dumps(
                {"endpoint": endpoint, "transport": transport, "headers": headers},
                ensure_ascii=False,
            ),
            "enabled": True,
        },
        token=token,
        timeout=timeout,
    )
    if code != 200:
        raise RuntimeError(f"create tool {tool_id} failed HTTP {code}: {payload}")


def _delete_tool(base: str, token: str, tool_id: str, timeout: float) -> None:
    http_json(
        "DELETE",
        f"{base.rstrip('/')}/webapi/api/v1/ai/tools/{tool_id}",
        token=token,
        timeout=timeout,
    )


def _create_expert(
    base: str,
    token: str,
    expert_id: str,
    tool_id: str,
    timeout: float,
) -> None:
    _delete_expert(base, token, expert_id, timeout)
    _http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/api/v1/ai/experts",
        {
            "expertId": expert_id,
            "name": expert_id,
            "category": "集成测试",
            "description": "MCP LLM conversation IT",
            "type": "CUSTOM",
            "modelProviderCode": PROVIDER,
            "modelName": MODEL,
            "systemPrompt": SYSTEM_PROMPT,
            "toolIds": [tool_id],
            "skillIds": [],
            "enabled": True,
        },
        token=token,
        timeout=timeout,
    )


def _delete_expert(base: str, token: str, expert_id: str, timeout: float) -> None:
    http_json(
        "DELETE",
        f"{base.rstrip('/')}/webapi/api/v1/ai/experts/{expert_id}",
        token=token,
        timeout=timeout,
    )


def _legal_text(call_token: str, suffix: str) -> str:
    return f"echo:{call_token}:{suffix}"


def _assert_chat_got_remote_result(
    *,
    payload: dict[str, Any],
    call_token: str,
    suffix: str,
    stats_before: dict[str, Any],
    stats_after: dict[str, Any],
    transport: str,
) -> str:
    expected = _legal_text(call_token, suffix)
    blob = _collect_text(payload)
    if expected not in blob:
        raise AssertionError(
            f"conversation missing remote MCP result {expected!r}; "
            f"got {blob[:800]!r}"
        )
    if int(stats_after.get("tools_call") or 0) <= int(stats_before.get("tools_call") or 0):
        raise AssertionError(
            "fake MCP tools_call did not increase — LLM 对话没有打到远端: "
            f"before={stats_before} after={stats_after} conversation={blob[:400]!r}"
        )
    last = stats_after.get("last_call") or {}
    if last.get("transport") != transport:
        raise AssertionError(f"last_call transport want {transport}, got {last}")
    if last.get("token") != call_token:
        raise AssertionError(
            f"远端 echo_ping token 不是用户给的 {call_token!r}: {last}"
        )
    if last.get("text") != expected:
        raise AssertionError(f"fake server last_call text want {expected!r}, got {last}")
    return expected


def _run_chat_case(
    *,
    base: str,
    token: str,
    name: str,
    expert_id: str,
    call_token: str,
    suffix: str,
    stats_transport: str,
    server: McpFakeServer,
    poll_timeout_sec: float,
    require_remote: bool,
) -> McpCaseResult:
    started = time.time()
    session_id = ""
    try:
        before = server.state.snapshot()
        session_id = _submit_chat(
            base,
            token,
            f"token={call_token}\n请立即调用 {TOOL_NAME}。",
            expert_id=expert_id,
            model_provider_code=PROVIDER,
            model_name=MODEL,
        )
        payload = _poll_session(base, token, session_id, 2.0, poll_timeout_sec)
        after = server.state.snapshot()
        blob = _collect_text(payload)
        expected = _legal_text(call_token, suffix)
        if require_remote:
            text = _assert_chat_got_remote_result(
                payload=payload,
                call_token=call_token,
                suffix=suffix,
                stats_before=before,
                stats_after=after,
                transport=stats_transport,
            )
            detail = f"chat passed remote tools/call text={text}"
        else:
            if int(after.get("tools_call") or 0) != int(before.get("tools_call") or 0):
                raise AssertionError(
                    f"missing Authorization 时远端不该执行 tools/call: {after}"
                )
            if expected in blob:
                raise AssertionError(
                    f"no-auth 对话不应出现远端原文 {expected!r}: {blob[:500]!r}"
                )
            if int(after.get("unauthorized") or 0) <= int(before.get("unauthorized") or 0):
                raise AssertionError(
                    "missing Authorization 时应打到远端并被 401；"
                    "unauthorized 未增加，说明客户端可能根本没连上"
                    "（例如 fat-jar SPI / McpJsonMapper 失败）: "
                    f"before={before} after={after} conversation={blob[:400]!r}"
                )
            detail = "chat finished without remote MCP execution (auth missing)"
        return McpCaseResult(
            name=name,
            ok=True,
            elapsed_ms=(time.time() - started) * 1000,
            session_id=session_id,
            detail=detail,
        )
    except Exception as ex:  # noqa: BLE001
        return McpCaseResult(
            name=name,
            ok=False,
            elapsed_ms=(time.time() - started) * 1000,
            session_id=session_id,
            detail=str(ex),
        )


def run_ai_mcp_remote_cases(
    base: str,
    token: str,
    timeout: float = 60.0,
    poll_timeout_sec: float = 180.0,
) -> list[McpCaseResult]:
    server = McpFakeServer().start()
    tool_ids: list[str] = []
    expert_ids: list[str] = []
    results: list[McpCaseResult] = []
    try:
        host = resolve_advertise_host(server.port)
        sse_url = f"http://{host}:{server.port}/sse"
        stream_url = f"http://{host}:{server.port}/mcp"
        stamp = uuid.uuid4().hex[:8]
        cases = [
            ("sse", f"it.mcp.sse.{stamp}", f"it-mcp-sse-{stamp}", sse_url, "SSE", SSE_SUFFIX, "sse"),
            (
                "streamable",
                f"it.mcp.streamable.{stamp}",
                f"it-mcp-http-{stamp}",
                stream_url,
                "STREAMABLE_HTTP",
                STREAMABLE_SUFFIX,
                "streamable",
            ),
        ]
        for name, tool_id, expert_id, endpoint, transport, suffix, stats_transport in cases:
            tool_ids.append(tool_id)
            expert_ids.append(expert_id)
            try:
                _create_tool(
                    base, token, tool_id, endpoint, transport, {"Authorization": AUTH}, timeout
                )
                _create_expert(base, token, expert_id, tool_id, timeout)
            except Exception as ex:  # noqa: BLE001
                results.append(
                    McpCaseResult(
                        name=f"{name}+auth",
                        ok=False,
                        elapsed_ms=0,
                        session_id="",
                        detail=f"setup failed: {ex}",
                    )
                )
                continue
            results.append(
                _run_chat_case(
                    base=base,
                    token=token,
                    name=f"{name}+auth",
                    expert_id=expert_id,
                    call_token=f"{name}-{stamp}",
                    suffix=suffix,
                    stats_transport=stats_transport,
                    server=server,
                    poll_timeout_sec=poll_timeout_sec,
                    require_remote=True,
                )
            )

        noauth_tool = f"it.mcp.sse.noauth.{stamp}"
        noauth_expert = f"it-mcp-noauth-{stamp}"
        tool_ids.append(noauth_tool)
        expert_ids.append(noauth_expert)
        try:
            _create_tool(base, token, noauth_tool, sse_url, "SSE", {}, timeout)
            _create_expert(base, token, noauth_expert, noauth_tool, timeout)
            results.append(
                _run_chat_case(
                    base=base,
                    token=token,
                    name="sse-missing-auth",
                    expert_id=noauth_expert,
                    call_token=f"noauth-{stamp}",
                    suffix=SSE_SUFFIX,
                    stats_transport="sse",
                    server=server,
                    poll_timeout_sec=poll_timeout_sec,
                    require_remote=False,
                )
            )
        except Exception as ex:  # noqa: BLE001
            results.append(
                McpCaseResult(
                    name="sse-missing-auth",
                    ok=False,
                    elapsed_ms=0,
                    session_id="",
                    detail=f"setup failed: {ex}",
                )
            )
        return results
    finally:
        for expert_id in expert_ids:
            try:
                _delete_expert(base, token, expert_id, timeout)
            except Exception:  # noqa: BLE001
                pass
        for tool_id in tool_ids:
            try:
                _delete_tool(base, token, tool_id, timeout)
            except Exception:  # noqa: BLE001
                pass
        server.stop()
