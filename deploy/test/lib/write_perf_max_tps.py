#!/usr/bin/env python3
"""写入链路最高稳定 TPS 压测 — otelgen 阶梯加压 + Doris 金标准。

目标：在 113 等测试机上找出「无 ingest 背压、Doris 入库达标」的最高稳定 spans/s。

Usage:
  python3 lib/write_perf_max_tps.py find-max     # 阶梯找最高稳定 TPS
  python3 lib/write_perf_max_tps.py hold         # 在 MAX_STABLE_RATE 下长稳态（默认 10m）

Environment (common):
  OTEL_ENDPOINT           default 127.0.0.1:4317
  OTELGEN_IMAGE           default ghcr.io/krzko/otelgen:latest
  OTELGEN_SERVICE         default write-perf-otelgen  (Doris service 过滤)
  OTELGEN_WORKERS         default 4
  OTELGEN_SCENARIOS       default basic
  OTELGEN_TRACES_PER_WORKER  default 3  (-t)
  DORIS_HOST              default 127.0.0.1
  DORIS_PORT              default 9030
  DORIS_USER              default root
  INGEST_CONTAINER        default ai-apm-ingest
  DEMO_DIR                default /opt/databuff-ai-apm-demo
  OUTPUT_DIR              default /tmp/write-perf
  STABILITY_RATIO         default 0.90   achieved >= target * ratio
  FLUSH_WAIT_SECONDS      default 30     otelgen 结束后等待 flush
  WARMUP_SECONDS          default 15     每档开始前空窗

find-max:
  STEP_SECONDS            default 60     每档稳态时长
  RATE_START              default 5      otelgen --rate (traces/s per worker)
  RATE_MULTIPLIER         default 2.0    每档倍率
  RATE_MAX                default 5000   上限（per worker）
  BINARY_REFINE           default 1      不稳定时在上一档与本档间二分

hold:
  HOLD_RATE               来自 find-max 结果或环境变量
  HOLD_SECONDS            default 600 (PERF_WRITE_DURATION=10m)
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
from dataclasses import asdict, dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any


def env_int(name: str, default: int) -> int:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    return int(raw)


def env_float(name: str, default: float) -> float:
    raw = os.environ.get(name)
    if raw is None or raw == "":
        return default
    return float(raw)


def env_str(name: str, default: str) -> str:
    return os.environ.get(name, default)


def parse_duration(raw: str) -> int:
    raw = raw.strip().lower()
    m = re.fullmatch(r"(\d+)(m|h|d)", raw)
    if m:
        n, unit = int(m.group(1)), m.group(2)
        mult = {"m": 60, "h": 3600, "d": 86400}[unit]
        return n * mult
    if raw.isdigit():
        return int(raw)
    raise ValueError(f"invalid duration: {raw}")


def now_str() -> str:
    return datetime.now().strftime("%Y-%m-%d %H:%M:%S")


def run(cmd: list[str], *, check: bool = True, capture: bool = True) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        cmd,
        check=check,
        capture_output=capture,
        text=True,
    )


def mysql_scalar(sql: str) -> int:
    host = env_str("DORIS_HOST", "127.0.0.1")
    port = env_str("DORIS_PORT", "9030")
    user = env_str("DORIS_USER", "root")
    proc = run(
        ["mysql", "-h", host, "-P", port, f"-u{user}", "-N", "-e", sql],
        check=True,
    )
    line = proc.stdout.strip().splitlines()[-1] if proc.stdout.strip() else "0"
    return int(line)


def mysql_count_service(service: str) -> int:
    svc_filter = f" WHERE service = '{service}'" if service else ""
    return mysql_scalar(f"SELECT COUNT(*) FROM databuff.trace_dc_span{svc_filter};")


def mysql_count_window(service: str, seconds: int) -> int:
    """Recent window via Doris NOW() — avoids Python/Doris clock skew."""
    svc_filter = f" AND service = '{service}'" if service else ""
    return mysql_scalar(
        "SELECT COUNT(*) FROM databuff.trace_dc_span "
        f"WHERE startTime >= DATE_SUB(NOW(), INTERVAL {seconds} SECOND){svc_filter};"
    )


def ingest_flush_errors(container: str) -> int:
    proc = run(
        ["docker", "logs", container],
        check=False,
    )
    text = (proc.stdout or "") + (proc.stderr or "")
    return len(re.findall(r"flush timed out|flush failed", text))


def stop_demo() -> None:
    demo_dir = env_str("DEMO_DIR", "/opt/databuff-ai-apm-demo")
    stop_sh = Path(demo_dir) / "stop.sh"
    if stop_sh.is_file():
        run(["bash", str(stop_sh)], check=False)


def run_otelgen(rate: int, duration: int) -> tuple[int, str]:
    image = env_str("OTELGEN_IMAGE", "ghcr.io/krzko/otelgen:latest")
    endpoint = env_str("OTEL_ENDPOINT", "127.0.0.1:4317")
    service = env_str("OTELGEN_SERVICE", "write-perf-otelgen")
    workers = env_int("OTELGEN_WORKERS", 4)
    traces = env_int("OTELGEN_TRACES_PER_WORKER", 3)
    scenarios = env_str("OTELGEN_SCENARIOS", "basic")
    cmd = [
        "docker",
        "run",
        "--rm",
        "--network",
        "host",
        image,
        "--otel-exporter-otlp-endpoint",
        endpoint,
        "--protocol",
        "grpc",
        "--insecure",
        "--duration",
        str(duration),
        "--rate",
        str(rate),
        "--service-name",
        service,
        "traces",
        "multi",
        "-w",
        str(workers),
        "-t",
        str(traces),
        "-s",
        scenarios,
    ]
    proc = run(cmd, check=False)
    out = (proc.stdout or "") + (proc.stderr or "")
    return proc.returncode, out


@dataclass
class StepResult:
    rate: int
    workers: int
    step_seconds: int
    target_trace_tps: float
    target_span_tps: float
    achieved_span_tps: float
    spans_inserted: int
    spans_per_trace: float
    flush_errors_delta: int
    otelgen_exit: int
    stable: bool
    reason: str
    started_at: str
    ended_at: str


@dataclass
class RunReport:
    mode: str
    otelgen_service: str
    workers: int
    spans_per_trace: float
    stability_ratio: float
    steps: list[StepResult] = field(default_factory=list)
    max_stable_rate: int | None = None
    max_stable_trace_tps: float | None = None
    max_stable_span_tps: float | None = None
    hold_seconds: int | None = None
    finished_at: str = ""


def measure_step(rate: int, step_seconds: int, spans_per_trace: float) -> StepResult:
    workers = env_int("OTELGEN_WORKERS", 4)
    service = env_str("OTELGEN_SERVICE", "write-perf-otelgen")
    container = env_str("INGEST_CONTAINER", "ai-apm-ingest")
    flush_wait = env_int("FLUSH_WAIT_SECONDS", 30)
    warmup = env_int("WARMUP_SECONDS", 15)
    ratio = env_float("STABILITY_RATIO", 0.90)

    target_trace_tps = rate * workers
    target_span_tps = target_trace_tps * spans_per_trace

    if warmup > 0:
        time.sleep(warmup)

    flush_before = ingest_flush_errors(container)
    t0 = datetime.now()
    count_before = mysql_count_service(service)
    otelgen_exit, _otel_out = run_otelgen(rate, step_seconds)
    time.sleep(flush_wait)
    t1 = datetime.now()
    count_after = mysql_count_service(service)
    flush_after = ingest_flush_errors(container)
    flush_delta = max(0, flush_after - flush_before)

    elapsed = max(step_seconds, 1.0)
    spans = max(0, count_after - count_before)
    achieved = spans / elapsed

    stable = True
    reasons: list[str] = []
    if flush_delta > 0:
        stable = False
        reasons.append(f"ingest flush errors +{flush_delta}")
    if otelgen_exit != 0:
        stable = False
        reasons.append(f"otelgen exit {otelgen_exit}")
    if achieved < target_span_tps * ratio:
        stable = False
        reasons.append(
            f"achieved {achieved:.1f} span/s < target {target_span_tps:.1f} * {ratio}"
        )
    if not reasons:
        reasons.append("ok")

    return StepResult(
        rate=rate,
        workers=workers,
        step_seconds=step_seconds,
        target_trace_tps=target_trace_tps,
        target_span_tps=target_span_tps,
        achieved_span_tps=achieved,
        spans_inserted=spans,
        spans_per_trace=spans_per_trace,
        flush_errors_delta=flush_delta,
        otelgen_exit=otelgen_exit,
        stable=stable,
        reason="; ".join(reasons),
        started_at=t0.strftime("%Y-%m-%d %H:%M:%S"),
        ended_at=t1.strftime("%Y-%m-%d %H:%M:%S"),
    )


def calibrate_spans_per_trace() -> float:
    """低档短跑，估算 otelgen 每 trace 平均入库 span 数。"""
    rate = env_int("CALIBRATE_RATE", 5)
    seconds = env_int("CALIBRATE_SECONDS", 30)
    workers = env_int("OTELGEN_WORKERS", 4)
    step = measure_step(rate, seconds, spans_per_trace=1.0)
    if step.spans_inserted <= 0:
        return float(env_int("SPANS_PER_TRACE_FALLBACK", 4))
    est_traces = rate * workers * seconds * 0.95
    if est_traces <= 0:
        return float(env_int("SPANS_PER_TRACE_FALLBACK", 4))
    return max(1.0, step.spans_inserted / est_traces)


def cmd_find_max(args: argparse.Namespace) -> RunReport:
    step_seconds = env_int("STEP_SECONDS", 60)
    rate_start = env_int("RATE_START", 5)
    rate_max = env_int("RATE_MAX", 5000)
    multiplier = env_float("RATE_MULTIPLIER", 2.0)
    binary_refine = env_int("BINARY_REFINE", 1) == 1
    service = env_str("OTELGEN_SERVICE", "write-perf-otelgen")
    workers = env_int("OTELGEN_WORKERS", 4)
    ratio = env_float("STABILITY_RATIO", 0.90)

    stop_demo()
    spans_per_trace = calibrate_spans_per_trace()

    report = RunReport(
        mode="find-max",
        otelgen_service=service,
        workers=workers,
        spans_per_trace=spans_per_trace,
        stability_ratio=ratio,
    )

    rate = rate_start
    last_stable: int | None = None
    first_unstable: int | None = None

    # 首档不稳定时先降档找起点，避免 RATE_START 过高直接退出
    probe = measure_step(rate, step_seconds, spans_per_trace)
    report.steps.append(probe)
    print(
        f"[{now_str()}] rate={rate} workers={workers} "
        f"target={probe.target_span_tps:.0f} span/s achieved={probe.achieved_span_tps:.0f} "
        f"stable={probe.stable} ({probe.reason})",
        flush=True,
    )
    while not probe.stable and rate > 1:
        rate = max(1, rate // 2)
        probe = measure_step(rate, step_seconds, spans_per_trace)
        report.steps.append(probe)
        print(
            f"[{now_str()}] rate={rate} workers={workers} "
            f"target={probe.target_span_tps:.0f} span/s achieved={probe.achieved_span_tps:.0f} "
            f"stable={probe.stable} ({probe.reason})",
            flush=True,
        )
    if not probe.stable:
        report.finished_at = now_str()
        return report

    last_stable = rate
    nxt = int(rate * multiplier)
    if nxt <= rate:
        nxt = rate + max(1, rate // 2)
    rate = nxt

    while rate <= rate_max:
        step = measure_step(rate, step_seconds, spans_per_trace)
        report.steps.append(step)
        print(
            f"[{now_str()}] rate={rate} workers={workers} "
            f"target={step.target_span_tps:.0f} span/s achieved={step.achieved_span_tps:.0f} "
            f"stable={step.stable} ({step.reason})",
            flush=True,
        )
        if step.stable:
            last_stable = rate
        else:
            first_unstable = rate
            break
        nxt = int(rate * multiplier)
        if nxt <= rate:
            nxt = rate + max(1, rate // 2)
        rate = nxt

    if binary_refine and last_stable is not None and first_unstable is not None:
        lo, hi = last_stable, first_unstable
        while hi - lo > max(1, lo // 10):
            mid = (lo + hi) // 2
            if mid <= lo:
                break
            step = measure_step(mid, step_seconds, spans_per_trace)
            report.steps.append(step)
            print(
                f"[{now_str()}] refine rate={mid} stable={step.stable} ({step.reason})",
                flush=True,
            )
            if step.stable:
                lo = mid
            else:
                hi = mid
        last_stable = lo

    if last_stable is not None:
        report.max_stable_rate = last_stable
        report.max_stable_trace_tps = last_stable * workers
        report.max_stable_span_tps = report.max_stable_trace_tps * spans_per_trace

    report.finished_at = now_str()
    return report


def cmd_hold(args: argparse.Namespace) -> RunReport:
    workers = env_int("OTELGEN_WORKERS", 4)
    service = env_str("OTELGEN_SERVICE", "write-perf-otelgen")
    ratio = env_float("STABILITY_RATIO", 0.90)
    hold_raw = os.environ.get("PERF_WRITE_DURATION") or os.environ.get("HOLD_SECONDS") or "10m"
    hold_seconds = parse_duration(hold_raw) if isinstance(hold_raw, str) and not hold_raw.isdigit() else int(hold_raw)

    rate = env_int("HOLD_RATE", 0)
    if rate <= 0:
        result_path = Path(env_str("OUTPUT_DIR", "/tmp/write-perf")) / "max-tps-result.json"
        if result_path.is_file():
            data = json.loads(result_path.read_text())
            rate = int(data.get("max_stable_rate") or 0)
        if rate <= 0:
            raise SystemExit("HOLD_RATE unset and no max-tps-result.json — run find-max first")

    stop_demo()
    spans_per_trace = env_float("SPANS_PER_TRACE", 0.0)
    if spans_per_trace <= 0:
        spans_per_trace = calibrate_spans_per_trace()

    report = RunReport(
        mode="hold",
        otelgen_service=service,
        workers=workers,
        spans_per_trace=spans_per_trace,
        stability_ratio=ratio,
        hold_seconds=hold_seconds,
        max_stable_rate=rate,
        max_stable_trace_tps=rate * workers,
        max_stable_span_tps=rate * workers * spans_per_trace,
    )

    step = measure_step(rate, hold_seconds, spans_per_trace)
    report.steps.append(step)
    if step.stable:
        report.max_stable_rate = rate
        report.max_stable_trace_tps = rate * workers
        report.max_stable_span_tps = step.achieved_span_tps

    report.finished_at = now_str()
    return report


def write_outputs(report: RunReport) -> None:
    out_dir = Path(env_str("OUTPUT_DIR", "/tmp/write-perf"))
    out_dir.mkdir(parents=True, exist_ok=True)

    json_path = out_dir / "max-tps-result.json"
    json_path.write_text(json.dumps(asdict(report), indent=2, ensure_ascii=False) + "\n")

    md_lines = [
        "# Write Perf — Max Stable TPS",
        "",
        f"- finished: {report.finished_at}",
        f"- mode: {report.mode}",
        f"- service filter: `{report.otelgen_service}`",
        f"- workers: {report.workers}",
        f"- spans/trace (calibrated): {report.spans_per_trace:.2f}",
        f"- stability ratio: {report.stability_ratio}",
        "",
    ]
    if report.max_stable_rate is not None:
        md_lines.extend(
            [
                "## Result",
                "",
                f"| 指标 | 值 |",
                f"|------|-----|",
                f"| max stable otelgen `--rate` | {report.max_stable_rate} |",
                f"| max stable trace TPS | {report.max_stable_trace_tps:.0f} |",
                f"| max stable span TPS (Doris) | {report.max_stable_span_tps:.0f} |",
                "",
            ]
        )
    if report.hold_seconds:
        md_lines.append(f"- hold duration: {report.hold_seconds}s")
        md_lines.append("")

    md_lines.extend(["## Steps", "", "| rate | target span/s | achieved span/s | stable | note |", "|------|---------------|-----------------|--------|------|"])
    for s in report.steps:
        md_lines.append(
            f"| {s.rate} | {s.target_span_tps:.0f} | {s.achieved_span_tps:.0f} | {s.stable} | {s.reason} |"
        )
    md_lines.append("")

    md_path = out_dir / "max-tps-result.md"
    md_path.write_text("\n".join(md_lines) + "\n")

    log_path = out_dir / "run.log"
    with log_path.open("a", encoding="utf-8") as f:
        f.write(f"\n=== {report.mode} @ {report.finished_at} ===\n")
        f.write(json_path.read_text())


def main() -> None:
    parser = argparse.ArgumentParser(description="Find max stable write TPS via otelgen + Doris")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("find-max", help="Ramp rate until unstable; optional binary refine")
    sub.add_parser("hold", help="Hold at max stable rate for PERF_WRITE_DURATION")
    args = parser.parse_args()

    if args.cmd == "find-max":
        report = cmd_find_max(args)
    else:
        report = cmd_hold(args)

    write_outputs(report)

    if report.max_stable_span_tps is not None:
        print(
            f"\nMAX STABLE: rate={report.max_stable_rate} "
            f"trace_tps={report.max_stable_trace_tps:.0f} "
            f"span_tps={report.max_stable_span_tps:.0f}",
            flush=True,
        )
    else:
        print("\nNo stable rate found — check ingest / Doris.", file=sys.stderr)
        sys.exit(1)


if __name__ == "__main__":
    main()
