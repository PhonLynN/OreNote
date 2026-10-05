#!/usr/bin/env python3
"""打包 拓记·OreNote 的 APK，并**自动递增版本号**。

为什么要有这个脚本：版本号靠人记必然漏。以前每次打包都是手动改
`app/build.gradle.kts`，改到第三次就出现「装上去看不出是哪一版、装不上时也不知道
是覆盖还是重装」的情况。现在把「递增版本号 → 构建 → 落到 dist/」固定成一步。

用法：
    python scripts/package.py --release    # ★ 常规出包：**第三级 +1**（0.4.0 → 0.4.1 → 0.4.2 …）
    python scripts/package.py              # 同上（debug 包）
    python scripts/package.py --tune       # 调参：0.4.1 → 0.4.1.1 → 0.4.1.2 …
                                           #   同一个功能反复试手感/参数时用，不进正式版本号
    python scripts/package.py 0.4.0        # 阶段进位：显式指定（用户宣布阶段切换时用）

## ⚠️⚠️ 用户 2026-10-04 明确要求：**每次打包 z 都要 +1**

> 「从现在开始，每次打包都要提升三级标题」

也就是 **`x.y.z` 的 `z` 每次 +1** —— 这正是**不带 `--keep`** 的默认行为。
所以**常规出包一律用 `python scripts/package.py --release`**。

### `--keep` 为什么把一个版本冻了十几次

`--keep` 是 2026-10-03 加的，本意是"同一个版本内反复出包时别把版本号跳花"。
但它被**误用成了默认**：我在修对话页 bug 的那十几轮里每轮都加 `--keep`，
于是 `versionName` **一直停在 `0.4.0`**，而 `versionCode` 从 555 涨到 631。

后果（用户实际遇到的）：
- 装上去**看不出是哪一版** —— 十几次构建全都显示 `0.4.0`
- 用户报「装不上」时，**无法用版本号判断装的是哪个包**
- 我甚至在排查时拿"版本号没变"当成线索，反而绕远了

**结论：`--keep` 只在一个场景下用 —— 同一个版本号内需要重打一个包
（比如构建产物坏了要重建）。其余一律让 `z` +1。**

版本规则（2026-09-28 按实际用法修正，旧“只改白板”口径已作废）：
    前两位 N.M —— 阶段。阶段性里程碑时进位（0.1.x → 0.2.0，用户 2026-09-28 宣布）。
    第三级     —— **每次更新** +1。历史的 0.1.0→0.1.247 期间实际就是这样用的，
                  与旧说法“白板下的小功能”早已脱节，故按实际用法记录。
    第四级     —— 调参，只在同一功能内反复试参数时用；定稿后全部清理。

    versionCode 与 versionName 解耦，**始终递增**（不能随版本号一起重置，
    否则旧版本无法被覆盖安装）。

    产物：dist/OreNote-<versionName>-debug.apk（或 -release.apk，看有没有 --release）
    dist/ 只保留最近 5 个正式版本包（不含调参子版本）。

关于构建类型（2026-09-25 更新）：
    · 默认 `assembleDebug` —— 日常联调用，可断点、构建快。
    · `--release` —— **可安装的正式包**：release 变体已配成「debug 密钥签名 +
      R8 压缩 + isDebuggable=false」，applicationId 不带后缀 → 与 debug 包
      同包名同签名，可直接覆盖安装、数据不丢（2026-09-25 已实测：37.6MB 的 debug 包
      被 4.4MB 的 release 包覆盖成功）。
      它的意义：debug 包在**冷安装**后有一段预热期（解释执行 → JIT → 后台 AOT），
      表现为「冷启动后先卡几分钟、用着用着自己变好」——这是 debug 包的固有行为，
      不是业务代码的问题。要评估真实的启动/动画性能、或验证性能优化，装它。
      （R8 的保留规则在 app/proguard-rules.pro；新增反射/序列化用法时记得同步。）
"""

import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE_FILE = ROOT / "app" / "build.gradle.kts"
APK_SOURCE = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
# release 变体：**可安装的正式包**（debug 密钥签名，与 debug 包同包名同签名）。
# 它开了 R8 + isDebuggable=false，冷启动没有 debug 包的预热期（2026-09-25）。
APK_RELEASE = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
DIST = ROOT / "dist"

# dist/ 里保留的正式版本包数量。
KEEP_VERSIONS = 5


def parts_of(version_name: str) -> list[int]:
    return [int(x) for x in version_name.split(".")]


def base_version(version_name: str) -> str:
    """取正式版本（三段）：0.1.0.2 → 0.1.0。"""
    return ".".join(str(x) for x in parts_of(version_name)[:3])


def bump_feature(version_name: str) -> str:
    """小功能定稿：第三级 +1。0.1.0 → 0.1.1 → 0.1.2 …

    前两位（0.1）是「白板环节」，在用户宣布白板做完之前不动。
    """
    a, b, c = parts_of(base_version(version_name))
    return f"{a}.{b}.{c + 1}"


