#!/usr/bin/env python3
"""AI 集成测试统一 runner — 依次跑工具参数校验 / 接入格式 / 会话记忆 / 大脑异步路由.

环境变量门控：
  - 工具参数校验：只要存在已启用 LLM provider 就跑（TEST_SKIP_AI_CHAT=1 跳过）
  - 接入格式：DEEPSEEK_API_KEY（OpenAI）/ MINIMAX_API_KEY（Anthropic）（TEST_SKIP_AI_PROVIDER_FORMATS=1 跳过）
  - 会话记忆 / 大脑异步路由：需 DEEPSEEK_API_KEY（TEST_SKIP_AI_MEMORY / TEST_SKIP_AI_BRAIN_ASYNC 跳过）
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

LIB_ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(LIB_ROOT))

from ai_chat_integration import (  # noqa: E402
    AI_CHAT_QUESTIONS,
    is_llm_ready,
    run_ai_chat_tool_loop,
    AiChatCaseResult,
)
from ai_provider_formats import (  # noqa: E402
    FORMAT_QUESTIONS,
    ENV_DEEPSEEK,
    ENV_MINIMAX,
    available_provider_formats,
    provider_api_key,
    run_ai_provider_format_cases,
)
from ai_session_memory import (  # noqa: E402
    ENV_API_KEY,
    deepseek_api_key,
    ensure_deepseek_provider,
    run_ai_session_memory_cases,
    MemoryCaseResult,
)
from ai_brain_async_routing import (  # noqa: E402
    run_ai_brain_async_routing_cases,
    BrainAsyncCaseResult,
)
from run_tests import login  # noqa: E402


def _print_section(title: str) -> None:
    print(f"\n{'=' * 60}")
    print(f"  {title}")
    print(f"{'=' * 60}")


def _print_result_row(name: str, ok: bool, elapsed_ms: float, session_id: str, detail: str) -> None:
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name} ({elapsed_ms:.0f}ms) session={session_id or '-'}")
    print(f"         {detail[:280]}")


def main() -> int:
    base = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403").rstrip("/")
    username = os.environ.get("TEST_USERNAME", "admin")
    password = os.environ.get("TEST_PASSWORD", "Databuff@123")
    timeout = float(os.environ.get("TEST_TIMEOUT", "60"))

    skip_chat = os.environ.get("TEST_SKIP_AI_CHAT", "0") == "1"
    skip_formats = os.environ.get("TEST_SKIP_AI_PROVIDER_FORMATS", "0") == "1"
    skip_memory = os.environ.get("TEST_SKIP_AI_MEMORY", "0") == "1"
    skip_brain_async = os.environ.get("TEST_SKIP_AI_BRAIN_ASYNC", "0") == "1"

    chat_rounds = int(os.environ.get("TEST_AI_CHAT_ROUNDS", "2"))
    chat_poll_timeout = float(os.environ.get("TEST_AI_CHAT_POLL_TIMEOUT", "180"))
    format_rounds = int(os.environ.get("TEST_AI_PROVIDER_FORMAT_ROUNDS", "1"))
    format_poll_timeout = float(os.environ.get("TEST_AI_PROVIDER_FORMAT_POLL_TIMEOUT", "180"))
    memory_poll_timeout = float(os.environ.get("TEST_AI_MEMORY_POLL_TIMEOUT", "240"))
    brain_async_poll_timeout = float(os.environ.get("TEST_AI_BRAIN_ASYNC_POLL_TIMEOUT", "300"))

    total = 0
    failed = 0

    print(f"[ai-tests] login {base} ...")
    token = login(base, username, password, timeout)

    has_deepseek = bool(deepseek_api_key())
    if has_deepseek:
        ensure_deepseek_provider(base, token, deepseek_api_key())

    # --- 1) 工具参数校验 ---
    if not skip_chat:
        _print_section("AI Chat 工具参数校验")
        if is_llm_ready(base, token, timeout):
            print(f"  running {chat_rounds} rounds x {len(AI_CHAT_QUESTIONS)} questions ...")
            results: list[AiChatCaseResult] = run_ai_chat_tool_loop(
                base,
                token,
                rounds=chat_rounds,
                poll_timeout_sec=chat_poll_timeout,
            )
            for item in results:
                _print_result_row(item.question, item.ok, item.elapsed_ms, item.session_id, item.detail)
                total += 1
                if not item.ok:
                    failed += 1
        else:
            print("  skip: no enabled LLM provider with API key configured")
    else:
        print("[ai-tests] skip AI chat (TEST_SKIP_AI_CHAT=1)")

    # --- 2) 两种接入格式（OpenAI / Anthropic）---
    if not skip_formats:
        _print_section("AI 接入格式（OpenAI Completions / Anthropic Messages）")
        formats = available_provider_formats(base, token)
        if formats:
            labels = ", ".join(
                f"{p.label}({p.env_key if provider_api_key(p.env_key) else 'server'})"
                for p in formats
            )
            print(
                f"  running {len(formats)} format(s) x {format_rounds} rounds x "
                f"{len(FORMAT_QUESTIONS)} questions: {labels}"
            )
            format_results = run_ai_provider_format_cases(
                base,
                token,
                rounds=format_rounds,
                poll_timeout_sec=format_poll_timeout,
            )
            for item in format_results:
                _print_result_row(item.question, item.ok, item.elapsed_ms, item.session_id, item.detail)
                total += 1
                if not item.ok:
                    failed += 1
        else:
            print(
                f"  skip: set {ENV_DEEPSEEK}/{ENV_MINIMAX}, or enable matching providers in UI"
            )
    else:
        print("[ai-tests] skip AI provider formats (TEST_SKIP_AI_PROVIDER_FORMATS=1)")

    # --- 3) 会话记忆 ---
    if not skip_memory:
        _print_section("AI 会话记忆")
        if has_deepseek:
            print(f"  running cases ({ENV_API_KEY} set) ...")
            memory_results: list[MemoryCaseResult] = run_ai_session_memory_cases(
                base,
                token,
                poll_timeout_sec=memory_poll_timeout,
            )
            for item in memory_results:
                _print_result_row(item.name, item.ok, item.elapsed_ms, item.session_id, item.detail)
                total += 1
                if not item.ok:
                    failed += 1
        else:
            print(f"  skip: set {ENV_API_KEY} to enable")
    else:
        print("[ai-tests] skip AI session memory (TEST_SKIP_AI_MEMORY=1)")

    # --- 4) 大脑异步路由 ---
    if not skip_brain_async:
        _print_section("AI 大脑异步路由（同 session 串行 fan-in / 跨 session 隔离）")
        if has_deepseek:
            print(f"  running cases ({ENV_API_KEY} set) ...")
            brain_results: list[BrainAsyncCaseResult] = run_ai_brain_async_routing_cases(
                base,
                token,
                poll_timeout_sec=brain_async_poll_timeout,
            )
            for item in brain_results:
                _print_result_row(item.name, item.ok, item.elapsed_ms, item.session_id, item.detail)
                total += 1
                if not item.ok:
                    failed += 1
        else:
            print(f"  skip: set {ENV_API_KEY} to enable")
    else:
        print("[ai-tests] skip AI brain async routing (TEST_SKIP_AI_BRAIN_ASYNC=1)")

    # --- summary ---
    _print_section("AI Tests Summary")
    print(f"  total={total} passed={total - failed} failed={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
