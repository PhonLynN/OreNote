#!/usr/bin/env python3
"""对比同一段原文在 DS App 与 OreNote 上的渲染差异（逐项测量）。

## 素材

· `ds_ns_3.jpg`      DS App 截图（1011x4148）
· `mine_ns_3.jpg`    OreNote 截图（1011x4144）

**两张图尺寸几乎相同（宽都是 1011）**，所以可以直接比 px —— 这是最理想的情况：
不需要再找"换算比"。

## 量什么

1. **页边距**：正文左右边界
2. **行距**：同一段里相邻行带的中心距
3. **块间距**：段落 / 标题 / 公式之间的留白
4. **字号**：汉字墨迹宽度
5. **公式的垂直位置与高度**
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
    first = last = None
    for y in range(y0, y1 + 1):
        for x in range(w):
            if px[x, y] < 128:
                if first is None or x < first:
                    first = x
                if last is None or x > last:
                    last = x
    return first, last


def main() -> int:
    for p in (DS, MINE):
        if not p.is_file():
            return sys.exit(f"缺少素材：{p}")

    result = {}
    for label, path in (("DS", DS), ("OreNote", MINE)):
        img = Image.open(path).convert("L")
        w, h = img.size
        bs = bands(img)
        centers = [(a + b) / 2 for a, b in bs]
        deltas = [round(centers[i + 1] - centers[i], 1) for i in range(len(centers) - 1)]
        small = sorted(d for d in deltas if 10 < d < 120)
        med = small[len(small) // 2] if small else 0
        result[label] = dict(img=img, w=w, h=h, bands=bs, deltas=deltas, line=med)
        print(f"=== {label}  {w}x{h}  行带 {len(bs)} 个 ===")
        print(f"  行内行距中位 ≈ {med:.1f} px")
        big = sorted(d for d in deltas if d >= 120)
        print(f"  块间距(>=120px) = {big[:10]}")
        print()

    print("=== 正文左右边界 ===")
    for label in ("DS", "OreNote"):
        r = result[label]
        img, bs = r["img"], r["bands"]
        spans = []
        for a, b in bs:
            if 20 <= b - a <= 60:            # 单行文字的高度范围
                f, l = ink_span(img, a, b)
                if f is not None:
                    spans.append((f, l))
        if spans:
            spans.sort(key=lambda s: s[0])
            lefts = sorted(s[0] for s in spans)
            rights = sorted(s[1] for s in spans)
            print(f"  {label:8s} 左边距中位={lefts[len(lefts)//2]:4d}  "
                  f"右边距中位={r['w']-1-rights[len(rights)//2]:4d}")

    print()
    print("=== 行距对比（px，两图同宽可直接比）===")
    d, m = result["DS"]["line"], result["OreNote"]["line"]
    print(f"  DS      : {d:.1f}")
    print(f"  OreNote : {m:.1f}")
    if d:
        print(f"  比值    : {m/d:.3f}   （1.000 = 完全一致）")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
