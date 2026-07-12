"""Strict API payload comparison.

Only time-related values are ignored; everything else must match exactly.

Skipped (never compared):
- millisecond / nanosecond / second epoch timestamps (scalar values)
- wall-clock datetime strings (yyyy-MM-dd HH:mm:ss[.fraction])
- dict keys that are epoch millisecond timestamps (values compared in time order)
- named time fields: ts, timestamp, time, startTime, endTime, fromTime, toTime
- epochSeconds field values
- trace/span hex identifiers (32/16 char), not business metrics
- first column of [timestamp, value] trend rows when it is a ms timestamp
"""

from __future__ import annotations

import json
import re
import time
from typing import Any

WALL_CLOCK_RE = re.compile(r"^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}(?:\.\d+)?$")
TRACE_ID_RE = re.compile(r"^[0-9a-f]{32}$")
SPAN_ID_RE = re.compile(r"^[0-9a-f]{16}$")

TIME_FIELD_NAMES = frozenset({
    "ts",
    "timestamp",
    "time",
    "startTime",
    "endTime",
    "fromTime",
    "toTime",
    "epochSeconds",
})

# serviceSeries / v1 serviceSeries rows: SQL ORDER BY ts only; service order is unstable.
SERVICE_METRIC_POINT_KEYS = frozenset({
    "service",
    "requestCount",
    "errorCount",
    "avgDuration",
})

# Portal span payloads may add nullable HTTP fields that older expected files omit.
ALLOWED_ACTUAL_EXTRA_KEYS = frozenset({
    "metaHttpStatusCode",
})


class JsonAssertError(Exception):
    def __init__(self, path: str, message: str) -> None:
        self.path = path
        self.message = message
        super().__init__(f"{path}: {message}")


def ms_timestamp_digits() -> int:
    return len(str(int(time.time() * 1000)))


