#!/usr/bin/env python3
"""AI 集成测试 runner — 四套件可分别跑，默认并行（禁止串行叠跑）。

套件：
  - chat      工具参数校验
  - formats   OpenAI / Anthropic 接入格式
  - memory    会话记忆
  - brain     大脑异步路由（套件内用例亦并行）

环境变量门控：
  - chat：已启用 LLM provider（TEST_SKIP_AI_CHAT=1 跳过）
  - formats：DEEPSEEK_API_KEY / MINIMAX_API_KEY（TEST_SKIP_AI_PROVIDER_FORMATS=1 跳过）
  - memory / brain：DEEPSEEK_API_KEY（TEST_SKIP_AI_MEMORY / TEST_SKIP_AI_BRAIN_ASYNC 跳过）

并行：
  - ``--suite all``（默认）且 ``AI_TESTS_PARALLEL=1``（默认）：四套件 ThreadPool 并行
  - 发布门禁推荐 ``ai-tests.sh`` 分进程并行（每套件独立进程）
  - ``AI_TESTS_PARALLEL=0`` 或 ``--serial``：仅调试用串行
"""

from __future__ import annotations

import argparse
import os
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from dataclasses import dataclass
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

SUITES = ("chat", "formats", "memory", "brain")


@dataclass
class SuiteOutcome:
    suite: str
    total: int
    failed: int
    skipped: bool
    skip_reason: str = ""


def _print_section(title: str) -> None:
    print(f"\n{'=' * 60}")
    print(f"  {title}")
    print(f"{'=' * 60}", flush=True)


def _print_result_row(name: str, ok: bool, elapsed_ms: float, session_id: str, detail: str) -> None:
    mark = "PASS" if ok else "FAIL"
    print(f"  [{mark}] {name} ({elapsed_ms:.0f}ms) session={session_id or '-'}", flush=True)
    print(f"         {detail[:280]}", flush=True)


def _run_chat(base: str, token: str, timeout: float, chat_rounds: int, chat_poll_timeout: float) -> SuiteOutcome:
    if os.environ.get("TEST_SKIP_AI_CHAT", "0") == "1":
        print("[ai-tests:chat] skip (TEST_SKIP_AI_CHAT=1)", flush=True)
        return SuiteOutcome("chat", 0, 0, True, "TEST_SKIP_AI_CHAT=1")
    _print_section("AI Chat 工具参数校验")
    if not is_llm_ready(base, token, timeout):
        print("  skip: no enabled LLM provider with API key configured", flush=True)
        return SuiteOutcome("chat", 0, 0, True, "llm not ready")
    print(f"  running {chat_rounds} rounds x {len(AI_CHAT_QUESTIONS)} questions (parallel) ...", flush=True)
    results: list[AiChatCaseResult] = run_ai_chat_tool_loop(
        base,
        token,
        rounds=chat_rounds,
        poll_timeout_sec=chat_poll_timeout,
    )
    failed = 0
    for item in results:
        _print_result_row(item.question, item.ok, item.elapsed_ms, item.session_id, item.detail)
        if not item.ok:
            failed += 1
    return SuiteOutcome("chat", len(results), failed, False)


def _run_formats(
    base: str, token: str, format_rounds: int, format_poll_timeout: float
) -> SuiteOutcome:
    if os.environ.get("TEST_SKIP_AI_PROVIDER_FORMATS", "0") == "1":
        print("[ai-tests:formats] skip (TEST_SKIP_AI_PROVIDER_FORMATS=1)", flush=True)
        return SuiteOutcome("formats", 0, 0, True, "TEST_SKIP_AI_PROVIDER_FORMATS=1")
    _print_section("AI 接入格式（OpenAI Completions / Anthropic Messages）")
    formats = available_provider_formats(base, token)
    if not formats:
        print(
            f"  skip: set {ENV_DEEPSEEK}/{ENV_MINIMAX}, or enable matching providers in UI",
            flush=True,
        )
        return SuiteOutcome("formats", 0, 0, True, "no provider formats")
    labels = ", ".join(
        f"{p.label}({p.env_key if provider_api_key(p.env_key) else 'server'})" for p in formats
    )
    print(
        f"  running {len(formats)} format(s) x {format_rounds} rounds x "
        f"{len(FORMAT_QUESTIONS)} questions: {labels}",
        flush=True,
    )
    format_results = run_ai_provider_format_cases(
        base,
        token,
        rounds=format_rounds,
        poll_timeout_sec=format_poll_timeout,
    )
    failed = 0
    for item in format_results:
        _print_result_row(item.question, item.ok, item.elapsed_ms, item.session_id, item.detail)
        if not item.ok:
            failed += 1
    return SuiteOutcome("formats", len(format_results), failed, False)


def _run_memory(base: str, token: str, memory_poll_timeout: float) -> SuiteOutcome:
    if os.environ.get("TEST_SKIP_AI_MEMORY", "0") == "1":
        print("[ai-tests:memory] skip (TEST_SKIP_AI_MEMORY=1)", flush=True)
        return SuiteOutcome("memory", 0, 0, True, "TEST_SKIP_AI_MEMORY=1")
    _print_section("AI 会话记忆")
    if not deepseek_api_key():
        print(f"  skip: set {ENV_API_KEY} to enable", flush=True)
        return SuiteOutcome("memory", 0, 0, True, f"{ENV_API_KEY} unset")
    print(f"  running cases ({ENV_API_KEY} set) ...", flush=True)
    memory_results: list[MemoryCaseResult] = run_ai_session_memory_cases(
        base,
        token,
        poll_timeout_sec=memory_poll_timeout,
    )
    failed = 0
    for item in memory_results:
        _print_result_row(item.name, item.ok, item.elapsed_ms, item.session_id, item.detail)
        if not item.ok:
            failed += 1
    return SuiteOutcome("memory", len(memory_results), failed, False)


