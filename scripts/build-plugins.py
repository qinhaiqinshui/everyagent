#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
build-plugins.py —— 一条命令重建全部内置插件(前端 bundle + 插件 jar)

背景(见 docs/ARCHITECTURE.md §内置插件装载前置):
  - 内置插件不进根 reactor:根 pom 的 <modules> 只有 contract/plugin-api/hub/worker;
  - deploy.py 只打 `-pl every-agent-hub,every-agent-worker -am package`,同样不带插件;
  - BuiltInPluginScanner 要求 every-agent-plugins/<id>/target/classes/plugin.json 且
    target/ 下存在非 sources/javadoc 的 jar,否则 WARN「内置插件未构建」并跳过;
  - findTargetJars 把 target/ 下所有非 sources/javadoc 的 jar 按字典序全部塞进
    URLClassLoader → 残留旧版本 jar 会遮蔽新类(表现为「改了没生效」),
    所以重建必须带 clean;
  - 纯 web 插件的 bundle 由 every-agent-web/scripts/build-plugins.mjs(esbuild)产出,
    且 web/index.js 要被 maven resources-plugin 拷进 target/classes 打进 jar,
    因此顺序必须是「先 web bundle,后 mvn package」。

本脚本不重复实现任何构建逻辑:web 侧直接调用既有的 build-plugins.mjs(唯一事实源),
Java 侧逐插件调 mvn -f <id>/pom.xml,与 mvn -pl/-am 的 reactor 语义互不干扰。

插件清单动态发现(与 scripts/bump-version.py 同一套扫描口径):
  - Java 插件: every-agent-plugins/*/pom.xml
  - web 插件:  every-agent-plugins/*/web/index.ts
  两者都无的目录一律忽略;新增插件只要落对应文件即自动纳入,无需改本脚本。

用法:
  python scripts/build-plugins.py                    # 全量重建(先 web 后 java)
  python scripts/build-plugins.py --list             # 只列插件清单与构建状态
  python scripts/build-plugins.py --check            # 只校验产物完整性(不构建,缺项退出码 1)
  python scripts/build-plugins.py --only secret-redaction,cli-command-picker
  python scripts/build-plugins.py --exclude sandbox-wsl-ubuntu   # 例:镜像 tar.gz 由别的流水线产出
  python scripts/build-plugins.py --java-only        # 只重建 jar(不动 bundle)
  python scripts/build-plugins.py --web-only         # 只重建 esbuild bundle
  python scripts/build-plugins.py --follow           # 实时打印 maven 日志(默认捕获,失败才回显)
  python scripts/build-plugins.py --tests            # 不跳过测试
  python scripts/build-plugins.py --dry-run          # 只打印将执行的命令

前置:
  - JDK 25:--java-home > 环境变量 DEPLOY_JAVA_HOME/JAVA_HOME > 探测常见安装位置;
  - maven:--maven > 环境变量 DEPLOY_MVN > MAVEN_HOME > PATH;
  - node + every-agent-web/node_modules 已安装(esbuild 依赖);
  - 有 worker 正在运行时请先停掉:被锁定的旧 jar 会让 clean 失败。
