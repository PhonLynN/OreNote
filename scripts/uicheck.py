#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
UI 快照 / 对比工具（视觉回归用）。

为什么要有它：本项目「全屏展示页 ↔ 全屏编辑页」历史上因**外观分叉**返工三次。
「视觉与原版完全一致」这种验收标准，靠肉眼看是不可靠的 —— 必须能**量化**，
否则改了 1dp 也没人发现。

它做两件事：
  1. 驱动界面并抓图（dump / tap / shot），得到一组**命名快照**；
  2. 把两组快照逐像素对比，报告差异比例与差异区域，并输出可视化差异图。

用法（在 OreNote/ 下）：
    python scripts/uicheck.py launch
    python scripts/uicheck.py dump                       # 列出可点/有文字的节点
    python scripts/uicheck.py tap-text 白板               # 按文字点（含 content-desc）
    python scripts/uicheck.py tap 540 1200                # 按坐标点
    python scripts/uicheck.py longpress 540 1600          # 长按
    python scripts/uicheck.py back
    python scripts/uicheck.py shot before/card            # 抓到 verification/shots/<名字>.png
    python scripts/uicheck.py diff before after           # 对比两个目录

对比说明：
    · 默认**屏蔽顶部 130px**（状态栏时钟每个时刻都不同，会造成假差异）
      与**底部 90px**（手势条）,可用 --mask-top/--mask-bottom 调。
    · 差异图存在 verification/shots/_diff_<a>_<b>.png，红=不同。
    · 退出码：0 = 无差异；2 = 有差异（便于脚本卡点）。