def _run_brain(base: str, token: str, brain_async_poll_timeout: float) -> SuiteOutcome:
    if os.environ.get("TEST_SKIP_AI_BRAIN_ASYNC", "0") == "1":
        print("[ai-tests:brain] skip (TEST_SKIP_AI_BRAIN_ASYNC=1)", flush=True)
        return SuiteOutcome("brain", 0, 0, True, "TEST_SKIP_AI_BRAIN_ASYNC=1")
    _print_section("AI 大脑异步路由（同 session 并行 fan-in / 跨 session 隔离）")
    if not deepseek_api_key():
        print(f"  skip: set {ENV_API_KEY} to enable", flush=True)
        return SuiteOutcome("brain", 0, 0, True, f"{ENV_API_KEY} unset")
    print(f"  running cases in parallel ({ENV_API_KEY} set) ...", flush=True)
    brain_results: list[BrainAsyncCaseResult] = run_ai_brain_async_routing_cases(
        base,
        token,
        poll_timeout_sec=brain_async_poll_timeout,
    )
    failed = 0
    for item in brain_results:
        _print_result_row(item.name, item.ok, item.elapsed_ms, item.session_id, item.detail)
        if not item.ok:
            failed += 1
    return SuiteOutcome("brain", len(brain_results), failed, False)


def main() -> int:
    parser = argparse.ArgumentParser(description="AI integration tests (parallel suites)")
    parser.add_argument(
        "--suite",
        choices=(*SUITES, "all"),
        default=os.environ.get("TEST_AI_SUITE", "all"),
        help="run one suite, or all (default: all, parallel)",
    )
    parser.add_argument(
        "--serial",
        action="store_true",
        default=os.environ.get("AI_TESTS_PARALLEL", "1") == "0",
        help="run suites serially (debug only; release gate must use parallel)",
    )
    args = parser.parse_args()

    base = os.environ.get("TEST_BASE_URL", "http://127.0.0.1:27403").rstrip("/")
    username = os.environ.get("TEST_USERNAME", "admin")
    password = os.environ.get("TEST_PASSWORD", "Databuff@123")
    timeout = float(os.environ.get("TEST_TIMEOUT", "60"))

    chat_rounds = int(os.environ.get("TEST_AI_CHAT_ROUNDS", "2"))
    chat_poll_timeout = float(os.environ.get("TEST_AI_CHAT_POLL_TIMEOUT", "180"))
    format_rounds = int(os.environ.get("TEST_AI_PROVIDER_FORMAT_ROUNDS", "1"))
    format_poll_timeout = float(os.environ.get("TEST_AI_PROVIDER_FORMAT_POLL_TIMEOUT", "180"))
    memory_poll_timeout = float(os.environ.get("TEST_AI_MEMORY_POLL_TIMEOUT", "240"))
    brain_async_poll_timeout = float(os.environ.get("TEST_AI_BRAIN_ASYNC_POLL_TIMEOUT", "300"))

    print(f"[ai-tests] login {base} suite={args.suite} parallel={not args.serial} ...", flush=True)
    token = login(base, username, password, timeout)

    if deepseek_api_key():
        ensure_deepseek_provider(base, token, deepseek_api_key())

    runners = {
        "chat": lambda: _run_chat(base, token, timeout, chat_rounds, chat_poll_timeout),
        "formats": lambda: _run_formats(base, token, format_rounds, format_poll_timeout),
        "memory": lambda: _run_memory(base, token, memory_poll_timeout),
        "brain": lambda: _run_brain(base, token, brain_async_poll_timeout),
    }

    selected = list(SUITES) if args.suite == "all" else [args.suite]
    outcomes: list[SuiteOutcome] = []

    if args.suite == "all" and not args.serial and len(selected) > 1:
        print("[ai-tests] launching suites in parallel (ThreadPool) ...", flush=True)
        with ThreadPoolExecutor(max_workers=len(selected)) as pool:
            futs = {pool.submit(runners[name]): name for name in selected}
            for fut in as_completed(futs):
                outcomes.append(fut.result())
        outcomes.sort(key=lambda o: selected.index(o.suite))
    else:
        if args.suite == "all" and args.serial:
            print("[ai-tests] WARNING: serial mode (debug only)", flush=True)
        for name in selected:
            outcomes.append(runners[name]())

    total = sum(o.total for o in outcomes)
    failed = sum(o.failed for o in outcomes)
    _print_section("AI Tests Summary")
    for o in outcomes:
        if o.skipped:
            print(f"  {o.suite}: SKIP ({o.skip_reason})", flush=True)
        else:
            print(f"  {o.suite}: passed={o.total - o.failed}/{o.total} failed={o.failed}", flush=True)
    print(f"  total={total} passed={total - failed} failed={failed}", flush=True)
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