def is_ms_timestamp(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        text = str(int(value))
    elif isinstance(value, str) and value.isdigit():
        text = value
    else:
        return False
    return text.startswith("17") and len(text) == ms_timestamp_digits()


def is_ns_timestamp(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        text = str(int(value))
    elif isinstance(value, str) and value.isdigit():
        text = value
    else:
        return False
    return text.startswith("17") and len(text) == ms_timestamp_digits() + 6


def is_wall_clock_datetime(value: Any) -> bool:
    return isinstance(value, str) and bool(WALL_CLOCK_RE.match(value))


def is_epoch_seconds(value: Any) -> bool:
    if isinstance(value, bool):
        return False
    if isinstance(value, (int, float)):
        text = str(int(value))
    elif isinstance(value, str) and value.isdigit():
        text = value
    else:
        return False
    return text.startswith("17") and len(text) == 10


def is_volatile_id(value: Any) -> bool:
    if not isinstance(value, str):
        return False
    return bool(TRACE_ID_RE.match(value) or SPAN_ID_RE.match(value))


def is_skipped_scalar(value: Any) -> bool:
    return (
        is_ms_timestamp(value)
        or is_ns_timestamp(value)
        or is_epoch_seconds(value)
        or is_wall_clock_datetime(value)
        or is_volatile_id(value)
    )


def is_time_dict_key(key: Any) -> bool:
    return is_ms_timestamp(key) or is_epoch_seconds(key)


def assert_matches(actual: Any, expected: Any, path: str = "$") -> tuple[bool, str]:
    try:
        _match(actual, expected, path)
        return True, "json match ok"
    except JsonAssertError as exc:
        return False, exc.message


def _partition_dict(data: dict[Any, Any]) -> tuple[dict[Any, Any], list[Any]]:
    normal: dict[Any, Any] = {}
    ts_values: list[Any] = []
    for key, value in data.items():
        if is_time_dict_key(key):
            ts_values.append(value)
        else:
            normal[key] = value
    return normal, ts_values


def _strip_trend_row(row: Any) -> Any:
    if isinstance(row, list) and len(row) >= 2 and is_ms_timestamp(row[0]):
        return row[1:]
    return row


def _match_dict(actual: dict[Any, Any], expected: dict[Any, Any], path: str) -> None:
    act_normal, act_ts = _partition_dict(actual)
    exp_normal, exp_ts = _partition_dict(expected)

    if set(act_normal.keys()) != set(exp_normal.keys()):
        missing = sorted(set(exp_normal.keys()) - set(act_normal.keys()))
        extra = set(act_normal.keys()) - set(exp_normal.keys())
        if missing:
            raise JsonAssertError(path, f"missing keys {missing}")
        disallowed_extra = sorted(extra - ALLOWED_ACTUAL_EXTRA_KEYS)
        if disallowed_extra:
            raise JsonAssertError(path, f"unexpected extra keys {disallowed_extra}")

    for key, exp_val in exp_normal.items():
        if key in TIME_FIELD_NAMES:
            continue
        act_val = act_normal[key]
        if key == "values" and isinstance(exp_val, list):
            _match(
                [_strip_trend_row(row) for row in act_val],
                [_strip_trend_row(row) for row in exp_val],
                f"{path}.{key}",
            )
            continue
        _match(act_val, exp_val, f"{path}.{key}")

    if len(act_ts) != len(exp_ts):
        raise JsonAssertError(
            path,
            f"expected {len(exp_ts)} time buckets, got {len(act_ts)}",
        )
    for idx, (act_val, exp_val) in enumerate(zip(act_ts, exp_ts)):
        _match(act_val, exp_val, f"{path}[bucket:{idx}]")


def _is_service_metric_point_list(items: list[Any]) -> bool:
    if not items:
        return True
    for item in items:
        if not isinstance(item, dict):
            return False
        keys = set(item.keys()) - TIME_FIELD_NAMES
        if not SERVICE_METRIC_POINT_KEYS <= keys:
            return False
    return True


def _sort_service_metric_points(items: list[Any]) -> list[Any]:
    def sort_key(item: dict[Any, Any]) -> tuple[Any, ...]:
        ts = item.get("ts")
        service = item.get("service")
        return (
            str(ts) if ts is not None else "",
            str(service) if service is not None else "",
            item.get("requestCount"),
            item.get("errorCount"),
            item.get("avgDuration"),
        )

    return sorted(items, key=sort_key)


def _is_component_service_stat_list(items: list[Any]) -> bool:
    if not items:
        return True
    return all(
        isinstance(item, dict) and "serviceId" in item and "componentType" in item
        for item in items
    )


def _sort_component_service_stats(items: list[Any]) -> list[Any]:
    return sorted(
        items,
        key=lambda item: (
            str(item.get("serviceId") or ""),
            str(item.get("componentType") or ""),
        ),
    )


def _is_service_id_name_list(items: list[Any]) -> bool:
    if not items:
        return True
    return all(
        isinstance(item, dict) and "serviceId" in item and "serviceName" in item
        for item in items
    )


def _sort_service_id_name_rows(items: list[Any]) -> list[Any]:
    return sorted(items, key=lambda item: str(item.get("serviceId") or ""))


def _is_span_row_list(items: list[Any]) -> bool:
    if not items:
        return True
    for item in items:
        if not isinstance(item, dict) or "resource" not in item:
            return False
        if "span_id" not in item and "spanId" not in item:
            return False
    return True


def _span_resource_matches(span: dict[Any, Any], resource_key: str) -> bool:
    resource = str(span.get("resource") or "")
    name = str(span.get("name") or "")
    return resource == resource_key or resource_key in resource or resource_key in name


def _sort_span_rows(items: list[Any]) -> list[Any]:
    def sort_key(item: dict[Any, Any]) -> tuple[Any, ...]:
        parent_flag = item.get("is_parent")
        if parent_flag is None:
            parent_flag = item.get("isParent")
        return (
            str(item.get("resource") or item.get("name") or ""),
            str(item.get("service") or ""),
            item.get("duration"),
            item.get("error"),
            -(int(parent_flag or 0)),
        )

    return sorted(items, key=sort_key)


def _is_service_flow_edge_list(items: list[Any]) -> bool:
    if not items:
        return True
    return all(
        isinstance(item, dict)
        and "srcService" in item
        and "dstService" in item
        and "callCount" in item
        for item in items
    )


def _sort_service_flow_edges(items: list[Any]) -> list[Any]:
    return sorted(
        items,
        key=lambda item: (
            str(item.get("srcService") or ""),
            -(int(item.get("callCount") or 0)),
            str(item.get("dstService") or ""),
        ),
    )


def _match_list(actual: list[Any], expected: list[Any], path: str) -> None:
    if _is_service_metric_point_list(actual) and _is_service_metric_point_list(expected):
        actual = _sort_service_metric_points(actual)
        expected = _sort_service_metric_points(expected)
    elif _is_component_service_stat_list(actual) and _is_component_service_stat_list(expected):
        actual = _sort_component_service_stats(actual)
        expected = _sort_component_service_stats(expected)
    elif _is_service_id_name_list(actual) and _is_service_id_name_list(expected):
        actual = _sort_service_id_name_rows(actual)
        expected = _sort_service_id_name_rows(expected)
    elif _is_span_row_list(actual) and _is_span_row_list(expected):
        actual = _sort_span_rows(actual)
        expected = _sort_span_rows(expected)
    elif _is_service_flow_edge_list(actual) and _is_service_flow_edge_list(expected):
        actual = _sort_service_flow_edges(actual)
        expected = _sort_service_flow_edges(expected)
    if len(actual) != len(expected):
        raise JsonAssertError(path, f"expected list length {len(expected)}, got {len(actual)}")
    for idx, (act_item, exp_item) in enumerate(zip(actual, expected)):
        _match(act_item, exp_item, f"{path}[{idx}]")


def _match_partial_dict(actual: dict[Any, Any], expected: dict[Any, Any], path: str) -> None:
    for key, exp_val in expected.items():
        if key not in actual:
            raise JsonAssertError(path, f"missing key {key!r}")
        _match(actual[key], exp_val, f"{path}.{key}")


def _match_special(actual: Any, expected: dict[str, Any], path: str) -> bool:
    """Return True if expected was a special matcher and was handled."""
    if len(expected) != 1:
        return False
    key, arg = next(iter(expected.items()))
    if key == "$range":
        if not isinstance(actual, (int, float)) or isinstance(actual, bool):
            raise JsonAssertError(path, f"expected number in range {arg}, got {actual!r}")
        if not (arg[0] <= actual <= arg[1]):
            raise JsonAssertError(path, f"expected {actual!r} in range {arg}")
        return True
    if key == "$minLength":
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        if len(actual) < arg:
            raise JsonAssertError(path, f"expected list length >= {arg}, got {len(actual)}")
        return True
    if key == "$containsKeys":
        if not isinstance(actual, dict):
            raise JsonAssertError(path, f"expected object, got {type(actual).__name__}")
        missing = [item for item in arg if item not in actual]
        if missing:
            raise JsonAssertError(path, f"missing keys {missing}")
        return True
    if key == "$containsServices":
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        actual_services = {
            str(item.get("service"))
            for item in actual
            if isinstance(item, dict) and item.get("service") is not None
        }
        missing = [item for item in arg if item not in actual_services]
        if missing:
            raise JsonAssertError(path, f"missing child services {missing}")
        return True
    if key == "$containsSpanServiceTypes":
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        for resource_key, expected_fields in arg.items():
            if not isinstance(expected_fields, dict):
                raise JsonAssertError(path, f"expected object matcher for span {resource_key!r}")
            match = next(
                (
                    item
                    for item in actual
                    if isinstance(item, dict) and _span_resource_matches(item, str(resource_key))
                ),
                None,
            )
            if match is None:
                raise JsonAssertError(path, f"missing span matching resource {resource_key!r}")
            _match_partial_dict(match, expected_fields, f"{path}[{resource_key}]")
        return True
    if key == "$nestedServiceChildren":
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        if not isinstance(arg, dict):
            raise JsonAssertError(path, "expected object matcher for $nestedServiceChildren")
        for service_name, child_matcher in arg.items():
            node = next(
                (
                    item
                    for item in actual
                    if isinstance(item, dict) and str(item.get("service")) == str(service_name)
                ),
                None,
            )
            if node is None:
                raise JsonAssertError(path, f"missing service node {service_name!r}")
            _match(node.get("children"), child_matcher, f"{path}.{service_name}.children")
        return True
    if key == "$minNonNullValues":
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        count = _count_non_null_trend_values(actual)
        if count < arg:
            raise JsonAssertError(path, f"expected >= {arg} non-null values, got {count}")
        return True
    if key == "$eachNonNullRange":
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        for idx, item in enumerate(actual):
            value = _trend_value(item)
            if value is None:
                continue
            if not isinstance(value, (int, float)) or isinstance(value, bool):
                raise JsonAssertError(f"{path}[{idx}]", f"expected number, got {value!r}")
            if not (arg[0] <= value <= arg[1]):
                raise JsonAssertError(f"{path}[{idx}]", f"expected {value!r} in range {arg}")
        return True
    return False


def _trend_value(item: Any) -> Any:
    if isinstance(item, list):
        if not item:
            return None
        if len(item) == 1:
            return item[0]
        return item[-1]
    return item


def _count_non_null_trend_values(items: list[Any]) -> int:
    return sum(1 for item in items if _trend_value(item) is not None)


def _match(actual: Any, expected: Any, path: str) -> None:
    if isinstance(expected, dict) and expected and all(str(k).startswith("$") for k in expected):
        if isinstance(actual, list):
            for key, arg in expected.items():
                if key == "$minLength":
                    if len(actual) < arg:
                        raise JsonAssertError(path, f"expected list length >= {arg}, got {len(actual)}")
                elif key == "$eachNonNullItem":
                    matched = 0
                    for idx, item in enumerate(actual):
                        if isinstance(item, dict) and item.get("value") is None:
                            continue
                        if isinstance(item, dict):
                            _match_partial_dict(item, arg, f"{path}[{idx}]")
                        else:
                            _match(item, arg, f"{path}[{idx}]")
                        matched += 1
                    min_non_null = expected.get("$minNonNullItems")
                    if min_non_null is not None and matched < min_non_null:
                        raise JsonAssertError(path, f"expected >= {min_non_null} non-null items, got {matched}")
                elif key == "$minNonNullItems":
                    if not isinstance(actual, list):
                        raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
                    matched = sum(
                        1 for item in actual
                        if isinstance(item, dict) and item.get("value") is not None
                    )
                    if matched < arg:
                        raise JsonAssertError(path, f"expected >= {arg} non-null items, got {matched}")
                elif not _match_special(actual, {key: arg}, path):
                    raise JsonAssertError(path, f"unknown matcher {key}")
            return
        for key, arg in expected.items():
            if not _match_special(actual, {key: arg}, path):
                raise JsonAssertError(path, f"unknown matcher {key}")
        return
    if isinstance(expected, dict) and _match_special(actual, expected, path):
        return
    if is_skipped_scalar(expected) or is_skipped_scalar(actual):
        return

    if expected is None or actual is None:
        if actual != expected:
            raise JsonAssertError(
                path,
                f"expected {json.dumps(expected, ensure_ascii=False)!r}, got {json.dumps(actual, ensure_ascii=False)!r}",
            )
        return

    if isinstance(expected, dict):
        if not isinstance(actual, dict):
            raise JsonAssertError(path, f"expected object, got {type(actual).__name__}")
        _match_dict(actual, expected, path)
        return

    if isinstance(expected, list):
        if not isinstance(actual, list):
            raise JsonAssertError(path, f"expected list, got {type(actual).__name__}")
        _match_list(actual, expected, path)
        return

    if isinstance(expected, bool):
        if actual is not True and actual is not False:
            raise JsonAssertError(path, f"expected bool, got {type(actual).__name__}")
        if actual != expected:
            raise JsonAssertError(path, f"expected {expected!r}, got {actual!r}")
        return

    if isinstance(expected, (int, float)):
        if not isinstance(actual, (int, float)):
            raise JsonAssertError(path, f"expected number, got {type(actual).__name__}")
        if actual != expected:
            raise JsonAssertError(path, f"expected {expected!r}, got {actual!r}")
        return

    if actual != expected:
        raise JsonAssertError(
            path,
            f"expected {json.dumps(expected, ensure_ascii=False)!r}, got {json.dumps(actual, ensure_ascii=False)!r}",
        )
