"""配置管理只读打开：告警规则表、模型 provider、登录会话、能力卡。"""

from __future__ import annotations

from pathlib import Path

from ..common import ApiCase


CASE_DIR = Path(__file__).resolve().parent
MODULE = "配置管理"
PAGE = "配置管理"


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    return [
        ApiCase(PAGE, "告警配置列表", "POST", "/webapi/monitor/search", {}, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "模型配置列表", "GET", "/webapi/api/v1/config/ai/providers", None, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "登录会话配置", "GET", "/webapi/system/systemBase", None, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "首页能力卡", "GET", "/webapi/api/v1/ai/capabilities", None, CASE_DIR, module=MODULE),
    ]
