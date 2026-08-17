"""告警中心 HTTP：列表 / 筛参 / 趋势 / 详情 / 空窗 / 非法 id / 通知空表。"""

from __future__ import annotations

from pathlib import Path

from ..common import ApiCase, time_window


CASE_DIR = Path(__file__).resolve().parent
MODULE = "告警中心"
PAGE = "告警中心"


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    tw = time_window(frm_ms, to_ms)
    empty_tw = {"from": 1, "to": 2, "start": 1, "end": 2}
    return [
        ApiCase(PAGE, "告警列表", "POST", "/webapi/alarm/list", tw, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "告警筛参", "POST", "/webapi/alarm/queryParams", tw, CASE_DIR, module=MODULE),
        ApiCase(
            PAGE, "告警按级别筛", "POST", "/webapi/alarm/list",
            {**tw, "level": [3]}, CASE_DIR, module=MODULE,
        ),
        ApiCase(PAGE, "告警趋势", "POST", "/webapi/alarm/trend", {**tw, "interval": 60}, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "告警空窗", "POST", "/webapi/alarm/list", empty_tw, CASE_DIR, module=MODULE),
        ApiCase(
            PAGE, "告警详情", "GET", "/webapi/alarm/detail/pending-id",
            None, CASE_DIR, module=MODULE, needs_alarm_id=True,
        ),
        ApiCase(
            PAGE, "告警非法详情", "GET", "/webapi/alarm/detail/not-a-real-id",
            None, CASE_DIR, module=MODULE,
        ),
        ApiCase(PAGE, "通知记录", "POST", "/webapi/notify/records", tw, CASE_DIR, module=MODULE),
    ]
