#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
bump-version.py —— 升级 / 校验全仓版本号(单点源:根 pom 的 <revision>)

版本口径(Maven CI-friendly versions)
------------------------------------
Maven 侧只有一个版本写点:根 pom 的 <properties><revision>。
  - contract / hub / worker / plugin-api,以及根 reactor 内的插件(task-edit-resend),
    都不写自身 <version>,统一继承 ${revision};
  - 仅由 PluginLoader 运行时加载的内置插件各自持有自己的版本(与它自己的 plugin.json 的
    version 同号),不随工程版本升级 —— 升级时只改 <revision> 一处,插件 pom 零改动;
  - 跨模块引用一律写 ${revision},不得写 ${project.version}(后者解析的是「消费方自己」
    的版本,在插件里会静默指错坐标)。

本脚本负责 Maven 之外仍需同号的槽位,以及防回归体检:

  槽位                     位置
  -----------------------  --------------------------------------------------
  every-agent 工程版本      根 pom.xml 的 <revision>(Maven 侧唯一写点)
  @everyagent/plugin-api   every-agent-plugin-api/js/package.json 的 version
  桌面端                    every-agent-desktop/package.json 的 version
  浏览器端                  every-agent-web/package.json 的 version

用法:
  python scripts/bump-version.py                  # 不带参数:展示当前版本 + 体检
  python scripts/bump-version.py 1.0.1            # 升级到 1.0.1(自动检测当前版本)
  python scripts/bump-version.py 1.0.1 --from 1.0.0   # 显式指定当前版本
  python scripts/bump-version.py 1.0.1 --dry-run      # 仅预览改动,不写文件
  python scripts/bump-version.py 1.0.1 --check        # 校验是否已统一为 1.0.1

体检(展示、升级、--check 都会跑;不通过即退出码 1):
  1. 四个槽位版本必须互相一致;
  2. 每个子模块 pom 的 every-agent-parent 引用必须写 ${revision};
  3. 任何 pom 不得残留「硬编码的工程版本」字面量(插件自身版本行除外)。

升级后记得同步脚手架模板与文档站的版本字样(本脚本不管那部分):
  powershell -ExecutionPolicy Bypass -File scripts/sync-plugin-template-versions.ps1
