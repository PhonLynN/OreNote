# ⚠️ 已弃用（2026-09-16，V2）：应用图标改为「深绿底 + 白色对切菱形」，
# 几何直接维护在 res/drawable/ic_launcher_foreground.xml / ic_launcher_monochrome.xml，
# 背景为 res/drawable/ic_launcher_background.xml。此脚本生成的是旧版「五个方块」图标，
# 仅作历史参考，不要运行（会覆盖现有资源）。
#!/usr/bin/env python3
"""生成 OrePlan 的自适应图标资源，并渲染预览图。

图形：**五个有棱角的方块**（直角、无圆角），3 个在上、2 个在下且左对齐，等大、同色。
画布保持干净：单一底色的背景层，不加渐变、光效、投影或任何高亮。

几何与配色只在这个文件里定义一份，由它产出：

    app/src/main/res/drawable/ic_launcher_background.xml
    app/src/main/res/drawable/ic_launcher_foreground.xml
    app/src/main/res/drawable/ic_launcher_monochrome.xml
    app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml

并渲染 `dist/icon-preview.png`（方形 / 圆角方形 / 圆形遮罩 × 192 / 96 / 48 px），
用来在真机之外核对图形与留白。

用法：
    python scripts/make_icon.py            # 写资源 + 渲染预览
    python scripts/make_icon.py --preview  # 只渲染预览（不覆盖 res/）
"""

from __future__ import annotations

import argparse
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
DIST = ROOT / "dist"

# ---------------------------------------------------------------------------
# 几何参数（自适应图标画布 108×108dp）
# ---------------------------------------------------------------------------

CANVAS = 108.0
# 自适应图标的可见区是中央 72×72，保证不被任何遮罩裁掉的安全区是直径 66 的圆。
SAFE_RADIUS = 33.0

CELL = 15.0     # 单个方块的边长
GAP = 4.4       # 间距。gap/cell ≈ 0.29，与用户给的参考图一致
COLS, ROWS = 3, 2

PITCH = CELL + GAP
GRID_W = COLS * CELL + (COLS - 1) * GAP   # 53.8
GRID_H = ROWS * CELL + (ROWS - 1) * GAP   # 34.4

# 整块在画布正中央。
X0 = (CANVAS - GRID_W) / 2
Y0 = (CANVAS - GRID_H) / 2

# ---------------------------------------------------------------------------
# 配色：干净的蓝底 + 白色方块，只有这两个颜色
# ---------------------------------------------------------------------------

BG_COLOR = "#FF2F6BEA"   # 品牌蓝（浅色板的 accent）
CELL_COLOR = "#FFFFFFFF"  # 方块：白


# ---------------------------------------------------------------------------

def cell_origin(col: int, row: int) -> tuple[float, float]:
    return X0 + col * PITCH, Y0 + row * PITCH


def emitted_cells() -> list[tuple[int, int]]:
    """3 上 2 下、左对齐：下排只占前两列。"""
    return [(c, 0) for c in range(COLS)] + [(c, 1) for c in range(COLS - 1)]


def square_path(x: float, y: float, size: float) -> str:
    """直角方块的 pathData（不给圆角，也就不需要弧线指令）。"""
    return f"M{x:g},{y:g}h{size:g}v{size:g}h-{size:g}z"


def worst_corner_radius() -> float:
    """图形最外侧角点到画布中心的距离。必须 ≤ SAFE_RADIUS，否则会被遮罩切到。"""
    corners = []
    for col, row in emitted_cells():
        x, y = cell_origin(col, row)
        # 四个角都要算：下排只占两列，因此最外侧角点未必在右下。
        corners += [(x, y), (x + CELL, y), (x, y + CELL), (x + CELL, y + CELL)]
    return max(math.hypot(cx - CANVAS / 2, cy - CANVAS / 2) for cx, cy in corners)


HEADER = """<?xml version="1.0" encoding="utf-8"?>
<!--
  {title}

  {note}

  本文件由 `scripts/make_icon.py` 生成，不要手改：几何与配色都在那个脚本里。
-->
"""


def background_xml() -> str:
    head = HEADER.format(
        title="自适应图标的背景层：单一品牌蓝，不加渐变或光效。",
        note="画布保持干净是刻意的：图标的主体是前景那五个方块，背景只负责给对比。",
    )
    return f"""{head}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

    <path
        android:fillColor="{BG_COLOR}"
        android:pathData="M0,0h108v108h-108z" />
</vector>
"""


def foreground_xml() -> str:
    head = HEADER.format(
        title=f"自适应图标的前景层：五个直角方块，{COLS} 上 {ROWS} 下、左对齐、等大同色。",
        note=(
            f"内容全部落在直径 {SAFE_RADIUS * 2:g}dp 的安全圆内（最外侧角点距中心 "
            f"{worst_corner_radius():.1f}dp），任何遮罩都不会切到图形。"
        ),
    )
    cells = "".join(
        f'    <path\n        android:fillColor="{CELL_COLOR}"\n'
        f'        android:pathData="{square_path(x, y, CELL)}" />\n'
        for col, row in emitted_cells()
        for x, y in [cell_origin(col, row)]
    )
    return f"""{head}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

{cells}</vector>
"""


def monochrome_xml() -> str:
    head = HEADER.format(
        title="主题化图标（Android 13+ 的「主题图标」）：同一个五方块图形，单色。",
        note="系统只取本图层的 alpha 作为蒙版再用主题色填充，所以这里不区分颜色。",
    )
    cells = "".join(
        f'    <path\n        android:fillColor="#FFFFFFFF"\n'
        f'        android:pathData="{square_path(x, y, CELL)}" />\n'
        for col, row in emitted_cells()
        for x, y in [cell_origin(col, row)]
    )
    return f"""{head}<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">

{cells}</vector>
"""


