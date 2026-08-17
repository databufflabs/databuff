#!/usr/bin/env python3
"""C 类：产品真没有 / 写路径不该在本需求装绿。源码声明，禁止假绿、禁止改 Coming Soon。"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

LIB_ROOT = Path(__file__).resolve().parent
REPO = LIB_ROOT.parents[2]
MENU = REPO / "ai-apm-web" / "src" / "main" / "java" / "com" / "databuff" / "apm" / "web" / "admin" / "support" / "OpenSourceMenuCatalog.java"
CASES = LIB_ROOT / "cases"
WEB_JAVA = REPO / "ai-apm-web" / "src" / "main" / "java"

sys.path.insert(0, str(LIB_ROOT))
from coming_soon_guard import test as coming_soon_still  # noqa: E402


class Skip(Exception):
    pass


class Fail(Exception):
    pass


def read(path: Path) -> str:
    if not path.is_file():
        raise Fail(f"missing {path}")
    return path.read_text(encoding="utf-8")


def java_has(needle: str) -> bool:
    for path in WEB_JAVA.rglob("*.java"):
        if needle in path.read_text(encoding="utf-8"):
            return True
    return False


def case_c11() -> None:
    """工具启停/test 写路径存在，本需求只测列表，禁止打 enable 装绿。"""
    text = read(WEB_JAVA / "com/databuff/apm/web/ai/platform/api/AiToolController.java")
    if "/enable" not in text or "/test" not in text:
        raise Fail("C11：源码里找不到 tools enable/test，缺口声明对不上")
    raise Skip("C11：工具 enable/disable/test 是写路径，本需求只测列表，禁止打写接口装绿")


def case_c14() -> None:
    text = read(WEB_JAVA / "com/databuff/apm/web/ai/platform/api/AiSkillController.java")
    if "import/preview" not in text or "/validate" not in text:
        raise Fail("C14：源码里找不到 skills import/preview 或 validate")
    raise Skip("C14：技能导入预览/校验是写路径，本需求只测列表，禁止装绿")


def case_c16() -> None:
    text = read(WEB_JAVA / "com/databuff/apm/web/ai/platform/api/AiExpertController.java")
    if "/enable" not in text or "/debug" not in text:
        raise Fail("C16：源码里找不到 experts enable/debug")
    raise Skip("C16：专家启停/debug 写路径且 debug 依赖真模型，走 C7 不顶替本条，禁止装绿")


def case_c31() -> None:
    text = read(MENU)
    if "故障列表" in text or "/alarmCenter/rootCause" in text:
        raise Fail("C31：开源菜单挂了故障列表，不能再声明未挂")
    # 前端路由还在，菜单没有
    routes = read(REPO / "ai-apm-frontend" / "src" / "router" / "route-data.ts")
    if "故障列表" not in routes:
        raise Fail("C31：route-data 也没有故障列表，声明对不上")


def case_c32() -> None:
    text = read(MENU)
    for path in ("/alarmCenter/problemAnalysis", "/alarmCenter/problemDetail", "/alarmCenter/rootCauseAnalysis"):
        if path in text:
            raise Fail(f"C32：开源菜单挂了 {path}，不能声明未挂")
    routes = read(REPO / "ai-apm-frontend" / "src" / "router" / "route-data.ts")
    for path in ("/alarmCenter/problemAnalysis", "/alarmCenter/problemDetail", "/alarmCenter/rootCauseAnalysis"):
        if path not in routes:
            raise Fail(f"C32：route-data 没有 {path}，静态页声明对不上")


def case_c71() -> None:
    names = [p.name for p in CASES.rglob("*") if p.is_file()]
    hot = [n for n in names if "profiling" in n.lower() or "热方法" in n or "hotmethod" in n.lower()]
    if hot:
        raise Fail(f"C71：已经有热方法/Profiling 专案 {hot}，不能再声明无专案")
    jvm = CASES / "应用性能" / "JVM"
    if not jvm.is_dir():
        raise Fail("C71：JVM 目录都不在，对照声明对不上")


def case_c75() -> None:
    err_dir = CASES / "应用性能" / "错误分析"
    files = list(err_dir.rglob("*")) if err_dir.is_dir() else []
    detail = [p.name for p in files if "errorDetail" in p.name or "错误详情" in p.name]
    if detail:
        raise Fail(f"C75：已经有错误详情专案 {detail}，不能再声明无专案")
    if not (err_dir / "cases.py").is_file():
        raise Fail("C75：错误分析 cases.py 不在，对照声明对不上")


def case_c87() -> None:
    coming_soon_still()


def case_c88() -> None:
    if java_has("/api6972") or java_has("api6972"):
        raise Fail("C88：本仓 Java 出现了 api6972，不能再声明无后端")
    vite = read(REPO / "ai-apm-frontend" / "vite.config.ts")
    if "/api6972" not in vite:
        raise Fail("C88：前端 vite 代理也没有 /api6972，声明对不上")


def case_c94() -> None:
    text = read(WEB_JAVA / "com/databuff/apm/web/portal/EventPortalController.java")
    if "addMonitor" not in text:
        raise Fail("C94：源码没有 addMonitor，声明对不上")
    raise Skip("C94：新建告警规则是写路径，本需求只读打开 search，禁止打 addMonitor 装绿")


def case_c95() -> None:
    text = read(WEB_JAVA / "com/databuff/apm/web/portal/RespPolicyController.java")
    if "/save" not in text:
        raise Fail("C95：源码没有 respPolicy/save，声明对不上")
    raise Skip("C95：新建响应策略是写路径，本需求只读打开，禁止打 respPolicy/save 装绿")


CASES_FN = {
    "AC-C11": case_c11,
    "AC-C14": case_c14,
    "AC-C16": case_c16,
    "AC-C31": case_c31,
    "AC-C32": case_c32,
    "AC-C71": case_c71,
    "AC-C75": case_c75,
    "AC-C87": case_c87,
    "AC-C88": case_c88,
    "AC-C94": case_c94,
    "AC-C95": case_c95,
}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--id", action="append", dest="ids")
    args = parser.parse_args()
    wanted = args.ids or list(CASES_FN)

    failed = 0
    skipped = 0
    passed = 0
    for cid in wanted:
        fn = CASES_FN.get(cid)
        if fn is None:
            print(f"FAIL {cid}: unknown id")
            failed += 1
            continue
        try:
            fn()
            print(f"PASS {cid}")
            passed += 1
        except Skip as error:
            print(f"SKIP {cid}: {error}")
            skipped += 1
        except Fail as error:
            print(f"FAIL {cid}: {error}")
            failed += 1
        except Exception as error:  # noqa: BLE001
            print(f"FAIL {cid}: {error}")
            failed += 1

    print(f"gap_capability_guard: pass={passed} skip={skipped} fail={failed}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