"""

import argparse
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path

# Windows 控制台默认 GBK,强制 UTF-8 避免中文乱码/报错
try:
    sys.stdout.reconfigure(encoding="utf-8")
    sys.stderr.reconfigure(encoding="utf-8")
except AttributeError:
    pass

ROOT = Path(__file__).resolve().parent.parent
PLUGINS_DIR = ROOT / "every-agent-plugins"
WEB_DIR = ROOT / "every-agent-web"
WEB_BUNDLE_SCRIPT = WEB_DIR / "scripts" / "build-plugins.mjs"

# 与 bump-version.py / build-plugins.mjs 一致:排除插件目录下的聚合 target
SKIP_DIRS = {"target", "node_modules", ".git"}
# 与 BuiltInPluginScanner.findTargetJars 对齐的 jar 过滤
JAR_EXCLUDE_SUFFIXES = ("-sources.jar", "-javadoc.jar")


@dataclass
class Plugin:
    """一个内置插件目录:是否 Java 插件(有 pom)、是否 web 插件(有 web/index.ts)。"""

    pid: str
    dir: Path
    has_pom: bool
    has_web: bool
    notes: list = field(default_factory=list)

    @property
    def kind(self) -> str:
        if self.has_pom and self.has_web:
            return "java+web"
        if self.has_pom:
            return "java"
        return "web"

    @property
    def manifest(self) -> Path:
        """BuiltInPluginScanner 定位插件清单的位置。"""
        return self.dir / "target" / "classes" / "plugin.json" if self.has_pom else self.dir / "plugin.json"

    @property
    def bundle(self) -> Path:
        return self.dir / "web" / "index.js"

    @property
    def bundle_entry(self) -> Path:
        return self.dir / "web" / "index.ts"


def die(msg: str) -> None:
    print(f"错误: {msg}", file=sys.stderr)
    sys.exit(1)


def log(msg: str) -> None:
    print(msg, flush=True)


# ---------------------------------------------------------------------------
# 扫描:与 bump-version.py 的 discover_plugin_slots() 同口径
# ---------------------------------------------------------------------------
def discover_plugins() -> list:
    if not PLUGINS_DIR.is_dir():
        die(f"插件目录不存在: {PLUGINS_DIR}")
    plugins = []
    for entry in sorted(PLUGINS_DIR.iterdir(), key=lambda p: p.name):
        if not entry.is_dir() or entry.name in SKIP_DIRS:
            continue
        plugins.append(
            Plugin(
                pid=entry.name,
                dir=entry,
                has_pom=(entry / "pom.xml").is_file(),
                has_web=(entry / "web" / "index.ts").is_file(),
            )
        )
    return [p for p in plugins if p.has_pom or p.has_web]


def filter_plugins(plugins: list, only: list, exclude: list, java_only: bool, web_only: bool) -> list:
    if java_only and web_only:
        die("--java-only 与 --web-only 互斥")
    known = {p.pid for p in plugins}
    for name in only + exclude:
        if name not in known:
            die(f"未知插件: {name}(可用: {', '.join(sorted(known))})")
    if only:
        wanted = set(only)
        plugins = [p for p in plugins if p.pid in wanted]
    if exclude:
        unwanted = set(exclude)
        plugins = [p for p in plugins if p.pid not in unwanted]
    if java_only:
        plugins = [p for p in plugins if p.has_pom]
    if web_only:
        plugins = [p for p in plugins if p.has_web]
    return plugins


# ---------------------------------------------------------------------------
# 工具链定位
# ---------------------------------------------------------------------------
def resolve_maven(explicit: str | None) -> str:
    if explicit:
        return explicit
    env = os.environ.get("DEPLOY_MVN")
    if env:
        return env
    maven_home = os.environ.get("MAVEN_HOME")
    if maven_home:
        for name in ("mvn.cmd", "mvn"):
            cand = Path(maven_home) / "bin" / name
            if cand.is_file():
                return str(cand)
    exe = "mvn.cmd" if os.name == "nt" else "mvn"
    found = shutil.which(exe) or shutil.which("mvn")
    if found:
        return found
    die("找不到 maven:请把 mvn 加入 PATH、设置 MAVEN_HOME,或用 --maven 指定 mvn 可执行文件")


def resolve_java_home(explicit: str | None) -> str | None:
    for value in (explicit, os.environ.get("DEPLOY_JAVA_HOME"), os.environ.get("JAVA_HOME")):
        if value and (Path(value) / "bin" / "java.exe" if os.name == "nt" else Path(value) / "bin" / "java").exists():
            return value
    return None


def resolve_node() -> str:
    node = shutil.which("node")
    if not node:
        die("找不到 node:web bundle 构建依赖 node + esbuild")
    return node


# ---------------------------------------------------------------------------
# 执行
# ---------------------------------------------------------------------------
def run(cmd: list, cwd: Path, env: dict | None = None, follow: bool = False, tail: int = 40):
    """返回 (returncode, 捕获输出或 None)。follow=True 时实时透传输出。"""
    if follow:
        return subprocess.run(cmd, cwd=str(cwd), env=env).returncode, None
    p = subprocess.run(cmd, cwd=str(cwd), env=env, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if p.returncode != 0:
        out = (p.stdout or "") + (p.stderr or "")
        lines = [ln for ln in out.splitlines() if ln.strip()]
        log("\n".join(f"    | {ln}" for ln in lines[-tail:]))
    return p.returncode, p.stdout or ""


def build_web_bundles(plugins: list, node: str, dry_run: bool, follow: bool) -> bool:
    targets = [p.pid for p in plugins if p.has_web]
    if not targets:
        log("[web] 无 web 插件入口,跳过")
        return True
    if not WEB_BUNDLE_SCRIPT.is_file():
        die(f"找不到 bundle 构建脚本: {WEB_BUNDLE_SCRIPT}")
    if not (WEB_DIR / "node_modules" / "esbuild").exists():
        die(f"缺少 esbuild,请先执行: cd {WEB_DIR} ; npm install")
    log(f"[web] esbuild 全量重建 {len(targets)} 个插件 bundle(脚本为全量口径,不支持单插件过滤)…")
    if dry_run:
        log(f"    $ {node} {WEB_BUNDLE_SCRIPT}")
        return True
    rc, _ = run([node, str(WEB_BUNDLE_SCRIPT)], cwd=WEB_DIR, follow=follow)
    if rc != 0:
        log(f"[web] ✗ bundle 构建失败(退出码 {rc})")
        return False
    log("[web] ✓ bundle 完成")
    return True


def default_repo_local() -> str | None:
    """本机默认本地仓库(供沙箱/受限环境下 user.home 解析异常时兜底)。"""
    home = os.environ.get("USERPROFILE") or os.environ.get("HOME") or str(Path.home())
    cand = Path(home) / ".m2" / "repository"
    return str(cand) if cand.is_dir() else None


def build_java_plugin(plugin: Plugin, ctx: dict):
    mvn, goals, env = ctx["mvn"], ctx["goals"], ctx["env"]
    base = [mvn, "-B", "-f", str(plugin.dir / "pom.xml"), *goals]
    if not ctx["tests"]:
        base.append("-Dmaven.test.skip=true")

    def invoke(extra: list) -> list:
        cmd = base + extra
        log(f"[java] {plugin.pid}: mvn {' '.join(goals)}")
        if ctx["dry_run"]:
            log(f"    $ {' '.join(cmd)}")
            return []
        t0 = time.perf_counter()
        rc, out = run(cmd, cwd=ROOT, env=env, follow=ctx["follow"])
        dt = time.perf_counter() - t0
        if rc != 0:
            # 受限令牌/沙箱环境里 Java 的 user.home 常解析成盘根(mvn 报
            # "Could not create local repository at C:\.m2\repository"),自动兜底重试一次
            if not ctx["follow"] and "Could not create local repository" in (out or ""):
                fallback = ctx["repo_local"] or default_repo_local()
                if fallback:
                    log(f"[java] {plugin.pid}: 本地仓库路径异常,改用 -Dmaven.repo.local={fallback} 重试")
                    rc2, _ = run(base + [f"-Dmaven.repo.local={fallback}"], cwd=ROOT, env=env, follow=ctx["follow"])
                    if rc2 == 0:
                        ctx["repo_local"] = fallback  # 后续插件直接用,不再逐个试错
                        log(f"[java] {plugin.pid}: ✓ {time.perf_counter() - t0:.1f}s(已带 repo.local)")
                        return []
            log(f"[java] {plugin.pid}: ✗ 失败(退出码 {rc}, {dt:.1f}s)")
            return [f"mvn 退出码 {rc}"]
        log(f"[java] {plugin.pid}: ✓ {dt:.1f}s")
        return []

    extra = [f"-Dmaven.repo.local={ctx['repo_local']}"] if ctx["repo_local"] else []
    return invoke(extra)


# ---------------------------------------------------------------------------
# 产物校验(按 BuiltInPluginScanner 的判定口径)
# ---------------------------------------------------------------------------
def target_jars(plugin: Plugin) -> list:
    tdir = plugin.dir / "target"
    if not tdir.is_dir():
        return []
    return sorted(
        f.name for f in tdir.glob("*.jar") if f.name.endswith(".jar") and not f.name.endswith(JAR_EXCLUDE_SUFFIXES)
    )


def check_plugin(plugin: Plugin) -> list:
    """返回问题列表,空列表即产物完整。"""
    problems = []
    if plugin.has_pom:
        if not plugin.manifest.is_file():
            problems.append("缺 target/classes/plugin.json(mvn 未构建)")
        jars = target_jars(plugin)
        if not jars:
            problems.append("缺插件 jar(target/ 下无非 sources/javadoc 的 jar)")
        elif len(jars) > 1:
            problems.append(f"target/ 残留 {len(jars)} 个 jar,字典序会遮蔽新类: {', '.join(jars)}")
    if plugin.has_web:
        if not plugin.bundle.is_file():
            problems.append("缺 web/index.js(esbuild 未构建)")
        elif plugin.bundle.stat().st_mtime < plugin.bundle_entry.stat().st_mtime:
            problems.append("web/index.js 早于 index.ts(bundle 过期)")
    return problems


def print_table(plugins: list) -> None:
    log(f"{'插件':<26}{'类型':<10}产物状态")
    log("-" * 78)
    bad = 0
    for p in plugins:
        problems = check_plugin(p)
        bad += bool(problems)
        log(f"{p.pid:<26}{p.kind:<10}{'OK' if not problems else '✗ ' + '; '.join(problems)}")
    log("-" * 78)
    log(f"合计 {len(plugins)} 个插件(java 插件 {sum(1 for p in plugins if p.has_pom)} · web 插件 {sum(1 for p in plugins if p.has_web)}) · 异常 {bad}")


# ---------------------------------------------------------------------------
def parse_args():
    parser = argparse.ArgumentParser(
        description="一条命令重建全部内置插件:先 esbuild 出 web bundle,再逐插件 mvn clean package。",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "示例:\n"
            "  python scripts/build-plugins.py                 # 全量重建\n"
            "  python scripts/build-plugins.py --list           # 查看清单与产物状态\n"
            "  python scripts/build-plugins.py --check          # 只校验不构建\n"
            "  python scripts/build-plugins.py --only pdf-viewer --dry-run\n"
        ),
    )
    parser.add_argument("--list", action="store_true", help="仅列出插件清单与产物状态,不构建")
    parser.add_argument("--check", action="store_true", help="仅校验产物完整性,不构建(有缺项退出码 1)")
    parser.add_argument("--only", default="", help="仅处理这些插件 id(逗号分隔)")
    parser.add_argument("--exclude", default="", help="排除这些插件 id(逗号分隔)")
    parser.add_argument("--java-only", action="store_true", help="只重建插件 jar(跳过 esbuild)")
    parser.add_argument("--web-only", action="store_true", help="只重建 web bundle(跳过 maven)")
    parser.add_argument("--goals", default="clean package", help='maven goals,默认 "clean package"(clean 防旧 jar 遮蔽)')
    parser.add_argument("--tests", action="store_true", help="不跳过测试(默认 -Dmaven.test.skip=true)")
    parser.add_argument("--fail-fast", action="store_true", help="任一插件失败即中止(默认继续跑完并汇总)")
    parser.add_argument("--follow", action="store_true", help="实时输出 maven/esbuild 日志(默认捕获,失败才回显尾部)")
    parser.add_argument("--dry-run", action="store_true", help="只打印将执行的命令")
    parser.add_argument("--maven", help="mvn 可执行文件(默认按 DEPLOY_MVN/MAVEN_HOME/PATH 解析)")
    parser.add_argument("--java-home", help="JDK 25 home(默认 DEPLOY_JAVA_HOME > JAVA_HOME)")
    parser.add_argument("--repo-local", help="maven 本地仓库路径(默认交由 mvn 自解析;报 "
                                             "Could not create local repository 时会自动兜底为本机 ~/.m2/repository)")
    return parser.parse_args()


def main():
    args = parse_args()
    split = lambda s: [x.strip() for x in s.split(",") if x.strip()]
    plugins = filter_plugins(discover_plugins(), split(args.only), split(args.exclude), args.java_only, args.web_only)
    if not plugins:
        die("没有匹配到任何插件")

    if args.list or args.check:
        print_table(plugins)
        if args.check:
            bad = [p.pid for p in plugins if check_plugin(p)]
            if bad:
                log(f"\n校验失败: {len(bad)} 个插件产物不完整 → {', '.join(bad)}")
                sys.exit(1)
            log("\n校验通过: 全部插件产物完整。")
        return

    goals = args.goals.split()
    if "clean" not in goals:
        log(f"警告: goals 不含 clean,残留旧 jar 会遮蔽新类(当前: {args.goals!r})")

    mvn = resolve_maven(args.maven)
    node = resolve_node()
    java_home = resolve_java_home(args.java_home) if not args.web_only else None
    if not args.web_only and not java_home and not args.dry_run:
        die("找不到 JDK 25:请用 --java-home 或设置 JAVA_HOME/DEPLOY_JAVA_HOME")

    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
        env["PATH"] = os.path.join(java_home, "bin") + os.pathsep + env.get("PATH", "")
    ctx = {
        "mvn": mvn,
        "goals": goals,
        "env": env,
        "tests": args.tests,
        "dry_run": args.dry_run,
        "follow": args.follow,
        "repo_local": args.repo_local or os.environ.get("MAVEN_REPO_LOCAL") or "",
    }

    log(f"工具链: mvn={mvn}  node={node}  java_home={java_home or '(沿用环境)'}")
    log(f"目标: {len(plugins)} 个插件 · goals={args.goals} · tests={'on' if args.tests else 'off'}")
    if not args.java_only:
        log("提示: 若有 worker 正在运行,请先停止——被锁定的旧 jar 会导致 clean 失败。\n")

    t0 = time.perf_counter()
    failures = []

    # 顺序固定:bundle 先,maven 才会把 web/index.js 拷进 target/classes 打进 jar
    if not args.java_only:
        if not build_web_bundles(plugins, node, args.dry_run, args.follow):
            failures.append(("web", "esbuild 失败"))
            if args.fail_fast:
                report(failures, t0, args)
                return

    if not args.web_only:
        for p in plugins:
            if not p.has_pom:
                continue
            errs = build_java_plugin(p, ctx)
            if errs:
                failures.append((p.pid, errs[0]))
                if args.fail_fast:
                    break

    report(failures, t0, args)

    if not args.dry_run:
        log("\n—— 产物校验 ——")
        print_table(plugins)


def report(failures: list, t0: float, args) -> None:
    dt = time.perf_counter() - t0
    if failures:
        log(f"\n✗ 失败 {len(failures)} 项(耗时 {dt:.1f}s):")
        for pid, why in failures:
            log(f"  - {pid}: {why}")
        if not args.dry_run:
            log("常见原因:旧 jar 被运行中的 worker 锁定(clean 删不掉)→ 先停 worker;或 JDK 非 25。")
        sys.exit(1)
    log(f"\n✓ 重建完成(耗时 {dt:.1f}s){' [dry-run]' if args.dry_run else ''}")


if __name__ == "__main__":
    main()
