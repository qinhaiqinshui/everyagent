#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Every Agent 一键部署脚本（本地编译产物 + SSH 上传 + 远端运行）。

三个服务可单独或组合部署：hub / worker / webapp。

两种远端运行模式（--mode）：
  host   （默认）直接在宿主机运行：java -jar 跑 hub/worker（systemd 常驻），
          前端静态 dist 由服务器已有的 nginx 托管（接管 80 默认站点）。
          不再需要 docker，资源占用低。
  docker 旧模式：上传产物 + 运行时 docker-compose.yml，服务器用官方镜像挂载运行。

本地都先编译：maven 打 hub/worker 可运行 jar，npm 打 webapp 的 dist；
再把产物上传到服务器（默认 ~/every-agent/releases/<ts>，软链 current）。

依赖：paramiko   （pip install paramiko；--list / --dry-run 不需要）
本地前置：能跑 mvn（Java 25）与 npm（Node 22）以产出 jar / dist
服务器前置（host 模式）：nginx（已装）、systemd；Java 25 需可用——可手动预装
          openjdk-25-jre-headless，或由脚本在缺失时 apt 安装（幂等，已装则跳过）。
          hub 端口 9100 由 nginx 反代（/hub/）对外暴露，防火墙只需开 80（默认）。需 sudo（建议免密）。

示例：
  # 宿主部署全部（首次自动装 Java 25、建 systemd 服务、接管 nginx 80）

  # 只部署 worker（--no-deps 不连带拉起依赖的 hub）
  python deploy.py worker --no-deps --host 1.2.3.4 --user root -k ~/.ssh/id_rsa

  # 预览：不构建、不上传、不连服务器
  python deploy.py all --dry-run

  # 让 worker 工作区选择落在宿主真实目录
  python deploy.py worker --workspace-root /srv/everyagent/workspaces --host 1.2.3.4 -k ~/.ssh/id_rsa

  # 部署期默认值写在仓库外的本地配置文件中（含 apikey/HUB_KEY，不随仓库走），
  # 运行时用 --config 指定完整路径，文件名不限：
  #   python deploy.py all --config C:\\Users\\me\\.everyagent\\deploy.env
  # 或：
  #   python deploy.py all --config ~/.everyagent/deploy.env
  # 文件内容形如：
  #   HUB_KEY=你的hub密钥                        # 原文；hub/worker 启动时自算 sha256
  #   WORKSPACE_ROOT=/srv/everyagent/workspaces
  #   DEPLOY_HOST=1.2.3.4
  #   DEPLOY_USER=ubuntu
  #   DEPLOY_KEY=~/.ssh/id_rsa
  # 未指定 --config 时不读取任何配置文件，直接回退到 CLI 参数 / 环境变量 / 硬默认值。
  # 若要上传为远端运行期 current/.env，用 --env-file 指定即可（--config 只做部署期
  # 参数来源，二者互不影响）。
"""
from __future__ import annotations

import argparse
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import time
from datetime import datetime
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent
DEPLOYABLE = ["hub", "worker", "webapp"]


def load_env_file(path: Path) -> dict:
    """解析简单的 KEY=VALUE 配置文件（忽略空行与 # 注释，去除首尾引号）。
    用于从本地配置文件（--config 指定，如仓库外的 deploy.env）读取部署期默认值；
    也用于解析 --env-file 指定、将上传为远端运行期 .env 的文件。"""
    out = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        if "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def resolve_repo_path(arg: str) -> Path:
    """把可能为相对路径的文件参数解析为绝对路径（相对路径按脚本目录/仓库根解析，
    保证从任意 cwd 运行也能定位；支持 ~ 展开，也可传绝对路径指向仓库外任意位置）。"""
    p = Path(arg).expanduser()
    return p if p.is_absolute() else REPO_ROOT / p


# 运行时使用的官方基础镜像（如需固定版本号，改这里即可）
HUB_IMAGE = "eclipse-temurin:25-jre"
WORKER_IMAGE = "eclipse-temurin:25-jre"
WEB_IMAGE = "nginx:alpine"

# 服务器端 docker-compose：官方镜像 + 挂载本地编译产物，不构建自定义镜像。
# 三个服务始终定义齐全；只部署部分时，未部署的服务不会被启动（其挂载路径无需存在）。
# worker 通过 bind mount 把「宿主工作区根」挂到容器内相同绝对路径，
# 这样 worker 的目录选择 / fs 操作直接落在宿主真实目录上（由 WORKSPACE_ROOT 控制）。

def render_compose(cfg) -> str:
    """生成运行时 compose：官方镜像 + 挂载本地产物。
    WORKSPACE_ROOT 非空时，把该宿主绝对路径「同路径」挂入 worker 容器，
    并写入同名环境变量，使 worker 默认工作区指向宿主真实目录。"""
    ws = (getattr(cfg, "workspace_root", "") or "").strip()
    hub_key = getattr(cfg, "hub_key", "") or ""
    web_port = getattr(cfg, "web_port", 80) or 80
    worker_volumes = [
        "      - ./worker/app.jar:/app/app.jar:ro",
        "      - worker-home:/everyagent",
    ]
    worker_id = getattr(cfg, "worker_id", "company-pc") or "company-pc"
    worker_env = [
        f'      WORKER_ID: "{worker_id}"',
        "      EVERYAGENT_HOME: /everyagent",
        # 原文透传:hub/worker 启动时自算 sha256(worker.yml 两条 hub 条目分别读
        # HUB_KEY / REMOTE_HUB_KEY,同一台 hub 时填同值)
        f'      HUB_KEY: "{hub_key}"',
        f'      REMOTE_HUB_KEY: "{hub_key}"',
    ]
    if ws:
        if not ws.startswith("/"):
            raise ValueError(f"WORKSPACE_ROOT 必须是绝对路径，当前为: {ws}")
        q = ws.replace('"', '\\"')
        worker_volumes.append(f'      - "{q}:{q}"')
        worker_env.append(f'      WORKSPACE_ROOT: "{q}"')
    return f"""\
