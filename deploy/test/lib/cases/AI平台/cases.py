"""AI 平台只读列表 HTTP（C6 / C10 / C12 / C13 / C15）。写路径与真对话不在本组。"""

from __future__ import annotations

from pathlib import Path

from ..common import ApiCase


CASE_DIR = Path(__file__).resolve().parent
MODULE = "AI平台"
PAGE = "AI平台"


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    return [
        ApiCase(PAGE, "能力卡列表", "GET", "/webapi/api/v1/ai/capabilities", None, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "工具列表", "GET", "/webapi/api/v1/ai/tools", None, CASE_DIR, module=MODULE),
        ApiCase(
            PAGE, "非法工具", "GET", "/webapi/api/v1/ai/tools/not-a-real-tool-id",
            None, CASE_DIR, module=MODULE, expect_status=404,
        ),
        ApiCase(PAGE, "技能列表", "GET", "/webapi/api/v1/ai/skills", None, CASE_DIR, module=MODULE),
        ApiCase(PAGE, "专家列表", "GET", "/webapi/api/v1/ai/experts", None, CASE_DIR, module=MODULE),
    ]