def next_tune(version_name: str) -> str:
    """调参子版本：0.1.0 → 0.1.0.1；0.1.0.3 → 0.1.0.4。"""
    base = base_version(version_name)
    ps = parts_of(version_name)
    if len(ps) == 3:
        return f"{base}.1"
    return f"{base}.{ps[3] + 1}"


def prune_dist(keep_name: str, tuning: bool) -> None:
    """清理 dist/。

    调参子版本（四段）只是试错中间产物，不留在 dist：
      - 普通打包：删除所有子版本包。
      - 调参打包（--tune）：保留刚打出的这一个（还要拿去装），删除同主版本下更早的。
    正式版本包只保留最近 KEEP_VERSIONS 个。

    ⚠️ **debug 与 release 两种产物都要清**（2026-09-28 修）：
    原实现只 glob `OreNote-*-debug.apk`，而**实际出包大多是 release**，
    于是 release 包从来没被清过 —— dist 里累积到 40 个。
    现在两种后缀各自保留最近 KEEP_VERSIONS 个。
    """
    if not DIST.is_dir():
        return

    def key(p: Path):
        return [int(n) for n in re.findall(r"\d+", p.stem)]

    for suffix in ("debug", "release"):
        apks = sorted(DIST.glob(f"OreNote-*-{suffix}.apk"), key=key)
        # 版本段数量：3 段 = 正式版（0.2.0），4 段 = 调参子版本（0.2.0.1）。
        official = [p for p in apks if len(re.findall(r"\d+", p.stem)) == 3]
        subs = [p for p in apks if len(re.findall(r"\d+", p.stem)) == 4]

        for p in subs:
            if tuning and p.stem == keep_name:
                continue
            p.unlink()
            print(f"删除调参子版本：{p.name}")

        if len(official) > KEEP_VERSIONS:
            for p in official[:-KEEP_VERSIONS]:
                p.unlink()
                print(f"删除历史版本：{p.name}")


def main() -> int:
    argv = sys.argv[1:]
    tune = "--tune" in argv
    # --release：出**可安装的正式包**（R8 + 非 debuggable，debug 密钥签名）。
    # 日常联调继续用 debug；要验证真实的启动/动画性能（避开 debug 包的预热期）就用它。
    release = "--release" in argv
    # --keep：**只升 versionCode，版本号不动**。
    #
    # ⚠️ 用户 2026-10-04 要求「每次打包 z 都 +1」，所以**默认（不带 --keep）才是常规出包**。
    # `--keep` 只用于"同一个版本号内重打一个包"（如构建产物坏了要重建）——
    # 我之前把它当默认用了十几轮，导致 versionName 冻在 0.4.0，
    # 用户看不出装的是哪一版。见文件头的说明。
    keep = "--keep" in argv
    explicit = next((a for a in argv if not a.startswith("--")), None)

    if keep and explicit:
        raise SystemExit("--keep 与显式版本号不能同时用：--keep 的意思就是版本号不动")

    if keep:
        print(
            "⚠️  --keep：versionName **保持不动**，只升 versionCode。\n"
            "    用户要求「每次打包 z +1」—— 常规出包请**不要**加 --keep。\n"
            "    （只有'同一版本号内重打包'才该用它）",
        )

    text = GRADLE_FILE.read_text(encoding="utf8")
    code_match = re.search(r"versionCode = (\d+)", text)
    name_match = re.search(r'versionName = "([^"]+)"', text)
    if not code_match or not name_match:
        raise SystemExit("在 app/build.gradle.kts 里找不到 versionCode / versionName")

    old_code = int(code_match.group(1))
    old_name = name_match.group(1)
    new_code = old_code + 1

    if keep:
        new_name = old_name
    elif explicit:
        new_name = explicit
    elif tune:
        new_name = next_tune(old_name)
    else:
        new_name = bump_feature(old_name)

    text = re.sub(r"versionCode = \d+", f"versionCode = {new_code}", text, count=1)
    text = text.replace(f'versionName = "{old_name}"', f'versionName = "{new_name}"', 1)
    GRADLE_FILE.write_text(text, encoding="utf8")
    print(f"版本号：{old_name} (code {old_code}) -> {new_name} (code {new_code})")

    gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
    extra = os.environ.get("ORENOTE_GRADLE_ARGS", "--offline").split()
    task = "assembleRelease" if release else "assembleDebug"
    subprocess.run([gradlew, task, *extra], cwd=ROOT, check=True)

    source = APK_RELEASE if release else APK_SOURCE
    if not source.is_file():
        raise SystemExit(f"没有找到构建产物：{source}")

    DIST.mkdir(exist_ok=True)
    kind = "release" if release else "debug"
    target = DIST / f"OreNote-{new_name}-{kind}.apk"
    shutil.copy2(source, target)
    size_mb = target.stat().st_size / 1024 / 1024
    print(f"APK：{target}（{size_mb:.1f} MB）")

    prune_dist(target.stem, tune)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
