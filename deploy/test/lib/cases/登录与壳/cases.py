"""登录页 / 侧栏菜单 HTTP（C1–C5）。logout 放最后，且不使共享 token 失效。"""

from __future__ import annotations

import hashlib
from pathlib import Path

from ..common import ApiCase


CASE_DIR = Path(__file__).resolve().parent
MODULE = "登录与壳"
PAGE = "登录与壳"


def _md5(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()


def build_cases(frm_ms: int, to_ms: int) -> list[ApiCase]:
    seed_user = "admin"
    seed_pass = "Databuff@123"
    return [
        ApiCase(
            PAGE, "产品版本", "GET", "/webapi/user/product/version",
            None, CASE_DIR, module=MODULE, use_token=False,
        ),
        ApiCase(
            PAGE, "demo 账号登录", "POST", "/webapi/user/login",
            {"account": seed_user, "password": _md5(seed_pass)},
            CASE_DIR, module=MODULE, use_token=False,
        ),
        ApiCase(
            PAGE, "错密码登录", "POST", "/webapi/user/login",
            {"account": seed_user, "password": _md5("wrong-password")},
            CASE_DIR, module=MODULE, use_token=False,
        ),
        ApiCase(
            PAGE, "空密码登录", "POST", "/webapi/user/login",
            {"account": seed_user, "password": ""},
            CASE_DIR, module=MODULE, use_token=False,
        ),
        ApiCase(
            PAGE, "登录后菜单", "POST", "/webapi/user/getMenuByAccount",
            {}, CASE_DIR, module=MODULE,
        ),
        ApiCase(
            PAGE, "未登录打菜单", "POST", "/webapi/user/getMenuByAccount",
            {}, CASE_DIR, module=MODULE, use_token=False,
        ),
        ApiCase(
            PAGE, "登出", "POST", "/webapi/user/loginOut",
            {}, CASE_DIR, module=MODULE,
        ),
    ]