"""

import argparse
import json
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

# 语义化版本号格式: 主.次.修订[-预发布],如 1.0.1 / 1.2.3-beta.1
SEMVER_RE = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")

# ---------------------------------------------------------------------------
# 版本槽位定义: (相对路径, [锚点正则列表])
# 每个正则的 group(2) 即需要替换的版本号,group(1)/group(3) 为前后锚点。
# 每个正则在对应文件中必须「恰好匹配 1 次」,否则脚本报错(漏改/误改保护)。
# ---------------------------------------------------------------------------
_REVISION = re.compile(r"(<revision>)([^<]+)(</revision>)")
_JSON_VERSION = re.compile(r'("version"\s*:\s*")([^"]+)(")')

SLOTS = [
    ("pom.xml", [_REVISION]),
    ("every-agent-plugin-api/js/package.json", [_JSON_VERSION]),
    ("every-agent-desktop/package.json", [_JSON_VERSION]),
    ("every-agent-web/package.json", [_JSON_VERSION]),
]

# ---------------------------------------------------------------------------
# 体检用锚点
# ---------------------------------------------------------------------------
_PARENT_BLOCK = re.compile(r"(?s)<parent>(.*?)</parent>")
_PARENT_ARTIFACT = re.compile(r"<artifactId>([^<]+)</artifactId>")
_PARENT_VERSION = re.compile(r"<version>([^<]+)</version>")
# 允许的 parent：核心模块/插件 SDK 自身 → every-agent-parent；插件 → every-agent-plugin-parent
ALLOWED_PARENTS = {"every-agent-parent", "every-agent-plugin-parent"}
_VERSION_LINE = re.compile(r"(?m)^[ \t]*<version>([^<]+)</version>[ \t]*\r?$")

CORE_MODULES = [
    "every-agent-contract",
    "every-agent-hub",
    "every-agent-plugin-parent",
    "every-agent-plugin-api",
    "every-agent-worker",
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


def module_poms():
    """根 pom + 核心模块 pom + 所有插件 pom(动态发现,排除聚合 target/)"""
    poms = [ROOT / "pom.xml"] + [ROOT / m / "pom.xml" for m in CORE_MODULES]
    roots = [p for p in sorted((ROOT / "every-agent-plugins").glob("*/pom.xml"))]
    poms.extend(roots)
    return [p for p in poms if p.exists()]


def plugin_manifest_versions():
    """[(插件目录名, plugin.json 的 version)] —— 插件自身版本,仅供参考展示"""
    out = []
    for manifest in sorted((ROOT / "every-agent-plugins").glob("*/plugin.json")):
        try:
            data = json.loads(manifest.read_text(encoding="utf-8-sig"))
        except (OSError, ValueError):
            continue
        out.append((manifest.parent.name, str(data.get("version", ""))))
    return out


def audit(app_versions):
    """体检:parent 引用 + 硬编码工程版本残留。返回问题列表。"""
    problems = []
    for pom in module_poms():
        rel = pom.relative_to(ROOT).as_posix()
        text = pom.read_text(encoding="utf-8")
        is_root = pom.parent == ROOT

        if not is_root:
            block = _PARENT_BLOCK.search(text)
            if not block:
                problems.append(f"{rel}: 未找到 <parent> 块")
            else:
                inner = block.group(1)
                art = _PARENT_ARTIFACT.search(inner)
                ver = _PARENT_VERSION.search(inner)
                name = art.group(1).strip() if art else "?"
                value = ver.group(1).strip() if ver else None
                if name not in ALLOWED_PARENTS:
                    problems.append(f"{rel}: parent 为 {name}(应为 every-agent-parent 或 every-agent-plugin-parent)")
                if value is None:
                    problems.append(f"{rel}: parent 缺 <version>")
                elif value != "${revision}":
                    problems.append(f"{rel}: parent 版本写死为 {value}(应为 ${{revision}})")

        # 插件「紧跟 </parent> 的自身版本行」是有意为之,不算残留
        own_line_pos = None
        parent_end = text.find("</parent>")
        if parent_end >= 0:
            m = _VERSION_LINE.search(text, parent_end)
            if m:
                own_line_pos = m.start()

        for m in _VERSION_LINE.finditer(text):
            if own_line_pos is not None and m.start() == own_line_pos:
                continue
            value = m.group(1).strip()
            if value in app_versions:
                problems.append(f"{rel}: 残留硬编码工程版本 <version>{value}</version>(应改为 ${{revision}} 或删掉继承)")
    return problems


def parse_args():
    parser = argparse.ArgumentParser(
        description="升级 / 校验全仓版本号(单点源:根 pom 的 <revision>)。不带版本参数时仅展示当前版本。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python scripts/bump-version.py                 # 查看当前版本 + 体检\n"
            "  python scripts/bump-version.py 1.0.1           # 升级到 1.0.1\n"
            "  python scripts/bump-version.py 1.0.1 --dry-run # 预览改动\n"
            "  python scripts/bump-version.py 1.0.1 --check   # 校验是否已统一\n"
        ),
    )
    parser.add_argument("version", nargs="?", help="目标版本号,如 1.0.1(省略则仅展示)")
    parser.add_argument("--from", dest="from_version", help="当前版本号(可选,默认自动检测并校验一致)")
    parser.add_argument("--dry-run", action="store_true", help="仅预览,不写文件")
    parser.add_argument("--check", action="store_true", help="校验所有槽位版本是否已等于目标版本,不做修改")
    return parser.parse_args()


def validate_version(ver: str) -> None:
    if not SEMVER_RE.match(ver):
        die(f"非法版本号格式: {ver!r}(应为 主.次.修订,如 1.0.1)")


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


def report_audit(versions, ok_message: str) -> None:
    problems = audit(versions)
    if problems:
        print("体检失败:", file=sys.stderr)
        for p in problems:
            print(f"  - {p}", file=sys.stderr)
        sys.exit(1)
    print(ok_message)


def main():
    args = parse_args()
    slots = collect_slots()
    current_versions = [v for _, entries in slots.values() for _, v in entries]
    uniq = set(current_versions)

    # 展示模式: 不带目标版本
    if args.version is None:
        print("当前各槽位版本:")
        for rel, (_, entries) in slots.items():
            vers = {v for _, v in entries}
            label = vers.pop() if len(vers) == 1 else "/".join(sorted(vers))
            print(f"  {rel:<44} {label}")

        manifests = plugin_manifest_versions()
        if manifests:
            tally = {}
            for _, ver in manifests:
                tally[ver] = tally.get(ver, 0) + 1
            summary = ", ".join(f"{ver} × {n} 个" for ver, n in sorted(tally.items()))
            print(f"\n内置插件自身版本(取自各自 plugin.json,不随工程版本升级): {summary}")

        if len(uniq) == 1:
            print(f"\n统一状态: 已统一为 {next(iter(uniq))}")
        else:
            print(f"\n统一状态: 不一致 {'/'.join(sorted(uniq))}")
        report_audit(
            uniq,
            "体检通过: 无硬编码工程版本残留,子模块 parent 引用均为 ${revision}。",
        )
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
        if len(uniq) != 1:
            die(f"检测到多个不同的当前版本: {', '.join(sorted(uniq))}。请先用 --from 指定当前版本,或先手动统一。")
        from_version = uniq.pop()

    if from_version == target:
        print(f"当前版本已是 {target},无需升级。")
        if args.check:
            report_audit({target}, f"校验通过: 所有槽位版本均为 {target}。")
        return

    # 校验模式
    if args.check:
        bad = [rel for rel, (_, entries) in slots.items() for _, v in entries if v != target]
        if bad:
            print(f"校验失败: 以下文件版本尚未统一为 {target}: {', '.join(sorted(set(bad)))}")
            sys.exit(1)
        report_audit({target}, f"校验通过: 所有槽位版本均为 {target}。")
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

    for rel, before, after in changed:
        for line_before, line_after in zip(before.splitlines(), after.splitlines()):
            if line_before != line_after:
                print(f"  {rel}")
                print(f"    - {line_before.strip()}")
                print(f"    + {line_after.strip()}")

    if args.dry_run:
        print(f"\n--dry-run: 共 {len(changed)} 个文件将变更,未写盘。")
        return

    for rel, _, after in changed:
        write_text(ROOT / rel, after)

    # 写盘后按目标版本复检
    report_audit({target}, f"完成: {len(changed)} 个文件已更新为 {target}。")
    print("提醒: 脚手架模板与文档站的版本字样由 scripts/sync-plugin-template-versions.ps1 同步,请再跑一次。")


if __name__ == "__main__":
    main()