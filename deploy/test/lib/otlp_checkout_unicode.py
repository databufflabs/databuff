"""Case-owned OTLP seed: service-a checkout-style chain with extra span attrs.

Does not touch OtlpTraceFixture / demo seeder. Timestamps must sit after the
frozen query window end so exact callCnt expected files stay green.
"""

from __future__ import annotations

import os
import secrets
import time
import urllib.error
import urllib.request
from typing import Iterable

from otlp_protobuf import (
    SPAN_KIND_CLIENT,
    SPAN_KIND_SERVER,
    encode_export_trace_request,
    encode_resource,
    encode_resource_spans,
    encode_scope_spans,
    encode_span,
)

SERVICE_A = "service-a"
CHECKOUT_NAME = "GET /demo/checkout"
REDIS_NAME = "GET cart"
SEED_C01C02 = "c01c02"
SEED_C03 = "c03"

# Literal backslash-u sequences (not Python unicode escapes).
ESC_ZH = "\\u4e2d\\u6587"
MIXED_HELLO_WORLD = "你好\\u4e16\\u754c"

# Same Basic header as OtlpHttpExporter (local ingest ignores it).
_OTLP_BASIC = "Basic YWRtaW5AZXhhbXBsZS5jb206T3Blbk9ic2VydmVAMjAyNg=="


def _hex(data: bytes) -> str:
    return data.hex()


def _service_a_resource() -> bytes:
    return encode_resource(
        [
            ("service.name", SERVICE_A),
            ("host.name", "demo-host-a"),
            ("service.instance.id", "service-a-1"),
            ("k8s.namespace.name", "demo"),
            ("telemetry.sdk.language", "java"),
        ]
    )


def build_checkout_unicode_bytes(
    *,
    prompt_value: str,
    redis_body: str,
    start_ms: int,
) -> tuple[bytes, str]:
    """Two-span service-a chain: HTTP checkout (LLM prefix) + Redis (no prefix)."""
    trace_id = secrets.token_bytes(16)
    root_id = secrets.token_bytes(8)
    redis_id = secrets.token_bytes(8)
    start_ns = int(start_ms) * 1_000_000
    root_end_ns = start_ns + 40_000_000
    redis_start_ns = start_ns + 5_000_000
    redis_end_ns = start_ns + 18_000_000

    checkout = encode_span(
        trace_id=trace_id,
        span_id=root_id,
        parent_span_id=b"",
        name=CHECKOUT_NAME,
        kind=SPAN_KIND_SERVER,
        start_unix_nano=start_ns,
        end_unix_nano=root_end_ns,
        attributes=[
            ("http.method", "GET"),
            ("http.status_code", "200"),
            ("url.full", "/demo/checkout"),
            ("gen.ai.prompt", prompt_value),
        ],
    )
    redis = encode_span(
        trace_id=trace_id,
        span_id=redis_id,
        parent_span_id=root_id,
        name=REDIS_NAME,
        kind=SPAN_KIND_CLIENT,
        start_unix_nano=redis_start_ns,
        end_unix_nano=redis_end_ns,
        attributes=[
            ("db.system", "redis"),
            ("db.statement", "GET cart:10001"),
            ("server.address", "redis"),
            ("server.port", "6379"),
            ("http.body", redis_body),
        ],
    )
    payload = encode_export_trace_request(
        [
            encode_resource_spans(
                _service_a_resource(),
                encode_scope_spans([checkout, redis]),
            )
        ]
    )
    return payload, _hex(trace_id)


def post_otlp_traces(ingest_base: str, payload: bytes, timeout: float = 15.0) -> int:
    url = ingest_base.rstrip("/") + "/v1/traces"
    request = urllib.request.Request(
        url,
        data=payload,
        method="POST",
        headers={
            "Content-Type": "application/x-protobuf",
            "Authorization": _OTLP_BASIC,
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return int(response.status)
    except urllib.error.HTTPError as error:
        return int(error.code)


def default_otlp_url() -> str:
    return (
        os.environ.get("TEST_OTLP_URL")
        or os.environ.get("OTEL_EXPORTER_OTLP_ENDPOINT")
        or "http://127.0.0.1:4318"
    )


def seed_llm_unicode_traces(
    ingest_base: str,
    seed_keys: Iterable[str],
    *,
    start_ms: int,
) -> dict[str, str]:
    """Post one checkout-style chain per seed key. Returns hex traceId by key."""
    wanted = set(seed_keys)
    out: dict[str, str] = {}
    specs = {
        SEED_C01C02: (ESC_ZH, ESC_ZH),
        SEED_C03: (MIXED_HELLO_WORLD, ESC_ZH),
    }
    unknown = wanted - set(specs)
    if unknown:
        raise ValueError(f"unknown llm-unicode seed keys: {sorted(unknown)}")
    for key in (SEED_C01C02, SEED_C03):
        if key not in wanted:
            continue
        prompt, redis_body = specs[key]
        payload, trace_id = build_checkout_unicode_bytes(
            prompt_value=prompt,
            redis_body=redis_body,
            start_ms=start_ms,
        )
        status = post_otlp_traces(ingest_base, payload)
        if status < 200 or status >= 300:
            raise RuntimeError(f"OTLP seed {key} failed HTTP {status} via {ingest_base}")
        out[key] = trace_id
        print(f"[test] seeded llm-unicode {key} traceId={trace_id}")
        time.sleep(0.05)
    return out
