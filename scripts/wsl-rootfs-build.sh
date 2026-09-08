#!/usr/bin/env bash
# eagent 托管发行版镜像构建(wsl2-bubblewrap-sandbox-design.md §4.1/§12.3,§4.8 wsl-direct)。
#
# 产物:eagent-rootfs.tar.gz + eagent-rootfs.tar.gz.sha256 —— 放仓库根 runtime/wsl/
# (唯一真源,electron-builder extraResources 打进 <resourcesPath>/runtime/wsl),
# worker 首启探测到发行版缺失即自动 wsl --import eagent(免管理员、离线,构建时把依赖全部烤进镜像)。
#
# 在有 docker 的 Linux/CI 上运行:
#   scripts/wsl-rootfs-build.sh [基础镜像] [输出目录]
#   默认:scripts/wsl-rootfs-build.sh ubuntu:24.04 ./dist
#
# 烤入内容(面向 wsl-direct 后端,§4.8):
#   最小预装集(只装运行时本身需要的):
#     python3       -- 必需:eagent-run.py(runner,ctypes seccomp/process_vm_readv/ioctl)
#     util-linux    -- 必需:mount + findmnt(seccomp 前 drvfs 挂载工作区)
#     ripgrep       -- 单二进制 AI 搜索工具(~2MB 无依赖树);worker BashTool 描述依赖它
#     ca-certificates -- 很小,保证 AI 首次联网装软件即可用;极致求小可删,AI 需要时自装
#   不预装(AI 可按需装;worker 侧 git 走宿主 JGit,不依赖发行版内 git):
#     git           -- AI 需要时 apt-get install git
#   无 systemd / 无常驻 daemon —— 移除 systemd/cron/atd 包。这是 seccomp 硬隔离前提:
#     发行版内不存在「独立于 AI 命令树」的常驻进程,AI 的一切执行路径都在 seccomp
#     (deny mount/umount/init_module/...) + no_new_privs 的罩子里。
#   /etc/wsl.conf      —— automount=false(只影响本发行版,宿主盘不自动挂载,
#                         工作区由 eagent-run.py 在 seccomp 前手动 mount -t drvfs);
#                         interop=false / appendWindowsPath=false(禁 Linux 启动 Windows exe)
#   /etc/sysctl.d/99-eagent.conf —— apparmor userns 限制放开(Ubuntu 24.04+;内核不认则无害)
#
# 本地过渡(没有 CI/docker 时):从已装配的日常发行版直接导出——
#   wsl.exe --export Ubuntu <homeDir>\wsl\eagent-rootfs.tar.gz
#   wsl.exe -d Ubuntu -u root -e sh -c "printf '[automount]\nenabled=false\nmountFsTab=false\n[interop]\nenabled=false\nappendWindowsPath=false\n' > /etc/wsl.conf"
#   wsl.exe --terminate Ubuntu
#   # 然后生成 sha256(PowerShell):
#   powershell -Command "(Get-FileHash -Algorithm SHA256 <homeDir>\wsl\eagent-rootfs.tar.gz).Hash.ToLower() + '  eagent-rootfs.tar.gz' | Out-File -Encoding ascii <homeDir>\wsl\eagent-rootfs.tar.gz.sha256"
set -euo pipefail

BASE="${1:-ubuntu:24.04}"
OUT_DIR="${2:-./dist}"
NAME="eagent-rootfs"
CID="eagent-rootfs-build"

mkdir -p "$OUT_DIR"
docker rm -f "$CID" >/dev/null 2>&1 || true

# 1) 容器内烤包 + 写配置(apt 缓存随手清,镜像瘦身)
docker run --name "$CID" "$BASE" bash -euo pipefail -c '
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    # 最小预装集(只装运行时本身需要的):
    #   python3       -- 必需:eagent-run.py(runner,ctypes seccomp/process_vm_readv/ioctl)
    #   util-linux    -- 必需:mount + findmnt(seccomp 前 drvfs 挂载工作区)
    #   ripgrep       -- 单二进制 AI 搜索工具(~2MB 无依赖树);worker BashTool 描述依赖它
    #   ca-certificates -- 很小(几百 KB)但保证 AI 首次联网装软件即可用;极致求小可删,
    #                       AI 需要时先 apt-get install ca-certificates
    # 不预装(AI 可按需装;worker 侧 git 走宿主 JGit,不依赖发行版内 git):
    #   git           -- AI 需要时 apt-get install git
    apt-get install -y -qq --no-install-recommends python3 util-linux ripgrep ca-certificates
    # 移除常驻 daemon(systemd 系 / cron / atd):容器内本不以 systemd 为 PID 1,此为双保险——
    # 让发行版内不存在「独立于 AI 命令树」的常驻进程(seccomp 硬隔离前提)。
    apt-get remove -y --purge systemd systemd-sysv systemd-timesyncd systemd-resolved 2>/dev/null || true
    apt-get remove -y --purge cron cronie at 2>/dev/null || true
    apt-get autoremove -y -qq 2>/dev/null || true
    # 深度清理:apt 缓存 + doc/man/info(非运行期的大头)
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*
    rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/* /usr/share/lintian/* 2>/dev/null || true
    # wsl-direct:关闭 automount(只影响本发行版,宿主盘不自动挂载;工作区由 runner 手动 drvfs 挂载)
    printf "[automount]\nenabled=false\nmountFsTab=false\n[interop]\nenabled=false\nappendWindowsPath=false\n" > /etc/wsl.conf
    printf "kernel.apparmor_restrict_unprivileged_userns=0\n" > /etc/sysctl.d/99-eagent.conf
'

# 2) 导出根文件系统 → tar.gz + sha256(docker export 不含容器层状态,rootfs 语义正确)
docker export "$CID" -o "$OUT_DIR/$NAME.tar"
docker rm -f "$CID" >/dev/null
gzip -9 "$OUT_DIR/$NAME.tar"
sha256sum "$OUT_DIR/$NAME.tar.gz" > "$OUT_DIR/$NAME.tar.gz.sha256"

echo "产物:"
ls -lh "$OUT_DIR/$NAME.tar.gz" "$OUT_DIR/$NAME.tar.gz.sha256"
echo "部署:把两份文件放进仓库根 runtime/wsl/(或输出到 ./dist 后跑 npm run build:wsl 复制),重启 worker 即自动导入。"