"""

import argparse
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
# 快照仍放 verification/shots（该目录被 .gitignore 忽略：截图随时可重生成，不入库）。
# 但**本脚本自己放在 scripts/（入库）** —— 它是工具代码，不是可重生成的产物。
SHOTS = os.path.join(ROOT, "verification", "shots")
PKG = "com.phonlynn.oreplan"
ACTIVITY = f"{PKG}/.MainActivity"

DEVICE = os.environ.get("ANDROID_SERIAL", "emulator-5554")


def find_adb() -> str:
    """优先读 local.properties 的 sdk.dir，其次 PATH，最后常见位置。"""
    props = os.path.join(ROOT, "local.properties")
    if os.path.isfile(props):
        with open(props, encoding="utf-8", errors="ignore") as fh:
            for line in fh:
                if line.strip().startswith("sdk.dir"):
                    sdk = line.split("=", 1)[1].strip().replace("\\\\", "\\")
                    cand = os.path.join(sdk, "platform-tools", "adb.exe")
                    if os.path.isfile(cand):
                        return cand
    for root in (
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk"),
        r"D:\AndroidDev\Sdk",
        r"C:\Android\Sdk",
    ):
        cand = os.path.join(root, "platform-tools", "adb.exe")
        if os.path.isfile(cand):
            return cand
    return "adb"


ADB = find_adb()


def adb(*args: str, binary: bool = False):
    """跑一条 adb 命令。binary=True 时返回原始字节（截图用）。"""
    cmd = [ADB, "-s", DEVICE, *args]
    if binary:
        return subprocess.run(cmd, capture_output=True).stdout
    res = subprocess.run(cmd, capture_output=True, text=True, errors="ignore")
    return (res.stdout or "") + (res.stderr or "")


def shell(*args: str, binary: bool = False):
    return adb("shell", *args, binary=binary)


# ---------------------------------------------------------------- 抓图


def shot(name: str) -> str:
    """抓一张图到 verification/shots/<name>.png（先写设备再 pull：
    exec-out 重定向在 PowerShell 下会破坏二进制）。"""
    out = os.path.join(SHOTS, name.replace("/", os.sep) + ".png")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    remote = "/sdcard/_uicheck.png"
    shell("screencap", "-p", remote)
    adb("pull", remote, out)
    shell("rm", "-f", remote)
    print(f"[shot] {os.path.relpath(out, ROOT)}  ({os.path.getsize(out)} bytes)")
    return out


# ---------------------------------------------------------------- 界面驱动


def dump_xml(retries: int = 2) -> ET.Element:
    """uiautomator dump 偶发失败 —— 重试一次（这是项目已知的坑）。"""
    remote = "/sdcard/_ui.xml"
    last = ""
    for _ in range(retries + 1):
        last = shell("uiautomator", "dump", remote)
        if "dumped" in last.lower():
            break
        time.sleep(1.0)
    else:
        raise SystemExit(f"[dump] uiautomator 连续失败：{last.strip()}")
    local = os.path.join(SHOTS, "_ui.xml")
    os.makedirs(SHOTS, exist_ok=True)
    adb("pull", remote, local)
    shell("rm", "-f", remote)
    return ET.parse(local).getroot()


def parse_bounds(s: str):
    """'[x1,y1][x2,y2]' -> (x1,y1,x2,y2) 或 None。"""
    try:
        a, b = s.split("][")
        x1, y1 = (int(v) for v in a.strip("[]").split(","))
        x2, y2 = (int(v) for v in b.strip("[]").split(","))
        return x1, y1, x2, y2
    except Exception:
        return None


def nodes(root: ET.Element):
    for n in root.iter("node"):
        b = parse_bounds(n.get("bounds", ""))
        if not b:
            continue
        text = n.get("text") or ""
        desc = n.get("content-desc") or ""
        rid = n.get("resource-id") or ""
        if text or desc or rid or n.get("clickable") == "true":
            yield n, b, text, desc, rid


def cmd_dump(_args):
    root = dump_xml()
    for n, b, text, desc, rid in nodes(root):
        flags = []
        if n.get("clickable") == "true":
            flags.append("click")
        if n.get("long-clickable") == "true":
            flags.append("long")
        if n.get("checkable") == "true":
            flags.append(f"checked={n.get('checked')}")
        label = text or desc or rid
        if not label and not flags:
            continue
        cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
        print(f"({cx:>4},{cy:>4}) {b!s:<24} {'/'.join(flags):<14} {label[:70]}")
    return 0


def cmd_tap_text(args):
    needle = " ".join(args.text)
    root = dump_xml()
    for _n, b, text, desc, _rid in nodes(root):
        blob = f"{text} {desc}"
        if needle in blob:
            cx, cy = (b[0] + b[2]) // 2, (b[1] + b[3]) // 2
            shell("input", "tap", str(cx), str(cy))
            print(f"[tap-text] '{needle}' -> ({cx},{cy})  命中: {blob.strip()[:50]}")
            return 0
    print(f"[tap-text] 没找到包含 '{needle}' 的节点", file=sys.stderr)
    return 1


def cmd_tap(args):
    shell("input", "tap", str(args.x), str(args.y))
    print(f"[tap] ({args.x},{args.y})")
    return 0


def cmd_longpress(args):
    """长按：input swipe 同点按住（duration 毫秒）。"""
    shell("input", "swipe", str(args.x), str(args.y), str(args.x), str(args.y), str(args.ms))
    print(f"[longpress] ({args.x},{args.y}) {args.ms}ms")
    return 0


def cmd_back(_args):
    shell("input", "keyevent", "4")
    print("[back]")
    return 0


def cmd_launch(_args):
    shell("am", "force-stop", PKG)
    time.sleep(0.6)
    shell("am", "start", "-n", ACTIVITY)
    time.sleep(2.5)
    print("[launch]")
    return 0


# ---------------------------------------------------------------- 对比


def cmd_diff(args):
    from PIL import Image, ImageChops

    a_dir = os.path.join(SHOTS, args.a)
    b_dir = os.path.join(SHOTS, args.b)
    if not os.path.isdir(a_dir) or not os.path.isdir(b_dir):
        raise SystemExit(f"目录不存在：{a_dir} / {b_dir}")

    names = sorted(
        f for f in os.listdir(a_dir) if f.lower().endswith(".png") and not f.startswith("_")
    )
    if not names:
        raise SystemExit(f"{a_dir} 里没有快照")

    worst = 0.0
    problems = 0
    print(f"对比 {args.a} -> {args.b}   屏蔽 上{args.mask_top}px / 下{args.mask_bottom}px")
    print(f"{'快照':<28}{'差异像素':>10}{'占比':>9}   差异区域")
    print("-" * 78)

    for name in names:
        pa, pb = os.path.join(a_dir, name), os.path.join(b_dir, name)
        if not os.path.isfile(pb):
            print(f"{name:<28}{'—— 缺失 ——':>20}")
            problems += 1
            continue

        ia, ib = Image.open(pa).convert("RGB"), Image.open(pb).convert("RGB")
        if ia.size != ib.size:
            print(f"{name:<28}尺寸不同 {ia.size} vs {ib.size}")
            problems += 1
            continue

        w, h = ia.size
        y0, y1 = args.mask_top, h - args.mask_bottom
        box = (0, y0, w, y1)
        ca, cb = ia.crop(box), ib.crop(box)

        diff = ImageChops.difference(ca, cb).convert("L")
        # 容忍极小的编码噪声，但不容忍任何真实位移/尺寸变化
        mask = diff.point(lambda v: 255 if v > args.tolerance else 0)
        bbox = mask.getbbox()
        total = mask.size[0] * mask.size[1]
        # 用直方图数「非零像素」：getdata() 在 Pillow 14 会被移除。
        # mask 只有 0/255 两种取值，所以 总数 − 0 的个数 = 变化像素数。
        changed = total - mask.histogram()[0]
        pct = 100.0 * changed / total if total else 0.0

        if changed:
            problems += 1
            worst = max(worst, pct)
            region = f"x{bbox[0]}..{bbox[2]}, y{bbox[1] + y0}..{bbox[3] + y0}"
            # 可视化：把差异涂红叠在 after 图上
            vis = cb.copy()
            red = Image.new("RGB", cb.size, (255, 0, 0))
            vis.paste(red, (0, 0), mask)
            out = os.path.join(SHOTS, f"_diff_{args.a}_{args.b}_{name}")
            vis.save(out)
        else:
            region = "—"

        print(f"{name:<28}{changed:>10}{pct:>8.3f}%   {region}")

    print("-" * 78)
    if problems:
        print(f"❌ {problems} 张快照有差异（最大 {worst:.3f}%）")
        print(f"   差异图：verification/shots/_diff_{args.a}_{args.b}_*.png")
        return 2
    print("✅ 全部一致")
    return 0


# ---------------------------------------------------------------- CLI


def main():
    p = argparse.ArgumentParser(description="OreNote UI 快照 / 视觉回归工具")
    sub = p.add_subparsers(dest="cmd", required=True)

    sub.add_parser("launch").set_defaults(fn=cmd_launch)
    sub.add_parser("dump").set_defaults(fn=cmd_dump)
    sub.add_parser("back").set_defaults(fn=cmd_back)

    t = sub.add_parser("tap"); t.add_argument("x", type=int); t.add_argument("y", type=int)
    t.set_defaults(fn=cmd_tap)

    tt = sub.add_parser("tap-text"); tt.add_argument("text", nargs="+")
    tt.set_defaults(fn=cmd_tap_text)

    lp = sub.add_parser("longpress")
    lp.add_argument("x", type=int); lp.add_argument("y", type=int)
    lp.add_argument("--ms", type=int, default=800)
    lp.set_defaults(fn=cmd_longpress)

    s = sub.add_parser("shot"); s.add_argument("name")
    s.set_defaults(fn=lambda a: shot(a.name))

    d = sub.add_parser("diff")
    d.add_argument("a"); d.add_argument("b")
    d.add_argument("--mask-top", type=int, default=130)
    d.add_argument("--mask-bottom", type=int, default=90)
    d.add_argument("--tolerance", type=int, default=6,
                   help="单通道差多少算不同（抗编码噪声，默认 6）")
    d.set_defaults(fn=cmd_diff)

    args = p.parse_args()
    rc = args.fn(args)
    sys.exit(rc or 0)


if __name__ == "__main__":
    main()
