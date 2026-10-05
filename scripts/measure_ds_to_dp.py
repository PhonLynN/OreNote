#!/usr/bin/env python3
"""把 DS 截图量出的**像素**换算成我们该用的 **dp**。

## 换算比从哪来

不能拍脑袋取 0.366（那是"假设两边字号一样"下的错算法）。正确做法是找一个
**两边都确定存在的共同基准**：正文字号本身。

    DS  ref.jpg      ：iPhone 截图，宽度 968px（逻辑宽 440pt @2.2x）
    我们 mine_1080   ：Android 1080px 宽 @2.75x（xxhdpi）

所以：
    1. 先量出 DS 那边**一个汉字占多少 px**（用一段已知字数的中文行）
    2. 我们的正文字号是 AiTypo.body.fontSize（sp），一个汉字 = 1em = fontSize(dp)
    3. 换算比 k = DS_汉字px / 我们的汉字dp

之后：我们的 dp = DS 的 px / k

## ⚠️ 为什么必须这样算

"行高对齐"这种说法听起来合理，但如果两边的**字号**本来就不同，
对齐行高等于默认接受字号差异 —— 而用户要的是"跟 DS 一样"。
所以基准取**字号**，行距/间距全部由它推导出来。

用法：
    python scripts/measure_ds_layout.py verification/dsref/ref.jpg   # 先量像素
    python scripts/measure_ds_to_dp.py 70 105 313 --font-sp 16      # 再换算
"""

import sys

# DS 参考图：iPhone 15/16 Pro 的截图，968px 宽 = 440pt 逻辑宽 ⇒ 2.2 px/pt。
# 一个字号的 pt 值在中文排版里就是"一个汉字宽"，所以可直接量。
DS_WIDTH_PX = 968
DS_WIDTH_PT = 440.0
DS_PX_PER_PT = DS_WIDTH_PX / DS_WIDTH_PT  # ≈ 2.2

# Android 侧：1080px 宽 @ density 2.75 ⇒ 逻辑宽 392.7dp。
# 字号用 sp，与 dp 的关系是 fontScale=1 时 1:1。
ANDROID_WIDTH_PX = 1080
ANDROID_DENSITY = 2.75
ANDROID_WIDTH_DP = ANDROID_WIDTH_PX / ANDROID_DENSITY  # ≈ 392.7


def k_ratio(ds_han_px: float, our_font_sp: float) -> float:
    """换算比：DS 的 1 个汉字 px ↔ 我们的 1 个汉字 dp(=sp)。

    ⚠️ 这是**唯一**正确的比法 —— 先让"字"一样大，再看其它间距差多少。
    """
    return ds_han_px / our_font_sp


def main() -> int:
    if len(sys.argv) < 2:
        return sys.exit(__doc__)

    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    font_sp = 16.0
    han_px = None
    for a in sys.argv[1:]:
        if a.startswith("--font-sp"):
            font_sp = float(sys.argv[sys.argv.index(a) + 1])
        if a.startswith("--han-px"):
            han_px = float(sys.argv[sys.argv.index(a) + 1])

    print(f"DS 截图：{DS_WIDTH_PX}px / {DS_WIDTH_PT}pt  ⇒ {DS_PX_PER_PT:.3f} px/pt")
    print(f"我们：   {ANDROID_WIDTH_PX}px / {ANDROID_DENSITY}  ⇒ {ANDROID_WIDTH_DP:.1f} dp 宽")
    print()

    if han_px:
        k = k_ratio(han_px, font_sp)
        print(f"换算比 k = DS 汉字 {han_px}px ÷ 我们字号 {font_sp}sp = {k:.4f}")
        print(f"（意思是：DS 上的 1px 相当于我们的 {1 / k:.4f} dp）")
        print()

    print("换算表（DS px → 我们的 dp）：")
    for a in args:
        try:
            px = float(a)
        except ValueError:
            continue
        for k in ([k_ratio(han_px, font_sp)] if han_px else [DS_PX_PER_PT / 2.0, 2.0, 2.2]):
            print(f"  {px:8.1f} px  ÷ k={k:.3f}  =  {px / k:7.2f} dp")
        print()

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
