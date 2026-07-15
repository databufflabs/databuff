#!/usr/bin/env python3
"""Ops-expert (expertId=ops) chat helper for Doris runtime failover E2E (gate B).

Submit a recovery prompt to the ops specialist, poll session messages, and emit a JSON
summary with session evidence (tool calls / replies). Does NOT start Doris containers.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.request
from typing import Any


def http_json(
    method: str,
    url: str,
    body: dict[str, Any] | None = None,
    token: str | None = None,
    timeout: float = 60.0,
) -> Any:
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        raw = resp.read().decode("utf-8")
        if not raw:
            return {}
        return json.loads(raw)


def login(base: str, account: str, password: str, timeout: float) -> str:
    # Prefer portal login used by UI triage; fall back to v1 auth login.
    try:
        payload = http_json(
            "POST",
            f"{base.rstrip('/')}/webapi/user/login",
            {"account": account, "password": password},
            timeout=timeout,
        )
        data = payload.get("data") if isinstance(payload, dict) else None
        token = (data or {}).get("token") if isinstance(data, dict) else None
        if token:
            return str(token)
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError, TypeError):
        pass
    payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/api/v1/auth/login",
        {"username": account, "password": password},
        timeout=timeout,
    )
    token = payload.get("token") if isinstance(payload, dict) else None
    if not token:
        raise RuntimeError(f"login missing token: {payload}")
    return str(token)


def llm_ready(base: str, token: str, timeout: float) -> bool:
    try:
        payload = http_json(
            "GET",
            f"{base.rstrip('/')}/webapi/api/v1/config/ai/status",
            token=token,
            timeout=timeout,
        )
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError, TypeError, ValueError):
        return False
    return bool(isinstance(payload, dict) and payload.get("ready"))


def submit_chat(
    base: str,
    token: str,
    message: str,
    expert_id: str,
    session_id: str | None,
    timeout: float,
) -> str:
    body: dict[str, Any] = {
        "expertId": expert_id,
        "message": message,
        "stream": False,
    }
    if session_id:
        body["sessionId"] = session_id
    payload = http_json(
        "POST",
        f"{base.rstrip('/')}/webapi/api/v1/ai/chat/submit",
        body,
        token=token,
        timeout=timeout,
    )
    sid = payload.get("sessionId") if isinstance(payload, dict) else None
    if not sid:
        raise RuntimeError(f"chat/submit failed: {payload}")
    return str(sid)


def poll_session(
    base: str,
    token: str,
    session_id: str,
    poll_interval_sec: float,
    poll_timeout_sec: float,
    http_timeout: float,
) -> dict[str, Any]:
    deadline = time.time() + poll_timeout_sec
    last: dict[str, Any] = {}
    while time.time() < deadline:
        last = http_json(
            "GET",
            f"{base.rstrip('/')}/webapi/api/v1/ai/sessions/{session_id}/messages",
            token=token,
            timeout=http_timeout,
        )
        if not last.get("running", True):
            return last
        time.sleep(poll_interval_sec)
    raise TimeoutError(f"session {session_id} still running after {poll_timeout_sec}s")


def flatten_evidence(payload: dict[str, Any]) -> dict[str, Any]:
    texts: list[str] = []
    tool_names: list[str] = []
    tool_inputs: list[str] = []
    tool_results: list[str] = []
    for message in payload.get("messages") or []:
        if not isinstance(message, dict):
            continue
        content = message.get("content")
        if isinstance(content, str) and content.strip():
            texts.append(content)
        meta = message.get("metadata") or {}
        if not isinstance(meta, dict):
            continue
        for key in ("toolName", "name", "tool", "tool_id", "toolId"):
            val = meta.get(key)
            if isinstance(val, str) and val:
                tool_names.append(val)
        for key in ("toolInput", "tool_input", "input", "arguments"):
            val = meta.get(key)
            if isinstance(val, str) and val:
                tool_inputs.append(val)
            elif isinstance(val, (dict, list)):
                tool_inputs.append(json.dumps(val, ensure_ascii=False))
        for key in ("toolResult", "tool_result", "result", "output"):
            val = meta.get(key)
            if isinstance(val, str) and val:
                tool_results.append(val)
            elif isinstance(val, (dict, list)):
                tool_results.append(json.dumps(val, ensure_ascii=False))
        reasoning = meta.get("reasoning")
        if isinstance(reasoning, str) and reasoning.strip():
            texts.append(reasoning)

    joined = "\n".join(texts + tool_names + tool_inputs + tool_results)
    bashish = any(
        re.search(r"\bBash\b", n, re.I)
        or n.lower() in {"bash", "bashtools.bash", "bashtools"}
        for n in tool_names
    ) or bool(re.search(r"\b(docker|compose)\b", joined, re.I))
    docker_startish = bool(
        re.search(
            r"docker\s+start|docker\s+compose\s+.*up|compose\s+.*up|systemctl\s+start",
            joined,
            re.I,
        )
    )
    return {
        "messageCount": len(payload.get("messages") or []),
        "toolNames": tool_names,
        "toolInputsSample": tool_inputs[:12],
        "toolResultsSample": [r[:500] for r in tool_results[:8]],
        "assistantTextSample": [t[:800] for t in texts[:8]],
        "hasBashOrDockerEvidence": bashish,
        "hasDockerStartOrComposeEvidence": docker_startish,
        "joinedSnippet": joined[:4000],
    }


def main() -> int:
    parser = argparse.ArgumentParser(description="Ops expert Doris recovery chat")
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--account", default="admin")
    parser.add_argument("--password", default="Databuff@123")
    parser.add_argument("--expert-id", default="ops")
    parser.add_argument("--message", default="")
    parser.add_argument("--follow-up", default="")
    parser.add_argument("--session-id", default="")
    parser.add_argument("--poll-interval", type=float, default=3.0)
    parser.add_argument("--poll-timeout", type=float, default=600.0)
    parser.add_argument("--http-timeout", type=float, default=60.0)
    parser.add_argument("--check-ready-only", action="store_true")
    parser.add_argument("--evidence-out", default="")
    args = parser.parse_args()

    try:
        token = login(args.base_url, args.account, args.password, args.http_timeout)
    except Exception as exc:  # noqa: BLE001 — CLI boundary
        print(json.dumps({"ok": False, "error": f"login failed: {exc}"}, ensure_ascii=False))
        return 2

    ready = llm_ready(args.base_url, token, args.http_timeout)
    if args.check_ready_only:
        print(json.dumps({"ok": ready, "ready": ready}, ensure_ascii=False))
        return 0 if ready else 3

    if not args.message.strip():
        print(json.dumps({"ok": False, "error": "--message is required unless --check-ready-only"}, ensure_ascii=False))
        return 2

    if not ready:
        print(
            json.dumps(
                {
                    "ok": False,
                    "ready": False,
                    "error": (
                        "LLM not ready (GET /webapi/api/v1/config/ai/status ready=false). "
                        "Configure an enabled provider API key in Web → 配置 → 大模型, "
                        "or set SKIP_OPS_EXPERT=1 for health-only smoke (NOT a release gate)."
                    ),
                },
                ensure_ascii=False,
            )
        )
        return 3

    try:
        session_id = submit_chat(
            args.base_url,
            token,
            args.message,
            args.expert_id,
            args.session_id or None,
            args.http_timeout,
        )
        payload = poll_session(
            args.base_url,
            token,
            session_id,
            args.poll_interval,
            args.poll_timeout,
            args.http_timeout,
        )
        if args.follow_up.strip():
            session_id = submit_chat(
                args.base_url,
                token,
                args.follow_up,
                args.expert_id,
                session_id,
                args.http_timeout,
            )
            payload = poll_session(
                args.base_url,
                token,
                session_id,
                args.poll_interval,
                args.poll_timeout,
                args.http_timeout,
            )
    except Exception as exc:  # noqa: BLE001
        print(json.dumps({"ok": False, "ready": True, "error": str(exc)}, ensure_ascii=False))
        return 4

    evidence = flatten_evidence(payload if isinstance(payload, dict) else {})
    result = {
        "ok": True,
        "ready": True,
        "sessionId": session_id,
        "expertId": args.expert_id,
        "running": bool(payload.get("running")) if isinstance(payload, dict) else None,
        "evidence": evidence,
    }
    if args.evidence_out:
        with open(args.evidence_out, "w", encoding="utf-8") as fh:
            json.dump({"sessionId": session_id, "poll": payload, "summary": result}, fh, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
