"""Minimal OTLP protobuf writer (ExportTraceServiceRequest). No extra deps."""

from __future__ import annotations


def _varint(n: int) -> bytes:
    out = bytearray()
    n = int(n) & ((1 << 64) - 1)
    while n > 0x7F:
        out.append((n & 0x7F) | 0x80)
        n >>= 7
    out.append(n)
    return bytes(out)


def _tag(field: int, wire: int) -> bytes:
    return _varint((field << 3) | wire)


def encode_bytes(field: int, data: bytes) -> bytes:
    return _tag(field, 2) + _varint(len(data)) + data


def encode_string(field: int, value: str) -> bytes:
    return encode_bytes(field, value.encode("utf-8"))


def encode_varint_field(field: int, value: int) -> bytes:
    return _tag(field, 0) + _varint(value)


def encode_fixed64(field: int, value: int) -> bytes:
    return _tag(field, 1) + int(value).to_bytes(8, "little", signed=False)


def encode_any_string(value: str) -> bytes:
    return encode_string(1, value)


def encode_key_value(key: str, value: str) -> bytes:
    return encode_string(1, key) + encode_bytes(2, encode_any_string(value))


def encode_span(
    *,
    trace_id: bytes,
    span_id: bytes,
    parent_span_id: bytes,
    name: str,
    kind: int,
    start_unix_nano: int,
    end_unix_nano: int,
    attributes: list[tuple[str, str]],
) -> bytes:
    body = bytearray()
    body += encode_bytes(1, trace_id)
    body += encode_bytes(2, span_id)
    if parent_span_id:
        body += encode_bytes(4, parent_span_id)
    body += encode_string(5, name)
    body += encode_varint_field(6, kind)
    body += encode_fixed64(7, start_unix_nano)
    body += encode_fixed64(8, end_unix_nano)
    for key, value in attributes:
        body += encode_bytes(9, encode_key_value(key, value))
    return bytes(body)


def encode_resource(attributes: list[tuple[str, str]]) -> bytes:
    body = bytearray()
    for key, value in attributes:
        body += encode_bytes(1, encode_key_value(key, value))
    return bytes(body)


def encode_scope_spans(spans: list[bytes]) -> bytes:
    body = bytearray()
    for span in spans:
        body += encode_bytes(2, span)
    return bytes(body)


def encode_resource_spans(resource: bytes, scope_spans: bytes) -> bytes:
    return encode_bytes(1, resource) + encode_bytes(2, scope_spans)


def encode_export_trace_request(resource_spans: list[bytes]) -> bytes:
    body = bytearray()
    for item in resource_spans:
        body += encode_bytes(1, item)
    return bytes(body)


SPAN_KIND_SERVER = 2
SPAN_KIND_CLIENT = 3