# 由 deploy.py 自动生成：官方镜像 + 挂载本地编译产物，不构建自定义镜像。
services:
  hub:
    image: {HUB_IMAGE}
    container_name: everyagent-hub
    command: ["java", "-jar", "/app/app.jar"]
    volumes:
      - ./hub/app.jar:/app/app.jar:ro
    ports:
      - "9100:9100"
    environment:
      HUB_KEY: "{hub_key}"
    restart: unless-stopped

  worker:
    image: {WORKER_IMAGE}
    container_name: everyagent-worker
    command: ["java", "-jar", "/app/app.jar"]
    volumes:
{"\n".join(worker_volumes)}
    environment:
{"\n".join(worker_env)}
    restart: unless-stopped
    depends_on:
      - hub

  webapp:
    image: {WEB_IMAGE}
    container_name: everyagent-webapp
    volumes:
      - ./webapp/dist:/usr/share/nginx/html:ro
    ports:
      - "{web_port}:80"
    restart: unless-stopped
    depends_on:
      - hub

volumes:
  worker-home:
"""


# ------------------- 宿主机模式渲染（systemd + nginx + .env） -------------------
# host 模式下远端目录布局（current 为 releases/<ts> 的软链，便于回滚）：
#   <remote>/current/hub/app.jar
#   <remote>/current/worker/app.jar
#   <remote>/current/webapp/dist/
#   <remote>/current/systemd/everyagent-{hub,worker}.service
#   <remote>/current/nginx/everyagent.conf
#   <remote>/current/.env        # 运行期环境变量（HUB_KEY 为原文，hub/worker 启动自算 sha256）

def render_hub_service(cfg, java_bin: str) -> str:
    cur = f"{cfg.remote_dir.rstrip('/')}/current"
    return f"""\
[Unit]
Description=Every Agent Hub
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
WorkingDirectory={cur}/hub
ExecStart={java_bin} -jar {cur}/hub/app.jar
EnvironmentFile={cur}/.env
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
"""


def render_worker_service(cfg, java_bin: str) -> str:
    cur = f"{cfg.remote_dir.rstrip('/')}/current"
    return f"""\
[Unit]
Description=Every Agent Worker
After=network-online.target everyagent-hub.service
Wants=network-online.target
Requires=everyagent-hub.service

[Service]
Type=simple
WorkingDirectory={cur}/worker
ExecStart={java_bin} -jar {cur}/worker/app.jar
EnvironmentFile={cur}/.env
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
"""


def render_nginx_site(cfg) -> str:
    cur = f"{cfg.remote_dir.rstrip('/')}/current"
    port = getattr(cfg, "web_port", 80) or 80
    return f"""\
