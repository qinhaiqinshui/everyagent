#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bump-version.py —— 一键统一升级各模块版本号

把以下模块的版本号统一升级到目标版本:
  - 根父工程 pom.xml              (every-agent-parent 自身版本 + every-agent-contract.version 属性)
  - every-agent-contract/pom.xml  (parent 引用版本 + 自身版本)
  - every-agent-hub/pom.xml       (parent 引用版本 + 自身版本)
  - every-agent-worker/pom.xml    (parent 引用版本 + 自身版本)
  - every-agent-desktop/package.json  (version 字段)
  - every-agent-web/package.json      (version 字段)

用法:
  python scripts/bump-version.py                # 不带参数:仅展示当前各模块版本
  python scripts/bump-version.py 0.4.0          # 升级到 0.4.0(自动检测当前版本)
  python scripts/bump-version.py 0.4.0 --from 0.3.0   # 显式指定当前版本
  python scripts/bump-version.py 0.4.0 --dry-run      # 仅预览改动,不写文件
  python scripts/bump-version.py 0.4.0 --check        # 校验所有版本号是否已统一为 0.4.0

说明:
  - 脚本通过「锚点正则」精确定位每个版本号,绝不误伤 spring-boot / 第三方依赖版本;
  - 写入时保留原文件行尾(CRLF/LF 原样),仅替换版本号本身;
  - 自动检测到的「当前版本」必须处处一致,否则要求用 --from 显式指定。
