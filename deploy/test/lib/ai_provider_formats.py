"""两种大模型接入格式集成测试 — OpenAI Completions vs Anthropic Messages.

回归 AgentScope tool args（content / input）在两种协议下均不出现
``Parameter validation failed`` / ``required property``。

环境变量门控（未设置则尝试使用实例上已启用的同名 provider）：
  - ``DEEPSEEK_API_KEY`` → OpenAI Completions（deepseek / openai-completions）
  - ``MINIMAX_API_KEY``  → Anthropic Messages（minimax / anthropic-messages）

跳过整组：``TEST_SKIP_AI_PROVIDER_FORMATS=1``
"""

from __future__ import annotations

import os
from dataclasses import dataclass
from typing import Any

from ai_chat_integration import (
    _http_json,
    run_ai_chat_tool_loop,
    AiChatCaseResult,
)

GROUP_PROVIDER_FORMATS = "接入格式"

ENV_DEEPSEEK = "DEEPSEEK_API_KEY"
ENV_MINIMAX = "MINIMAX_API_KEY"

# 聚焦会触发工具调用的问数题；每格式 1 轮，控制费用与耗时。
FORMAT_QUESTIONS: list[tuple[str, str]] = [
    ("queryServicesAll", "列出最近1小时的所有服务列表"),
    ("queryServiceTopology", "查询 [elasticsearch]es:9200 最近1小时的上下游服务拓扑"),
    ("queryTraceListByCondition", "查询 service-a 最近1小时内 HTTP 慢请求的 trace 列表"),
]


@dataclass(frozen=True)
class ProviderFormatProfile:
    """一种对外 LLM 接入格式的代表 provider。"""

    label: str
    api_type: str
    provider_code: str
    base_url: str
    model: str
    env_key: str


PROVIDER_FORMATS: tuple[ProviderFormatProfile, ...] = (
    ProviderFormatProfile(
        label="OpenAI Completions",
        api_type="openai-completions",
        provider_code="deepseek",
        base_url="https://api.deepseek.com",
        model="deepseek-v4-flash",
        env_key=ENV_DEEPSEEK,
    ),
    ProviderFormatProfile(
        label="Anthropic Messages",
        api_type="anthropic-messages",
        provider_code="minimax",
        base_url="https://api.minimaxi.com/anthropic",
        model="MiniMax-M3",
        env_key=ENV_MINIMAX,
    ),
)


def provider_api_key(env_key: str) -> str | None:
    value = os.environ.get(env_key, "").strip()
    return value or None


def _list_providers(base: str, token: str, timeout: float = 30.0) -> list[dict[str, Any]]:
    payload = _http_json(
        "GET",
        f"{base.rstrip('/')}/webapi/api/v1/config/ai/providers",
        token=token,
        timeout=timeout,
    )
    return payload if isinstance(payload, list) else []


def _provider_ready_on_server(
    providers: list[dict[str, Any]],
    profile: ProviderFormatProfile,
) -> bool:
    for item in providers:
        if str(item.get("providerCode") or "") != profile.provider_code:
            continue
        if not item.get("enabled") or not item.get("configured"):
            return False
        api_type = str(item.get("apiType") or "").strip()
        return api_type == profile.api_type
    return False


def available_provider_formats(
    base: str | None = None,
    token: str | None = None,
    *,
    timeout: float = 30.0,
) -> list[ProviderFormatProfile]:
    """Env key 优先；否则回退到实例上已启用且 apiType 匹配的 provider。"""
    server_providers: list[dict[str, Any]] | None = None
    if base and token:
        try:
            server_providers = _list_providers(base, token, timeout=timeout)
        except Exception:
            server_providers = []

    ready: list[ProviderFormatProfile] = []
    for profile in PROVIDER_FORMATS:
        if provider_api_key(profile.env_key):
            ready.append(profile)
            continue
        if server_providers is not None and _provider_ready_on_server(server_providers, profile):
            ready.append(profile)
    return ready


def ensure_provider_format(
    base: str,
    token: str,
    profile: ProviderFormatProfile,
    api_key: str,
    timeout: float = 30.0,
) -> None:
    """写入 API Key / baseUrl / model，并显式固定 apiType（接入格式）。"""
    root = base.rstrip("/")
    _http_json(
        "PUT",
        f"{root}/webapi/api/v1/config/ai/providers/{profile.provider_code}/detail",
        {
            "providerCode": profile.provider_code,
            "apiType": profile.api_type,
            "baseUrl": profile.base_url,
            "apiKey": api_key,
            "enabled": True,
            "defaultProvider": False,
            "defaultModelId": profile.model,
            "models": [
                {
                    "modelId": profile.model,
                    "displayName": profile.model,
                    "defaultModel": True,
                }
            ],
        },
        token=token,
        timeout=timeout,
    )
    detail = _http_json(
        "GET",
        f"{root}/webapi/api/v1/config/ai/providers/{profile.provider_code}/detail",
        token=token,
        timeout=timeout,
    )
    actual_type = str(detail.get("apiType") or "").strip()
    if actual_type != profile.api_type:
        raise RuntimeError(
            f"provider {profile.provider_code} apiType={actual_type!r}, "
            f"expected {profile.api_type!r}"
        )


def run_ai_provider_format_cases(
    base: str,
    token: str,
    *,
    rounds: int = 1,
    poll_interval_sec: float = 2.0,
    poll_timeout_sec: float = 180.0,
    expert_id: str = "data",
) -> list[AiChatCaseResult]:
    """对每种可用接入格式跑工具参数校验；按请求覆盖 modelProviderCode。"""
    results: list[AiChatCaseResult] = []
    for profile in available_provider_formats(base, token):
        api_key = provider_api_key(profile.env_key)
        if api_key:
            ensure_provider_format(base, token, profile, api_key)
        prefix = f"[{profile.label}] "
        results.extend(
            run_ai_chat_tool_loop(
                base,
                token,
                rounds=rounds,
                poll_interval_sec=poll_interval_sec,
                poll_timeout_sec=poll_timeout_sec,
                expert_id=expert_id,
                model_provider_code=profile.provider_code,
                model_name=profile.model,
                questions=FORMAT_QUESTIONS,
                name_prefix=prefix,
            )
        )
    return results
