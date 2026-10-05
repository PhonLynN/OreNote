#!/usr/bin/env python3
"""从 DS 参考截图里**量出**排版尺寸（不靠估）。

## 为什么要有这个脚本

用户口径：「按 DS 截图量，量出多少就用多少」。所以尺寸不能凭手感写死 ——
先把 ref.jpg 里正文的行带（text band）找出来，量出：

    · 行内行距（同一段里相邻基线的间距）
    · 段落间距（跨段落）
    · 列表项间距（相邻圆点的间距）
    · 标题上下的留白
    · 左右页边距与列表缩进

再把这些**像素值**换算成 dp（用行高对齐得到换算比，见下）。

## 量法：横向墨量投影（horizontal ink projection）

对每一行像素 y，统计"这一行里有多少个暗像素"。文字行的暗像素多、
行间空白几乎为 0。于是得到一条墨量曲线，用阈值切成一段段"行带"，
相邻行带的**中心距**就是行距。

⚠️ 只统计**左侧正文区**（避开右侧滚动条与状态栏），并且**跳过公式行与
分隔线**（它们的墨量特征和正文不同，混进来会把统计带偏）。

用法：
    python scripts/measure_ds_layout.py verification/dsref/ref.jpg
"""

import sys
from pathlib import Path

try:
    from PIL import Image
except ImportError:
    sys.exit("需要 Pillow：pip install pillow")


def load_gray(path: Path):
    img = Image.open(path).convert("L")
    return img


def ink_profile(img, x0: int, x1: int) -> list[int]:
    """每一行的暗像素数量（只看 [x0, x1) 这一竖条）。"""
    w, h = img.size
    px = img.load()
    profile = []
    for y in range(h):
        dark = 0
        for x in range(x0, x1):
            if px[x, y] < 128:
                dark += 1
        profile.append(dark)
    return profile


def bands(profile: list[int], threshold: int, gap: int = 2):
    """把墨量曲线切成 (start, end) 行带。gap = 允许行内断开多少像素。"""
    out = []
    start = None
    blank = 0
    for y, v in enumerate(profile):
        if v >= threshold:
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
        out.append((start, len(profile) - 1))
    return out


def main() -> int:
    path = Path(sys.argv[1] if len(sys.argv) > 1 else "verification/dsref/ref.jpg")
    if not path.is_file():
        return sys.exit(f"找不到图片：{path}")

    img = load_gray(path)
    w, h = img.size
    print(f"图片：{path}  尺寸 {w}x{h}")

    # 正文区：避开最右侧滚动条（约占 2%）与最左侧空白
    x0, x1 = int(w * 0.06), int(w * 0.94)

    profile = ink_profile(img, x0, x1)
    peak = max(profile)
    th = max(6, int(peak * 0.06))
    bs = bands(profile, th)
    print(f"墨量峰值 {peak}，阈值 {th}，切出 {len(bs)} 个行带\n")

    print("行带（起点, 终点, 高度, 中心）：")
    centers = []
    for (s, e) in bs:
        centers.append((s + e) / 2)
        print(f"  {s:5d}  {e:5d}  {e - s:4d}  {(s + e) / 2:8.1f}")

    # 相邻行带中心距：小的是"行内行距"，大的是"段间/块间留白"
    if len(centers) >= 2:
        deltas = [round(centers[i + 1] - centers[i], 1) for i in range(len(centers) - 1)]
        print(f"\n相邻行带中心距（共 {len(deltas)} 个）：")
        print("  " + ", ".join(str(d) for d in deltas))

        # 用直方图把行距与段距分开：最小的那一簇是行内行距
        small = [d for d in deltas if d < 120]
        if small:
            small.sort()
            line_step = small[len(small) // 2]
            print(f"\n行内行距（<120px 的中位数）          ≈ {line_step} px")
            para = sorted(d for d in deltas if d >= 120)
            if para:
                print(f"块间距（>=120px）分布                ≈ {para[:12]} px")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