"""

import argparse
import re
import sys
from pathlib import Path

# Windows 控制台默认 GBK,强制 UTF-8 避免中文乱码/报错
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except AttributeError:
    pass

# 项目根目录(本脚本位于 scripts/ 下)
ROOT = Path(__file__).resolve().parent.parent

# 语义化版本号格式: 主.次.修订[-预发布],如 0.4.0 / 1.2.3-beta.1
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")

# ---------------------------------------------------------------------------
# 版本槽位定义: (相对路径, [锚点正则列表])
# 每个正则的 group(2) 即需要替换的版本号,group(1)/group(3) 为前后锚点。
# 每个正则在对应文件中必须「恰好匹配 1 次」,否则脚本报错(漏改/误改保护)。
# ---------------------------------------------------------------------------
_POM_PARENT = re.compile(r"(<artifactId>every-agent-parent</artifactId>\s*<version>)([^<]+)(</version>)")
_POM_CONTRACT_PROP = re.compile(r"(<every-agent-contract\.version>)([^<]+)(</every-agent-contract\.version>)")
# 注意: 本仓库模块 pom 的「自身版本」位于 <artifactId> 之前(非标准顺序),锚点取 版本号 + 其后的 artifactId
_POM_CONTRACT_SELF = re.compile(r"(<version>)([^<]+)(</version>\s*<artifactId>every-agent-contract</artifactId>)")
_POM_HUB_SELF = re.compile(r"(<version>)([^<]+)(</version>\s*<artifactId>every-agent-hub</artifactId>)")
_POM_WORKER_SELF = re.compile(r"(<version>)([^<]+)(</version>\s*<artifactId>every-agent-worker</artifactId>)")
_JSON_VERSION = re.compile(r'("version"\s*:\s*")([^"]+)(")')

SLOTS = [
    ("pom.xml", [_POM_PARENT, _POM_CONTRACT_PROP]),
    ("every-agent-contract/pom.xml", [_POM_PARENT, _POM_CONTRACT_SELF]),
    ("every-agent-hub/pom.xml", [_POM_PARENT, _POM_HUB_SELF]),
    ("every-agent-worker/pom.xml", [_POM_PARENT, _POM_WORKER_SELF]),
    ("every-agent-desktop/package.json", [_JSON_VERSION]),
    ("every-agent-web/package.json", [_JSON_VERSION]),
]


def die(msg: str) -> None:
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(1)


def read_text(path: Path) -> str:
    if not path.exists():
        die(f"文件不存在: {path}")
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def write_text(path: Path, text: str) -> None:
    with open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)


def parse_args():
    parser = argparse.ArgumentParser(
        description="一键统一升级各模块版本号。不带版本参数时仅展示当前各模块版本。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python scripts/bump-version.py                # 查看当前版本\n"
            "  python scripts/bump-version.py 0.4.0          # 升级到 0.4.0\n"
            "  python scripts/bump-version.py 0.4.0 --dry-run # 预览改动\n"
            "  python scripts/bump-version.py 0.4.0 --check   # 校验是否已统一\n"
        ),
    )
    parser.add_argument("version", nargs="?", help="目标版本号,如 0.4.0(省略则仅展示)")
    parser.add_argument("--from", dest="from_version", help="当前版本号(可选,默认自动检测并校验一致)")
    parser.add_argument("--dry-run", action="store_true", help="仅预览改动,不写文件")
    parser.add_argument("--check", action="store_true", help="校验所有版本号是否已等于目标版本,不做修改")
    return parser.parse_args()


def validate_version(ver: str) -> None:
    if not SEMVER_RE.match(ver):
        die(f"非法版本号格式: {ver!r}(应为 主.次.修订,如 0.4.0)")


def collect_slots():
    """读取所有槽位文件,校验每个正则唯一匹配,返回 {路径: (原始文本, [(pattern, 当前版本), ...])}"""
    result = {}
    for rel, patterns in SLOTS:
        path = ROOT / rel
        text = read_text(path)
        entries = []
        for pat in patterns:
            matches = list(pat.finditer(text))
            if len(matches) != 1:
                die(f"{rel}: 锚点 {pat.pattern!r} 期望匹配 1 次,实际 {len(matches)} 次(请检查文件结构)")
            entries.append((pat, matches[0].group(2)))
        result[rel] = (text, entries)
    return result


def main():
    args = parse_args()

    slots = collect_slots()

    # 汇总所有「当前版本」
    current_versions = [v for _, entries in slots.values() for _, v in entries]

    # 展示模式: 不带目标版本
    if args.version is None:
        print("当前各模块版本:")
        for rel, (_, entries) in slots.items():
            vers = {v for _, v in entries}
            label = vers.pop() if len(vers) == 1 else "/".join(sorted(vers))
            print(f"  {rel:<36} {label}")
        uniq = set(current_versions)
        print(f"\n统一状态: {'已统一为 ' + uniq.pop() if len(uniq) == 1 else '不一致 ' + '/'.join(sorted(uniq))}")
        return

    target = args.version
    validate_version(target)

    # 确定「当前版本」
    if args.from_version:
        validate_version(args.from_version)
        from_version = args.from_version
        mismatched = [rel for rel, (_, entries) in slots.items()
                      for _, v in entries if v != from_version]
        if mismatched:
            print(f"警告: --from {from_version} 与以下文件当前版本不一致: {', '.join(sorted(set(mismatched)))}", file=sys.stderr)
    else:
        uniq = set(current_versions)
        if len(uniq) != 1:
            die(f"检测到多个不同的当前版本: {', '.join(sorted(uniq))}。请先用 --from 指定当前版本,或先手动统一")
        from_version = uniq.pop()

    if from_version == target:
        print(f"当前版本已是 {target},无需升级。")
        if args.check:
            print("校验通过: 所有模块版本均为目标版本。")
        return

    # 校验模式
    if args.check:
        bad = [rel for rel, (_, entries) in slots.items() for _, v in entries if v != target]
        if bad:
            print(f"校验失败: 以下文件版本尚未统一为 {target}: {', '.join(sorted(set(bad)))}")
            sys.exit(1)
        print(f"校验通过: 所有模块版本均为 {target}。")
        return

    # 生成改动(先不写),输出 diff 摘要
    print(f"升级版本: {from_version} -> {target}\n")
    changed = []  # (rel, before, after)
    for rel, (text, entries) in slots.items():
        new_text = text
        for pat, _ in entries:
            new_text = pat.sub(lambda m: m.group(1) + target + m.group(3), new_text)
        if new_text != text:
            changed.append((rel, text, new_text))

    if not changed:
        print("没有需要修改的文件(版本号已一致)。")
        return

    for rel, text, new_text in changed:
        print(f"[{'预览' if args.dry_run else '更新'}] {rel}")
        # 只打印每个文件中真正变化的行
        for old_line, new_line in zip(text.splitlines(), new_text.splitlines()):
            if old_line != new_line:
                print(f"    - {old_line.strip()}")
                print(f"    + {new_line.strip()}")

    if args.dry_run:
        print(f"\n[dry-run] 共 {len(changed)} 个文件将变更,未写入。")
        return

    for rel, _, new_text in changed:
        write_text(ROOT / rel, new_text)
    print(f"\n完成: 已更新 {len(changed)} 个文件到版本 {target}。")


if __name__ == "__main__":
    main()
