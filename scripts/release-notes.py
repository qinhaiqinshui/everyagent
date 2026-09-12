#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
release-notes.py —— 提取「最新 tag → 指定 ref(默认 HEAD)」之间的 commit message,生成 release note 草稿

用法:
  python scripts/release-notes.py                     # 最新 tag..HEAD,按提交前缀分组
  python scripts/release-notes.py --list              # 平铺列表,不分组
  python scripts/release-notes.py v0.2.0              # 指定起始 tag
  python scripts/release-notes.py v0.2.0 origin/main  # 指定起始 tag 与终点 ref
  python scripts/release-notes.py --merges            # 包含 merge commit(默认排除)
  python scripts/release-notes.py --match 'v*'        # 仅把匹配 v* 的 tag 视作候选"最新 tag"

输出为 Markdown,重定向即可保存:python scripts/release-notes.py > RELEASE_NOTES.md
注:「最新 tag」取 git describe --tags --abbrev=0,即从 HEAD 回溯可直达的最近 tag。
输出顶部会追加「影响模块」行:汇总提交范围内被修改文件所属的业务模块(仅统计五个业务模块目录,根目录文件不纳入)。
"""

import argparse
import subprocess
import sys
from datetime import datetime

# Windows 控制台默认 GBK,强制 UTF-8 避免中文乱码/报错
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except AttributeError:
    pass

# 业务模块目录(按 docs/ARCHITECTURE.md 的模块顺序);根目录文件不纳入「影响模块」
MODULE_DIRS = [
    "every-agent-hub",
    "every-agent-worker",
    "every-agent-web",
    "every-agent-contract",
    "every-agent-desktop",
]


def die(msg: str) -> None:
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(1)


def run_git(args, check=True, ignore_stderr=False):
    """执行 git 命令,返回 stdout(已去尾换行)。check=True 时失败即退出。"""
    try:
        proc = subprocess.run(
            ["git"] + args,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except FileNotFoundError:
        die("找不到 git 命令,请确认 git 已安装并在 PATH 中")
    if check and proc.returncode != 0:
        err = proc.stderr.strip() if not ignore_stderr else ""
        die(f"git {' '.join(args)} 失败: {err or '未知错误'}")
    return proc.returncode, proc.stdout.rstrip("\n")


def affected_modules(from_tag, to_ref):
    """返回提交范围内被修改文件所属的业务模块(去重、按 MODULE_DIRS 顺序);根目录文件不纳入。"""
    if from_tag:
        _, files_out = run_git(["diff", "--name-only", f"{from_tag}..{to_ref}"])
    else:
        # 仓库尚无 tag:以 to_ref 的整棵树作为「全部变更」,等于从空树对比
        _, files_out = run_git(["ls-tree", "-r", "--name-only", to_ref])
    tops = set()
    for path in files_out.splitlines():
        path = path.strip()
        if path:
            tops.add(path.split("/", 1)[0])
    return [m for m in MODULE_DIRS if m in tops]


def parse_args():
    parser = argparse.ArgumentParser(
        description="提取「最新 tag → 终点 ref(默认 HEAD)」的 commit message,输出 Markdown release note 草稿。"
                    "分组模式会把 \"前缀: 描述\" 风格的提交按前缀归类(如 前端/worker/feat),无前缀或前缀过长的归入「其他」。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python scripts/release-notes.py                    # 最新 tag..HEAD,分组\n"
            "  python scripts/release-notes.py --list v0.1.2      # v0.1.2..HEAD,平铺\n"
            "  python scripts/release-notes.py --match 'v*' v0.2.0 main"
        ),
    )
    parser.add_argument("from_tag", nargs="?", default=None,
                        help="起始 tag;省略则自动取最新 tag")
    parser.add_argument("to_ref", nargs="?", default="HEAD",
                        help="终点 ref(默认 HEAD)")
    parser.add_argument("-l", "--list", action="store_true",
                        help="平铺输出全部提交,不分组")
    parser.add_argument("-m", "--merges", action="store_true",
                        help="包含 merge commit(默认排除)")
    parser.add_argument("--match", metavar="GLOB", default=None,
                        help="仅在匹配 glob 的 tag(如 v*)中取最新")
    return parser.parse_args()


def main():
    args = parse_args()

    # 前置检查:必须在 git 仓库内
    code, _ = run_git(["rev-parse", "--is-inside-work-tree"], check=False)
    if code != 0 or not _.strip() == "true":
        die("当前目录不在 git 仓库内")

    if args.from_tag:
        code, _ = run_git(["rev-parse", "-q", "--verify", f"{args.from_tag}^{{commit}}"], check=False)
        if code != 0:
            die(f"起始 tag 不存在: {args.from_tag}")
    code, _ = run_git(["rev-parse", "-q", "--verify", f"{args.to_ref}^{{commit}}"], check=False)
    if code != 0:
        die(f"终点 ref 不存在: {args.to_ref}")

    # 确定起始 tag:未指定则取 HEAD 回溯可直达的最近 tag
    from_tag = args.from_tag
    if not from_tag:
        describe_args = ["describe", "--tags", "--abbrev=0"]
        if args.match:
            describe_args += ["--match", args.match]
        code, out = run_git(describe_args, check=False)
        if code == 0:
            from_tag = out.strip()

    if from_tag:
        rev_range = f"{from_tag}..{args.to_ref}"
        from_hint = from_tag
    else:
        rev_range = args.to_ref  # 仓库尚无任何 tag:退化为全部历史
        from_hint = "仓库起点"

    merge_args = [] if args.merges else ["--no-merges"]

    _, count_out = run_git(["rev-list", "--count"] + merge_args + [rev_range])
    count = int(count_out.strip() or "0")
    if count == 0:
        print(f"没有新提交:{from_hint} 与 {args.to_ref} 之间没有差异。", file=sys.stderr)
        return

    _, log_out = run_git(["log", "--pretty=format:%s%x09%h"] + merge_args + [rev_range])
    lines = [l for l in log_out.split("\n") if l.strip()]

    _, to_hash = run_git(["rev-parse", "--short", args.to_ref])

    modules = affected_modules(from_tag, args.to_ref)

    # ---------- 输出 ----------
    print(f"# Release Notes({from_hint} → {args.to_ref}@{to_hash.strip()})")
    print()
    print(f"> 共 {count} 条提交,生成于 {datetime.now():%Y-%m-%d %H:%M:%S}。")
    if modules:
        print(f"影响模块：{'、'.join(modules)}")

    if args.list:
        print()
        for line in lines:
            subject, _, h = line.rpartition("\t")
            print(f"- {subject}({h})")
        return

    # 分组模式:按首个冒号(半角/全角)前的短前缀归类,保持首次出现顺序
    groups: dict[str, list[str]] = {}
    for line in lines:
        subject, _, h = line.rpartition("\t")
        prefix, rest = "", subject
        for i, ch in enumerate(subject):
            if ch in (":", "："):
                candidate = subject[:i]
                if candidate and len(candidate) <= 12 and " " not in candidate:
                    prefix = candidate
                    rest = subject[i + 1:].lstrip(" ")
                break
        groups.setdefault(prefix or "其他", []).append(f"- {rest}({h})")

    print()
    for g, items in groups.items():
        print(f"## {g}")
        print()
        print("\n".join(items))
        print()


if __name__ == "__main__":
    main()
