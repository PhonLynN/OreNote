#!/usr/bin/env python3
"""量「大标题」前后的留白（用户 2026-10-04 指出的差异）。

## 用户原话

> 「ds在由大标题分割的块之间给了**非常大**的间隙，
>   而 orenote 不同大标题分割的空隙高度甚至**低于大标题到下方第一行**的高度」

## 量什么

对每张大标题（字号明显大于正文的那几行），分别量：

    · 标题**上方**的空白（上一个块底 → 标题顶）
    · 标题**下方**的空白（标题底 → 下一块顶）

两张截图同宽（1011px），所以可以直接比 px。

用法：python scripts/measure_heading_gap.py
"""

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow")

DS = Path("verification/dsref/ds_ns_3.jpg")
MINE = Path("verification/dsref/mine_ns_3.jpg")


def bands(img, xlo=0.05, xhi=0.95, thr_ratio=0.05, gap=2):
    px = img.load()
    w, h = img.size
    x0, x1 = int(w * xlo), int(w * xhi)
    prof = [sum(1 for x in range(x0, x1) if px[x, y] < 128) for y in range(h)]
    peak = max(prof) or 1
    th = max(5, int(peak * thr_ratio))
    out, start, blank = [], None, 0
    for y, v in enumerate(prof):
        if v >= th:
            if start is None:
                start = y
            blank = 0
        else:
            if start is not None:
                blank += 1
                if blank > gap:
                    out.append((start, y - blank))
                    start = None
    if start is not None:
        out.append((start, h - 1))
    return out


def ink_span(img, y0, y1):
    px = img.load()
    w, _ = img.size
    f = l = None
    for y in range(y0, y1 + 1):
        for x in range(w):
            if px[x, y] < 128:
                if f is None or x < f:
                    f = x
                if l is None or x > l:
                    l = x
    return f, l


def analyse(path, label):
    img = Image.open(path).convert("L")
    w, h = img.size
    bs = bands(img)
    print(f"=== {label}  {w}x{h}  行带 {len(bs)} ===")

    # 标题 = 墨迹更高的行带（字号大）。取高度明显大于中位数的那些。
    heights = sorted(b - a for a, b in bs)
    med_h = heights[len(heights) // 2]
    threshold = med_h * 1.35

    print(f"  行带高度中位数 {med_h}px，标题判据 > {threshold:.0f}px")
    print()
    print(f"  {'标题行带':<16}{'上方空白':<12}{'下方空白':<12}{'比例 上/下':<12}")
    rows = []
    for i, (a, b) in enumerate(bs):
        if (b - a) < threshold:
            continue
        above = a - bs[i - 1][1] - 1 if i > 0 else None
        below = bs[i + 1][0] - b - 1 if i + 1 < len(bs) else None
        if above is None or below is None:
            continue
        rows.append((a, b, above, below))
        ratio = above / below if below else 0
        print(f"  y={a:5d}..{b:<6d}{above:<12}{below:<12}{ratio:<12.2f}")
    print()
    return rows


def main() -> int:
    for p in (DS, MINE):
        if not p.is_file():
            return sys.exit(f"缺少素材：{p}")

    ds = analyse(DS, "DS")
    mine = analyse(MINE, "OreNote")

    if ds and mine:
        da = sum(r[2] for r in ds) / len(ds)
        db = sum(r[3] for r in ds) / len(ds)
        ma = sum(r[2] for r in mine) / len(mine)
        mb = sum(r[3] for r in mine) / len(mine)
        print("=== 平均（px）===")
        print(f"  {'':10}{'标题上方':<12}{'标题下方':<12}{'上/下':<10}")
        print(f"  {'DS':<10}{da:<12.1f}{db:<12.1f}{da/db:<10.2f}")
        print(f"  {'OreNote':<10}{ma:<12.1f}{mb:<12.1f}{ma/mb:<10.2f}")
        print()
        print(f"  DS 的'上/下' = {da/db:.2f}  （>1 表示标题上方留白更大）")
        print(f"  我们 的'上/下' = {ma/mb:.2f}")
        print()
        if ma / mb < 1:
            print("  ⚠️ 我们的标题上方留白【小于】下方 —— 正是用户描述的『甚至低于』")
        else:
            print("  我们的标题上方留白大于下方 ✓")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
