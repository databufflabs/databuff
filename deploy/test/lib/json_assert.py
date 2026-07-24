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

Object lists (list of dict): always sorted by every key (sorted key name, then value)
before element-wise compare, so API response order never matters. Primitive lists
(numbers / strings / trend rows) stay order-sensitive.
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


def _span_resource_matches(span: dict[Any, Any], resource_key: str) -> bool:
    resource = str(span.get("resource") or "")
    name = str(span.get("name") or "")
    return resource == resource_key or resource_key in resource or resource_key in name


def _is_object_list(items: list[Any]) -> bool:
    return bool(items) and all(isinstance(item, dict) for item in items)


def _canonical_object_sort_key(item: dict[Any, Any]) -> tuple[Any, ...]:
    """Sort key = every field: sorted key names, then stringified values."""
    parts: list[tuple[str, str]] = []
    for key in sorted(item.keys(), key=lambda k: str(k)):
        value = item[key]
        if isinstance(value, (dict, list)):
            encoded = json.dumps(value, ensure_ascii=False, sort_keys=True, default=str)
        elif value is None:
            encoded = ""
        else:
            encoded = str(value)
        parts.append((str(key), encoded))
    return tuple(parts)


def _match_list(actual: list[Any], expected: list[Any], path: str) -> None:
    # Generic: object arrays are multisets — sort by all keys, then compare.
    if _is_object_list(actual) and _is_object_list(expected):
        actual = sorted(actual, key=_canonical_object_sort_key)
        expected = sorted(expected, key=_canonical_object_sort_key)
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
