#!/usr/bin/env python3
"""Generate README feature pillar banners as PNG (CJK-safe on GitHub)."""

from __future__ import annotations

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
FONT = "/System/Library/Fonts/PingFang.ttc"
W, H = 880, 176
PANEL_W, PANEL_H = 400, 176
GAP = 80
LEFT_X = 0
RIGHT_X = W - PANEL_W
SUB_MAX_W = PANEL_W - 32


def load_font(size: int, index: int = 0) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT, size=size, index=index)


def wrap_text(text: str, font: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    lines: list[str] = []
    for segment in text.split(" · "):
        candidate = segment if not lines else f"{lines[-1]} · {segment}"
        if font.getlength(candidate) <= max_width:
            if lines:
                lines[-1] = candidate
            else:
                lines.append(segment)
            continue
        if lines:
            lines.append(segment)
        else:
            words = segment.split()
            current = ""
            for word in words:
                trial = word if not current else f"{current} {word}"
                if font.getlength(trial) <= max_width:
                    current = trial
                else:
                    if current:
                        lines.append(current)
                    current = word
            if current:
                lines.append(current)
    return lines or [text]


def draw_gradient_bar(draw: ImageDraw.ImageDraw, x: int, y: int, w: int, h: int, c1: tuple, c2: tuple) -> None:
    for i in range(w):
        t = i / max(w - 1, 1)
        r = int(c1[0] + (c2[0] - c1[0]) * t)
        g = int(c1[1] + (c2[1] - c1[1]) * t)
        b = int(c1[2] + (c2[2] - c1[2]) * t)
        draw.line([(x + i, y), (x + i, y + h)], fill=(r, g, b))


def draw_panel(
    draw: ImageDraw.ImageDraw,
    x: int,
    title: str,
    items: list[str],
    subtitle: str,
    accent: tuple[tuple, tuple],
    dot_color: str,
) -> None:
    draw.rounded_rectangle((x, 0, x + PANEL_W, PANEL_H), radius=12, fill="#FFFFFF", outline="#E2E8F0", width=1)
    draw_gradient_bar(draw, x + 1, 1, PANEL_W - 2, 4, accent[0], accent[1])

    title_font = load_font(16, index=1)
    item_font = load_font(14)
    sub_font = load_font(12)

    cx = x + PANEL_W // 2
    draw.text((cx, 22), title, fill="#0F172A", font=title_font, anchor="ma")

    cols = [(x + 100, 72), (x + 264, 72), (x + 100, 100), (x + 264, 100)]
    for (ix, iy), label in zip(cols, items):
        draw.ellipse((ix - 18, iy - 4, ix - 11, iy + 3), fill=dot_color)
        draw.text((ix - 6, iy - 8), label, fill="#334155", font=item_font)

    sub_lines = wrap_text(subtitle, sub_font, SUB_MAX_W)
    sub_y = 122 if len(sub_lines) == 1 else 116
    line_gap = 16
    for i, line in enumerate(sub_lines):
        draw.text((cx, sub_y + i * line_gap), line, fill="#64748B", font=sub_font, anchor="ma")


def draw_arrow(draw: ImageDraw.ImageDraw) -> None:
    mid = W // 2
    cy = PANEL_H // 2
    draw.line([(mid - 28, cy), (mid + 8, cy)], fill="#CBD5E1", width=2)
    draw.polygon([(mid + 8, cy), (mid, cy - 5), (mid, cy + 5)], fill="#94A3B8")
    draw.line([(mid + 12, cy), (mid + 28, cy)], fill="#CBD5E1", width=2)


def render(path: Path, title_left: str, title_right: str, left_items: list[str], right_items: list[str], sub_left: str, sub_right: str) -> None:
    img = Image.new("RGB", (W, H), "#FFFFFF")
    draw = ImageDraw.Draw(img)

    draw_panel(
        draw,
        LEFT_X,
        title_left,
        left_items,
        sub_left,
        ((59, 130, 246), (6, 182, 212)),
        "#3B82F6",
    )
    draw_arrow(draw)
    draw_panel(
        draw,
        RIGHT_X,
        title_right,
        right_items,
        sub_right,
        ((139, 92, 246), (217, 70, 239)),
        "#8B5CF6",
    )

    img.save(path, format="PNG", optimize=True)
    print(f"wrote {path}")


def main() -> None:
    render(
        ROOT / "feature-pillars.png",
        "OpenTelemetry APM",
        "AI 原生",
        ["链路追踪", "服务指标", "服务拓扑", "告警"],
        ["智能问数", "服务巡检", "故障分析", "MCP"],
        "标准 OTLP 后端 · Trace 组装 · 指标派生 · AI 数据底座",
        "长在 APM 数据上 · Brain 调度专家 · 查证据给结论",
    )
    render(
        ROOT / "feature-pillars-en.png",
        "OpenTelemetry APM",
        "AI Native",
        ["Tracing", "Metrics", "Topology", "Alerts"],
        ["NL Query", "Inspection", "RCA", "MCP"],
        "Standard OTLP backend · Trace assembly · Metric derivation · AI data foundation",
        "Built on APM data · Brain orchestrates experts · Evidence-based conclusions",
    )


if __name__ == "__main__":
    main()
