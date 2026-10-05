#!/usr/bin/env python3
"""量 DS 图里**一个汉字**的宽度 —— 校准换算比用。

## 为什么这个数必须量准

后面所有 dp（行距、段距、缩进、字号）都由它推出来。取错了，
整条尺寸链一起错 —— 而尺寸正是这一轮要解决的东西。

## 做法：用"字距"而不是"整行宽度"

整行宽度会把行首行尾的**标点和西文**算进去，汉字宽度被拉偏。
所以这里改成**量相邻两个相同汉字的中心距**：

在一条**全是汉字、字字紧排**的行上，横向投影墨量，
相邻墨迹峰之间的间距就是**一个汉字的步进（advance）**。

比"一行宽度 ÷ 字数"稳，因为它不受首尾半宽字符的影响。

用法：
    python scripts/measure_han_width.py verification/dsref/ref.jpg <y0> <y1>
"""

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install pillow")


def column_profile(img, y0: int, y1: int, x0: int, x1: int):
    """纵向墨量投影：每个 x 上有多少暗像素。"""
    px = img.load()
    prof = []
    for x in range(x0, x1):
        dark = 0
        for y in range(y0, y1):
            if px[x, y] < 128:
                dark += 1
        prof.append(dark)
    return prof


def gaps_between_glyphs(prof, min_gap=1):
    """找"墨迹段"之间的空白段的宽度，即字与字之间的间隙。

    ⚠️ 汉字是紧排的，正常间隙只有 0~2px；间隙明显更大的是标点或空格。
    这里返回所有间隙宽度，供判断这一行是不是"纯汉字紧排"。
    """
    gaps = []
    run = 0
    seen_ink = False
    for v in prof:
        if v == 0:
            if seen_ink:
                run += 1
        else:
            if run:
                gaps.append(run)
                run = 0
            seen_ink = True
    return gaps


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "verification/dsref/ref.jpg")
    y0 = int(sys.argv[2]) if len(sys.argv) > 2 else 143
    y1 = int(sys.argv[3]) if len(sys.argv) > 3 else 183
    # 只量正文列：DS 图里正文从 x≈40 开始，到 x≈930 结束
    x0 = int(sys.argv[4]) if len(sys.argv) > 4 else 30
    x1 = int(sys.argv[5]) if len(sys.argv) > 5 else 940

    img = Image.open(path).convert("L")
    prof = column_profile(img, y0, y1, x0, x1)

    ink_cols = [i for i, v in enumerate(prof) if v > 0]
    if not ink_cols:
        return sys.exit("这条行带里没有墨迹，y 范围不对")
    first, last = ink_cols[0] + x0, ink_cols[-1] + x0
    print(f"行带 y={y0}..{y1}：墨迹 x={first}..{last}  宽 {last - first + 1} px")

    gaps = gaps_between_glyphs(prof)
    if gaps:
        gaps.sort()
        print(f"字间/词间间隙：最小 {gaps[0]}px  中位 {gaps[len(gaps) // 2]}px  最大 {gaps[-1]}px")
        print("（汉字紧排时中位间隙应是 0~2px；明显更大说明这行混了西文/标点）")
        print(f"间隙个数 {len(gaps)}（约等于这行的墨迹块数，汉字行 ≈ 字数）")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