def adaptive_icon_xml() -> str:
    return """<?xml version="1.0" encoding="utf-8"?>
<!--
  自适应图标入口（API 26+）。工程 minSdk = 36，桌面只会用到这一个入口，
  因此不需要再放各密度的 PNG 位图。

  三层：背景（品牌蓝）/ 前景（五个方块）/ 单色（Android 13+ 主题图标）。
-->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
"""


def standalone_svg() -> str:
    """一个普通的 SVG（不是 Android 资源），方便在浏览器/设计工具里直接看。"""
    cells = "".join(
        f'  <rect x="{x:g}" y="{y:g}" width="{CELL:g}" height="{CELL:g}" fill="#FFF" />\n'
        for col, row in emitted_cells()
        for x, y in [cell_origin(col, row)]
    )
    return f'''<?xml version="1.0" encoding="UTF-8"?>
<!--
  OrePlan 图标：五个有棱角的方块（3 上 2 下、左对齐），单色品牌蓝底。
  108×108 与自适应图标画布一致：中央 72×72 是可见区，图形落在直径 66 的安全圆内。
  本文件由 scripts/make_icon.py 生成。
-->
<svg xmlns="http://www.w3.org/2000/svg" width="108" height="108" viewBox="0 0 108 108">
  <rect width="108" height="108" fill="#{BG_COLOR[3:].lower()}" />
{cells}</svg>
'''


def write_resources() -> None:
    targets = {
        RES / "drawable" / "ic_launcher_background.xml": background_xml(),
        RES / "drawable" / "ic_launcher_foreground.xml": foreground_xml(),
        RES / "drawable" / "ic_launcher_monochrome.xml": monochrome_xml(),
        RES / "mipmap-anydpi-v26" / "ic_launcher.xml": adaptive_icon_xml(),
        ROOT / "oreplan-icon.svg": standalone_svg(),
    }
    for path, text in targets.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf8")
        print(f"写入 {path.relative_to(ROOT)}")


# ---------------------------------------------------------------------------
# 预览渲染
# ---------------------------------------------------------------------------


def _rgb(color: str) -> tuple[int, int, int]:
    """"#AARRGGBB"（Android 写法）取其中的 RGB。"""
    hexed = color.lstrip("#")
    return tuple(int(hexed[i:i + 2], 16) for i in (2, 4, 6))


def render_icon(size: int, mask: str, ss: int = 4):
    """按同一套参数渲染图标。ss 是超采样倍数。"""
    from PIL import Image, ImageDraw

    n = size * ss
    scale = n / CANVAS
    img = Image.new("RGB", (n, n), _rgb(BG_COLOR))

    d = ImageDraw.Draw(img)
    for col, row in emitted_cells():
        x, y = cell_origin(col, row)
        d.rectangle([x * scale, y * scale, (x + CELL) * scale - 1, (y + CELL) * scale - 1],
                    fill=_rgb(CELL_COLOR))
    img = img.convert("RGBA")

    mask_img = Image.new("L", (n, n), 0)
    md = ImageDraw.Draw(mask_img)
    if mask == "circle":
        md.ellipse([0, 0, n - 1, n - 1], fill=255)
    elif mask == "squircle":
        md.rounded_rectangle([0, 0, n - 1, n - 1], radius=n * 0.28, fill=255)
    else:  # square：完整 108 画布，用来看图形本身
        md.rectangle([0, 0, n - 1, n - 1], fill=255)

    out = Image.new("RGBA", (n, n), (0, 0, 0, 0))
    out.paste(img, (0, 0), mask_img)
    return out.resize((size, size), Image.LANCZOS)


def render_preview(path: Path) -> None:
    from PIL import Image, ImageDraw

    sizes = [
        (192, "square"),    # 完整 108 画布：看图形本身
        (192, "squircle"),  # 常见的圆角方形遮罩
        (96, "squircle"),
        (48, "squircle"),
        (192, "circle"),    # 圆形遮罩（部分启动器）
        (48, "circle"),
    ]
    labels = ["square 192", "squircle 192", "96", "48", "circle 192", "48"]
    pad, label_h = 28, 30
    biggest = max(s for s, _ in sizes)
    cell_w = biggest + pad * 2
    row_h = biggest + pad * 2 + label_h
    sheet = Image.new("RGB", (cell_w * len(sizes), row_h + 90), (238, 240, 245))
    sd = ImageDraw.Draw(sheet)
    sd.text((24, 22), "OrePlan icon preview - " + " / ".join(labels), fill=(40, 46, 60))

    for i, (size, mask) in enumerate(sizes):
        icon = render_icon(size, mask)
        x = i * cell_w + (cell_w - size) // 2
        y = 70 + (biggest - size) // 2
        sheet.paste(icon, (x, y), icon)
        sd.text((i * cell_w + 24, 44), labels[i], fill=(110, 118, 132))

    DIST.mkdir(exist_ok=True)
    sheet.save(path)
    print(f"预览 {path.relative_to(ROOT)}（{sheet.width}×{sheet.height}）")


def report_geometry() -> None:
    worst = worst_corner_radius()
    print(f"图形 {GRID_W:g}×{GRID_H:g}dp，原点 ({X0:g}, {Y0:g})，方块 {CELL:g}dp / 间距 {GAP:g} / 直角")
    print(f"最外侧角点距中心 {worst:.2f}dp（安全半径 {SAFE_RADIUS:g}dp）"
          f" -> {'在安全区内' if worst <= SAFE_RADIUS else '超出安全区，需要缩小！'}")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--preview", action="store_true", help="只渲染预览，不覆盖 res/")
    args = parser.parse_args()

    report_geometry()
    if not args.preview:
        write_resources()
    render_preview(DIST / "icon-preview.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