server {{
    listen {port} default_server;
    listen [::]:{port} default_server;
    server_name _;
    root {cur}/webapp/dist;
    index index.html;

    # hub 反向代理：浏览器/webapp 经 nginx(:80) 访问 hub(:9100)，
    # 内部 worker 仍直连 localhost:9100（不依赖 nginx）。
    # /hub/ws   -> hub 的 /ws（WebSocket 升级）
    # /hub/api/ -> hub 的 /api/...
    location /hub/ {{
        proxy_pass http://127.0.0.1:9100/;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
    }}

    location / {{
        try_files $uri $uri/ /index.html;
    }}
}}
"""


def render_runtime_env_file(cfg) -> str:
    """生成远端运行期 .env（HUB_KEY 原文,hub/worker 启动自算 sha256;REMOTE_HUB_KEY
    供 worker.yml 第二条 hub 条目用,同一台 hub 时与 HUB_KEY 同值）。"""
    ws = (getattr(cfg, "workspace_root", "") or "").strip()
    hub_key = getattr(cfg, "hub_key", "") or ""
    lines = [
        f"HUB_KEY={hub_key}",
        f"REMOTE_HUB_KEY={hub_key}",
        f"WORKER_ID={getattr(cfg, 'worker_id', 'company-pc') or 'company-pc'}",
        "EVERYAGENT_HOME=/var/lib/everyagent",
    ]
    if ws:
        lines.append(f"WORKSPACE_ROOT={ws}")
    return "\n".join(lines) + "\n"


def host_deploy_command(cfg, services, ts: str) -> str:
    """宿主机模式远端执行脚本：若缺失则装 Java25(幂等) → 装 systemd 单元 → 接管 nginx 80。
    hub 由 nginx 的 /hub/ 反代对外暴露，worker 仍直连 localhost:9100。
    sudo 由 --sudo / DEPLOY_SUDO 控制（root 直连时留空，免密 sudo 时加 sudo -n）。"""
    rd = cfg.remote_dir.rstrip("/")
    cur = f"{rd}/current"
    sudo = "sudo -n" if getattr(cfg, "sudo", False) else ""
    java_check = (
        f'if ! java -version 2>&1 | grep -q " 25"; then '
        f'{sudo} apt-get update && {sudo} apt-get install -y openjdk-25-jre-headless; fi'
    )
    lines = ["set -e", java_check]
    if "hub" in services:
        lines.append(f"{sudo} cp {cur}/systemd/everyagent-hub.service /etc/systemd/system/")
    if "worker" in services:
        lines.append(f"{sudo} cp {cur}/systemd/everyagent-worker.service /etc/systemd/system/")
        lines.append(f"{sudo} mkdir -p /var/lib/everyagent")
    lines.append(f"{sudo} systemctl daemon-reload")
    if "hub" in services:
        lines.append(f"{sudo} systemctl enable everyagent-hub")
        lines.append(f"{sudo} systemctl restart everyagent-hub")
    if "worker" in services:
        lines.append(f"{sudo} systemctl enable everyagent-worker")
        lines.append(f"{sudo} systemctl restart everyagent-worker")
    if "webapp" in services:
        # nginx 缺失时幂等安装并启动（webapp 依赖 nginx 接管 80 默认站点）
        lines.append(
            f'if ! command -v nginx >/dev/null 2>&1; then '
            f'{sudo} apt-get update && {sudo} apt-get install -y nginx; fi'
        )
        lines.append(f"{sudo} systemctl enable --now nginx")
        # every-agent 作为 80 默认站点：禁用其它声明 default_server 的启用站点，
        # 避免重复 default_server 冲突（如老项目 novel-agent 已占用 80 默认）。
        lines.append(
            "for f in /etc/nginx/sites-enabled/*; do "
            "  [ \"$(basename \"$f\")\" = everyagent ] && continue; "
            "  if grep -qE 'listen[^;]*80[^;]*default_server' \"$f\" 2>/dev/null; then "
            f"    echo \"[deploy] 禁用冲突的 nginx 默认站点: $(basename \"$f\")\"; {sudo} rm -f \"$f\"; "
            "  fi; "
            "done"
        )
        lines.append(f"{sudo} cp {cur}/nginx/everyagent.conf /etc/nginx/sites-available/everyagent")
        lines.append(f"{sudo} ln -sf /etc/nginx/sites-available/everyagent /etc/nginx/sites-enabled/everyagent")
        lines.append(f"{sudo} nginx -t")
        lines.append(f"{sudo} systemctl reload nginx")
    # 仅保留最近 N 个 release，避免磁盘无限增长（跳过仍被 current 引用的）
    lines.append(release_prune_command(cfg))
    return "\n".join(lines)


def log(msg: str) -> None:
    ts = datetime.now().strftime("%H:%M:%S")
    line = f"[{ts}] {msg}"
    try:
        print(line, flush=True)
    except UnicodeEncodeError:
        # Windows GBK 控制台无法编码部分字符（如 ✓）：降级为可表示字符
        enc = sys.stdout.encoding or "utf-8"
        print(line.encode(enc, errors="replace").decode(enc), flush=True)


# --------------------------- 本地构建 ---------------------------
def run_local(cmd: str, cwd: Path, env: dict | None = None,
              retries: int = 2, retry_delay: float = 4.0) -> None:
    """执行本地命令。失败时带退避重试 retries 次再抛异常。

    Windows 上 npm ci / vite build 常因 esbuild.exe 被残留进程（或杀软扫描）
    瞬时占用而报 EPERM，重试通常可直接绕过这类瞬时锁。
    """
    log(f"本地执行: {cmd}\n  (cwd={cwd})")
    for attempt in range(1, retries + 2):
        rc = subprocess.run(cmd, cwd=str(cwd), shell=True, env=env).returncode
        if rc == 0:
            return
        if attempt <= retries:
            log(f"命令失败 (exit={rc})，{retry_delay:.0f}s 后重试 ({attempt}/{retries}) ...")
            time.sleep(retry_delay)
            continue
        raise RuntimeError(f"本地构建失败 (exit={rc}): {cmd}")


def find_hub_jar() -> Path:
    d = REPO_ROOT / "every-agent-hub" / "target"
    cands = sorted(d.glob("every-agent-hub-*-exec.jar"))
    if not cands:
        raise FileNotFoundError(f"未找到 hub 的 exec jar，请确认本地已执行 mvn package（期望 {d} 下有 *-exec.jar）")
    return cands[-1]


def find_worker_jar() -> Path:
    d = REPO_ROOT / "every-agent-worker" / "target"
    cands = [p for p in d.glob("every-agent-worker-*.jar") if not p.name.endswith(".original")]
    if not cands:
        raise FileNotFoundError(f"未找到 worker 的可运行 jar，请确认本地已执行 mvn package（期望 {d} 下有 *.jar）")
    return sorted(cands)[-1]


def build_artifacts(cfg, services) -> Path:
    staging = Path(tempfile.mkdtemp(prefix="eadeploy-"))
    log(f"本地构建产物到临时目录: {staging}")

    java = [s for s in services if s in ("hub", "worker")]
    if java:
        mods = ",".join(f"every-agent-{s}" for s in java)
        # worker/hub 用 release 25 编译，必须 JDK 25。强制把构建进程的
        # JAVA_HOME 指向 cfg.java_home（默认 Corretto 25），不受本机 JAVA_HOME 影响。
        java_env = dict(os.environ)
        java_env["JAVA_HOME"] = cfg.java_home
        java_env["PATH"] = cfg.java_home + os.sep + "bin" + os.pathsep + java_env.get("PATH", "")
        log(f"构建使用 JDK: {cfg.java_home}")
        run_local(f"{cfg.mvn} -B -pl {mods} -am package -Dmaven.test.skip=true", REPO_ROOT, env=java_env)

    if "webapp" in services:
        web = REPO_ROOT / "every-agent-web"
        # 不执行 npm ci / npm install，直接使用现有的 node_modules 执行打包
        run_local(f"{cfg.npm} run build", web, retries=2, retry_delay=5.0)

    if "hub" in services:
        d = staging / "hub"
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy(find_hub_jar(), d / "app.jar")
        log("已暂存 hub 产物 -> hub/app.jar")
    if "worker" in services:
        d = staging / "worker"
        d.mkdir(parents=True, exist_ok=True)
        shutil.copy(find_worker_jar(), d / "app.jar")
        log("已暂存 worker 产物 -> worker/app.jar")
    if "webapp" in services:
        src = REPO_ROOT / "every-agent-web" / "dist"
        if not src.is_dir():
            raise FileNotFoundError(f"未找到前端构建产物: {src}（npm run build 应已生成 dist/）")
        shutil.copytree(src, staging / "webapp" / "dist")
        log("已暂存 webapp 产物 -> webapp/dist")

    java_bin = getattr(cfg, "java_bin", "/usr/bin/java") or "/usr/bin/java"
    if getattr(cfg, "mode", "host") == "docker":
        (staging / "docker-compose.yml").write_text(render_compose(cfg), encoding="utf-8")
    else:
        # host 模式：生成 systemd 单元、nginx 站点、运行期 .env，随产物一起上传
        sd = staging / "systemd"
        sd.mkdir(parents=True, exist_ok=True)
        (sd / "everyagent-hub.service").write_text(render_hub_service(cfg, java_bin), encoding="utf-8")
        (sd / "everyagent-worker.service").write_text(render_worker_service(cfg, java_bin), encoding="utf-8")
        ng = staging / "nginx"
        ng.mkdir(parents=True, exist_ok=True)
        (ng / "everyagent.conf").write_text(render_nginx_site(cfg), encoding="utf-8")
        (staging / ".env").write_text(render_runtime_env_file(cfg), encoding="utf-8")
    return staging


def make_archive(staging: Path) -> Path:
    tmp = Path(tempfile.gettempdir()) / f"every-agent-deploy-{datetime.now():%Y%m%d%H%M%S}.tar.gz"
    files = [(p, p.relative_to(staging).as_posix()) for p in staging.rglob("*") if p.is_file()]
    total = sum(f.stat().st_size for f, _ in files)
    log(f"打包中: 共 {len(files)} 个文件, 约 {total / 1024 / 1024:.1f} MB ...")
    with tarfile.open(tmp, "w:gz", compresslevel=6) as tf:
        for path, rel in files:
            tf.add(str(path), arcname=rel)
    log(f"归档完成: {tmp.name} ({tmp.stat().st_size / 1024 / 1024:.1f} MB)")
    return tmp


# ----------------------------- SSH -----------------------------
def connect(cfg):
    try:
        import paramiko
    except ImportError:
        sys.exit("缺少依赖 paramiko，请先执行: pip install paramiko")
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    kwargs = dict(hostname=cfg.host, port=cfg.port, username=cfg.user, timeout=30)
    if cfg.password:
        kwargs["password"] = cfg.password
    if cfg.key_file:
        kwargs["key_filename"] = cfg.key_file
    log(f"连接 {cfg.user}@{cfg.host}:{cfg.port} ...")
    client.connect(**kwargs)
    return client


def remote_exec(client, cmd: str) -> int:
    log("远端执行:\n  " + cmd.replace("\n", "\n  "))
    stdin, stdout, stderr = client.exec_command(cmd, get_pty=True)
    for line in stdout:
        print("  " + line.rstrip("\n"), flush=True)
    return stdout.channel.recv_exit_status()


def resolve_remote_dir(client, remote_dir: str) -> str:
    """把远端目录里的 ~ 展开成服务器登录用户的家目录（SFTP 不会自动展开 ~）。"""
    rd = (remote_dir or "").strip()
    if rd.startswith("~"):
        home = ""
        try:
            stdin, stdout, _ = client.exec_command("echo $HOME")
            home = stdout.read().decode("utf-8", "replace").strip()
        except Exception:
            home = ""
        if not home:
            home = "/root"
        rd = home + rd[1:]
    return rd.rstrip("/")


def ensure_privilege(cfg, client) -> None:
    """host 模式的部署需要 root：要写 /etc/systemd/system、起停 systemd 服务、
    装 nginx、接管 nginx 80 站点。连接后尽早校验权限，避免构建/上传完成后
    才在远端系统操作步骤失败（ubuntu 等非 root 用户无免密 sudo 时，systemctl
    会触发 polkit 交互认证，SSH 非交互会话必然失败）。

    - 当前用户是 root（id -u == 0）：直接放行。
    - 非 root 且指定了 --sudo / DEPLOY_SUDO：校验 sudo -n（免密）可用，可用则放行。
    - 非 root 且未指定 --sudo：立刻报错，给出两种解法，而不是等到远端才失败。
    docker 模式不操作系统服务（只需远程目录可写 + docker 权限），不强制检查。
    """
    if getattr(cfg, "mode", "host") != "host":
        return
    try:
        _, out, _ = client.exec_command("id -u")
        uid = out.read().decode("utf-8", "replace").strip()
    except Exception:
        uid = ""
    if uid == "0":
        return
    if getattr(cfg, "sudo", False):
        rc = remote_exec(client, "sudo -n true")
        if rc == 0:
            return
        raise RuntimeError(
            "host 模式部署需要 root 权限：当前登录用户非 root，且免密 sudo 不可用"
            "（sudo -n true 失败）。\n"
            "  请在服务器为当前用户配置免密 sudo（visudo 添加 NOPASSWD），详见 deploy.py 顶部说明，"
            "或改用 --user root 连接。"
        )
    raise RuntimeError(
        "host 模式部署需要 root 权限：当前登录用户非 root，且未加 --sudo。\n"
        "  请加 --sudo（要求该用户已配置免密 sudo），或改用 --user root 连接。"
        "示例：python deploy.py all --host ... --user ubuntu --sudo -k ~/.ssh/id_rsa"
    )


def sftp_put(client, local: Path, remote: str) -> None:
    sftp = client.open_sftp()
    try:
        parent = remote.rsplit("/", 1)[0]
        if parent:
            # 用 SSH 建父目录：比 SFTP 自带的 mkdir 可靠，且能暴露权限错误
            rc = remote_exec(client, f"mkdir -p {parent}")
            if rc != 0:
                raise RuntimeError(
                    f"无法在服务器创建目录: {parent}（mkdir -p 返回 {rc}）。\n"
                    f"  该路径对登录用户不可写。请改用可写路径（如 ~/every-agent），"
                    f"或用 DEPLOY_REMOTE_DIR / --remote-dir 指定其它有权限的目录。"
                )
        size = local.stat().st_size
        log(f"上传 {local.name} -> {remote} ({size / 1024 / 1024:.1f} MB)")
        last = {"p": -1}

        def cb(x, y):
            if y > 0:
                p = x * 100 // y
                if p != last["p"] and p % 10 == 0:
                    log(f"  上传进度 {p}%")
                    last["p"] = p

        sftp.put(str(local), remote, callback=cb)
    finally:
        sftp.close()


# --------------------------- 命令构造 ---------------------------
def extract_command(cfg, services, ts: str) -> str:
    rd = cfg.remote_dir.rstrip("/")
    rel = f"{rd}/releases/{ts}"
    cur = f"{rd}/current"
    sudo = "sudo -n " if getattr(cfg, "sudo", False) else ""
    lines = [
        f"mkdir -p {rel}",
        f"tar -xzf {rd}/_upload.tar.gz -C {rel}",
        f"rm -f {rd}/_upload.tar.gz",
    ]
    # 新 release 只含本次部署的服务；把「未部署」的服务从旧 current 以软链继承过来，
    # 保证 current（三个服务共用的快照）始终完整：部署单个服务不会让其它服务的
    # jar/dist 路径消失（否则 hub 一重启就挂、nginx root 指向空目录等）。
    lines.append("for svc in hub worker webapp; do")
    lines.append(f'  if [ ! -e "{rel}/$svc" ] && [ -e "{cur}/$svc" ]; then')
    lines.append(f'    ln -s "$(readlink -f "{cur}/$svc")" "{rel}/$svc"')
    lines.append("  fi")
    lines.append("done")
    lines.append(f"rm -rf {cur}; ln -sfn {rel} {cur}")
    if "webapp" in services:
        # nginx 以 www-data 运行，站点根在用户 home 下时需保证其对整条路径
        # 有遍历(x)权限、对 dist 静态文件有读(r)权限，否则 stat 报 13: Permission denied。
        lines.append(f'{sudo}chmod o+x "$HOME" 2>/dev/null || true')
        lines.append(f'{sudo}chmod o+x {rd} {rd}/releases {cur} 2>/dev/null || true')
        lines.append(f'{sudo}chmod o+x {rd}/releases/* 2>/dev/null || true')
        lines.append(f'{sudo}chmod -R o+rX {cur}/webapp 2>/dev/null || true')
    return "\n".join(lines)


def deploy_command(cfg, services, ts: str) -> str:
    rd = cfg.remote_dir.rstrip("/")
    cur = f"{rd}/current"
    svcs = " ".join(services)
    nodeps = "--no-deps " if cfg.no_deps else ""
    pull = "--pull " if cfg.pull else ""
    cb = getattr(cfg, "compose", "docker compose") or "docker compose"
    sudo = "sudo -n " if getattr(cfg, "sudo", False) else ""
    lines = [f"cd {cur}"]
    lines.append(f"{sudo}{cb} up -d {pull}{nodeps}{svcs}")
    if cfg.prune:
        lines.append(f"{sudo}{cb} image prune -f")
    # 仅保留最近 N 个 release，避免磁盘无限增长（跳过仍被 current 引用的）
    lines.append(release_prune_command(cfg))
    return "\n".join(lines)


def release_prune_command(cfg) -> str:
    """清理旧 release：只删「不再被 current 引用」的 release。

    每次部署产出的 release 只含本次服务，未部署的服务从旧 current 软链继承；
    若按时间一刀切删除，可能误删仍被 current/* 软链（或 current 内目录）引用的
    release，导致继承出来的服务路径失效。这里先收集 current 下所有仍被引用的
    release（readlink 解析后取所属 release），删除时跳过它们。
    """
    rd = cfg.remote_dir.rstrip("/")
    cur = f"{rd}/current"
    keep = cfg.keep_releases
    protect = (
        'protect=""\n'
        f"for p in {cur}/hub {cur}/worker {cur}/webapp {cur}/systemd {cur}/nginx {cur}/.env; do\n"
        '  t=$(readlink -f "$p" 2>/dev/null) || continue\n'
        '  case "$t" in\n'
        f"    {rd}/releases/*) d=${{t#{rd}/releases/}}; d=${{d%%/*}}; "
        f'protect="$protect {rd}/releases/$d" ;;\n'
        "  esac\n"
        "done\n"
    )
    prune = (
        f"for r in $(ls -dt {rd}/releases/* 2>/dev/null | tail -n +{keep + 1}); do\n"
        '  case " $protect " in *" $r "*) ;; *) rm -rf "$r" ;; esac\n'
        "done\n"
    )
    return protect + prune


# ----------------------------- 部署 -----------------------------
def deploy(cfg, services) -> None:
    mode = getattr(cfg, "mode", "host")
    ws = (getattr(cfg, "workspace_root", "") or "").strip()
    if "worker" in services and not ws:
        if mode == "docker":
            log("[警告] 未设置 WORKSPACE_ROOT（--workspace-root / DEPLOY_WORKSPACE_ROOT），"
                "worker 将使用容器内默认工作区，且无法访问宿主目录（除非改用同路径 bind mount）。")
        else:
            log("[警告] 未设置 WORKSPACE_ROOT（--workspace-root / DEPLOY_WORKSPACE_ROOT），"
                "worker 将使用 EVERYAGENT_HOME 默认工作区（/var/lib/everyagent），无法访问你指定的宿主目录。")
    if cfg.dry_run:
        log("== DRY RUN: 仅本地预览, 不执行本地构建、不连接服务器 ==")
        print("\n---- 本地将执行的构建命令 ----")
        java = [s for s in services if s in ("hub", "worker")]
        if java:
            mods = ",".join(f"every-agent-{s}" for s in java)
            print(f"  mvn -B -pl {mods} -am package -Dmaven.test.skip=true")
        if "webapp" in services:
            print("  (cd every-agent-web && npm run build)  # 不执行 npm install，直接打包")
        print("\n---- 上传内容 ----")
        for s in services:
            if s == "hub":
                print("  hub/app.jar")
            elif s == "worker":
                print("  worker/app.jar")
            elif s == "webapp":
                print("  webapp/dist/")
        mode = getattr(cfg, "mode", "host")
        if mode == "docker":
            print("  docker-compose.yml  (官方镜像 + 挂载)")
        else:
            print("  systemd/everyagent-hub.service")
            print("  systemd/everyagent-worker.service")
            print("  nginx/everyagent.conf")
            print("  .env  (HUB_KEY 原文,hub/worker 启动自算 sha256)")
        print("\n---- 远端将执行的命令 ----")
        ts = "<timestamp>"
        print(extract_command(cfg, services, ts))
        print(deploy_command(cfg, services, ts) if mode == "docker" else host_deploy_command(cfg, services, ts))
        print("--------------------------------\n")
        log(f"DRY RUN 完成。服务: {services}; 远端目录: {cfg.remote_dir}")
        return

    staging = None
    archive = None
    client = connect(cfg)
    try:
        # 先连服务器、把 ~ 展开成绝对路径，再渲染 systemd/nginx 单元文件
        # （systemd/nginx 都不展开 ~，单元文件路径必须是绝对路径，否则服务起不来）
        cfg.remote_dir = resolve_remote_dir(client, cfg.remote_dir)
        # 提前校验 host 模式所需 root/免密 sudo，避免构建、上传完成后才在系统操作步骤失败
        ensure_privilege(cfg, client)
        staging = build_artifacts(cfg, services)
        archive = make_archive(staging)
        ts = datetime.now().strftime("%Y%m%d%H%M%S")
        sftp_put(client, archive, f"{cfg.remote_dir.rstrip('/')}/_upload.tar.gz")

        rc = remote_exec(client, extract_command(cfg, services, ts))
        if rc != 0:
            raise RuntimeError(f"解包/软链失败 (exit={rc})")

        if getattr(cfg, "mode", "host") == "docker":
            if cfg.env_file:
                envp = Path(cfg.env_file)
                if not envp.exists():
                    log(f"警告: --env-file 指定文件不存在: {envp}")
                else:
                    # 原样上传(HUB_KEY 为原文,hub 启动时自算 sha256,脚本不做变换)
                    sftp_put(client, envp, f"{cfg.remote_dir.rstrip('/')}/current/.env")
            rc = remote_exec(client, deploy_command(cfg, services, ts))
        else:
            # host 模式的 .env 已随归档上传（render_runtime_env_file 生成,HUB_KEY 原文）
            rc = remote_exec(client, host_deploy_command(cfg, services, ts))
        if rc != 0:
            raise RuntimeError(f"部署失败 (exit={rc})")

        log("部署完成 [OK]")
        log(f"服务: {services}")
        log(f"远端目录: {cfg.remote_dir}/current")
    finally:
        client.close()
        if archive is not None:
            archive.unlink(missing_ok=True)
        if staging is not None:
            shutil.rmtree(staging, ignore_errors=True)


# ----------------------------- CLI -----------------------------
def parse_args(argv):
    p = argparse.ArgumentParser(
        description="Every Agent 一键部署（本地编译产物 + 远端运行；host 模式直跑 / docker 挂载）",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("services", nargs="*", help="要部署的服务: hub / worker / webapp，或 all（默认 all）")
    p.add_argument("--mode", choices=["host", "docker"], default=None,
                   help="远端运行模式（默认 host）：host=直接在宿主机跑 systemd+nginx（省 docker，资源占用低）；"
                        "docker=旧模式，上传产物+运行时 docker-compose.yml，服务器用官方镜像挂载运行")
    p.add_argument("--host", help="服务器地址（或环境变量 DEPLOY_HOST）")
    p.add_argument("--port", type=int, default=None, help="SSH 端口（默认 22 / DEPLOY_PORT）")
    p.add_argument("--web-port", type=int, default=None, help="前端(webapp)映射到的宿主端口（默认 80 / DEPLOY_WEB_PORT；容器内固定 80）")
    p.add_argument("--compose", help="compose 命令（默认 'docker compose' / DEPLOY_COMPOSE；若服务器只有 v1 则填 'docker-compose'）")
    p.add_argument("--sudo", action="store_true", help="远端 docker 命令前加 'sudo -n'（ubuntu 不在 docker 组时使用）")
    p.add_argument("--user", help="SSH 用户（或环境变量 DEPLOY_USER）")
    p.add_argument("-k", "--key-file", help="SSH 私钥路径（或环境变量 DEPLOY_KEY）")
    p.add_argument("--password", help="SSH 密码（不推荐，优先用密钥）")
    p.add_argument("--remote-dir", help="远端部署根目录（默认 ~/every-agent，即登录用户家目录下；或 DEPLOY_REMOTE_DIR）")
    p.add_argument("--env-file", help="要上传为远端 current/.env 的本地环境变量文件（相对路径按脚本目录解析）")
    p.add_argument("--config", default=None,
                   help="部署期默认配置来源文件位置（可指向仓库外任意路径、任意文件名，如 ~/.everyagent/deploy.env；"
                        "相对路径按脚本目录解析；未指定则不读取配置文件，回退到 CLI 参数 / 环境变量 / 硬默认值）")
    p.add_argument("--mvn", help="本地 maven 可执行文件（默认 mvn / DEPLOY_MVN）")
    p.add_argument("--java-home", help="本地构建用的 JDK 根目录（worker/hub 用 release 25，必须 JDK 25；"
                                        "默认 D:\\Program Files\\jdks\\corretto-25.0.4 / DEPLOY_JAVA_HOME / 配置文件 DEPLOY_JAVA_HOME）")
    p.add_argument("--java-bin", help="host 模式下远端运行的 java 可执行文件（默认 /usr/bin/java，即脚本首次部署时"
                                        "apt 安装的 openjdk-25-jre-headless；docker 模式忽略 / DEPLOY_JAVA_BIN）")
    p.add_argument("--npm", help="本地 npm 可执行文件（默认 npm / DEPLOY_NPM）")
    p.add_argument("--workspace-root", help="宿主工作区根(绝对路径)；bind mount 进 worker 使工作区落在宿主真实目录。不填则 worker 用容器内默认目录（DEPLOY_WORKSPACE_ROOT）")
    p.add_argument("--hub-key", help="原始 hub key(必填,hub/worker 启动自算 sha256)；前端设置页与 worker hubs[].hub-key 填同一密钥。也可写在 --config 文件的 HUB_KEY= 一行。")
    p.add_argument("--keep-releases", type=int, default=3, help="保留最近几个 release（默认 3）")
    p.add_argument("--no-deps", action="store_true", help="部署时不连带拉起所依赖的服务")
    p.add_argument("--pull", action="store_true", help="up 时强制拉取最新官方镜像")
    p.add_argument("--prune", action="store_true", help="部署后执行 docker image prune -f")
    p.add_argument("--dry-run", action="store_true", help="仅本地预览，不构建/不上传/不连接服务器")
    p.add_argument("--list", action="store_true", help="列出可部署服务后退出")
    args = p.parse_args(argv)

    if args.list:
        print("可部署服务:", ", ".join(DEPLOYABLE))
        sys.exit(0)

    if not args.services or args.services == ["all"]:
        args.services = list(DEPLOYABLE)
    for s in args.services:
        if s not in DEPLOYABLE:
            p.error(f"未知服务 '{s}'，可选: {DEPLOYABLE}")

    # 配置文件位置：仅当显式指定 --config 时读取；未指定则不读取任何默认配置文件，
    # 直接回退到 CLI 参数 / 环境变量 / 硬默认值。deploy.env 已移出仓库，deploy.py
    # 不再隐式读取仓库内文件。相对路径统一按脚本目录解析；--env-file 同理。
    if args.config:
        cfg_path = resolve_repo_path(args.config)
        args.config = str(cfg_path)
        if not cfg_path.is_file():
            log(f"警告: --config 指定配置文件不存在: {cfg_path}（将回退到 CLI 参数/环境变量/硬默认值）")
    if args.env_file:
        args.env_file = str(resolve_repo_path(args.env_file))

    # 部署期默认值来源：CLI 参数 > 环境变量 > 配置文件(--config，仅显式指定时) > 硬默认
    local = load_env_file(Path(args.config)) if args.config else {}
    args.host = args.host or os.environ.get("DEPLOY_HOST") or local.get("DEPLOY_HOST")
    args.port = args.port or int(os.environ.get("DEPLOY_PORT") or local.get("DEPLOY_PORT", "22"))
    args.user = args.user or os.environ.get("DEPLOY_USER") or local.get("DEPLOY_USER")
    args.key_file = args.key_file or os.environ.get("DEPLOY_KEY") or local.get("DEPLOY_KEY")
    args.remote_dir = args.remote_dir or os.environ.get("DEPLOY_REMOTE_DIR") or local.get("DEPLOY_REMOTE_DIR", "~/every-agent")
    args.mvn = args.mvn or os.environ.get("DEPLOY_MVN") or local.get("DEPLOY_MVN", "mvn")
    args.npm = args.npm or os.environ.get("DEPLOY_NPM") or local.get("DEPLOY_NPM", "npm")
    args.java_home = args.java_home or os.environ.get("DEPLOY_JAVA_HOME") or local.get("DEPLOY_JAVA_HOME", r"D:\Program Files\jdks\corretto-25.0.4")
    args.workspace_root = args.workspace_root or os.environ.get("DEPLOY_WORKSPACE_ROOT") or local.get("WORKSPACE_ROOT", "")
    args.hub_key = args.hub_key or os.environ.get("DEPLOY_HUB_KEY") or local.get("HUB_KEY", "")
    args.web_port = args.web_port or int(os.environ.get("DEPLOY_WEB_PORT") or local.get("DEPLOY_WEB_PORT", "80"))
    args.compose = args.compose or os.environ.get("DEPLOY_COMPOSE") or local.get("DEPLOY_COMPOSE", "docker compose")
    args.mode = args.mode or os.environ.get("DEPLOY_MODE") or local.get("DEPLOY_MODE", "host")
    args.java_bin = args.java_bin or os.environ.get("DEPLOY_JAVA_BIN") or local.get("DEPLOY_JAVA_BIN", "/usr/bin/java")

    def _truthy(v):
        return v is not None and str(v).strip().lower() in ("1", "true", "yes", "y", "on")
    args.sudo = bool(args.sudo) or _truthy(os.environ.get("DEPLOY_SUDO")) or _truthy(local.get("DEPLOY_SUDO"))

    # 运行期变量来源（host 与 docker 共用）：--env-file > --config(deploy.env) > 硬默认。
    runtime = load_env_file(Path(args.env_file)) if args.env_file else {}
    args.worker_id = runtime.get("WORKER_ID") or local.get("WORKER_ID", "company-pc")

    if any(s in args.services for s in ("hub", "worker")) and not (args.hub_key or "").strip():
        p.error("部署 hub/worker 必须提供原始 hub key（--hub-key / DEPLOY_HUB_KEY / 配置文件 HUB_KEY=）；"
                "填原文即可，hub/worker 启动时自算 sha256")

    if not args.dry_run and not args.host:
        p.error("必须提供 --host 或环境变量 DEPLOY_HOST（--dry-run 模式除外）")
    return args


def main():
    args = parse_args(sys.argv[1:])
    log(f"目标服务: {args.services}")
    if args.dry_run:
        log("模式: DRY RUN（不连接服务器）")
    else:
        log(f"服务器: {args.user}@{args.host}:{args.port}  远端目录: {args.remote_dir}")
        log(f"本地工具链: mvn={args.mvn}  npm={args.npm}")
    deploy(args, args.services)


if __name__ == "__main__":
    main()
