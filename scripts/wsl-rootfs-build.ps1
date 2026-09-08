# eagent managed distro rootfs build -- PowerShell edition (Windows host; equivalent to wsl-rootfs-build.sh)
#
# Artifacts: eagent-rootfs.tar.gz + eagent-rootfs.tar.gz.sha256 -- 放仓库根 runtime/wsl/
# (唯一真源,electron-builder extraResources 打进 <resourcesPath>/runtime/wsl),
# worker 探测到发行版缺失即自动 wsl --import eagent (offline, sha256 gated).
#
# Prereq: Docker Desktop (WSL2 backend), docker.exe on PATH.
# Usage (normal PowerShell):
#   powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wsl-rootfs-build.ps1
#   optional: [-Base ubuntu:24.04] [-OutDir .\dist]
#
# Baked contents (for wsl-direct backend, docs wsl2-bubblewrap-sandbox-design.md sec 4.8):
#   MINIMAL baked set (only runtime itself needs):
#     python3        -- REQUIRED: eagent-run.py runner (ctypes seccomp/process_vm_readv/ioctl)
#     util-linux     -- REQUIRED: mount + findmnt (drvfs workspace mount before seccomp)
#     ripgrep        -- single-binary AI search tool (~2MB, no dep tree)
#     ca-certificates -- small, ensures AI can install software immediately (apt/curl https)
#   NOT baked (AI installs on demand; worker git runs on host via JGit):
#     git            -- AI can `apt-get install git` when needed
#   NO systemd / NO resident daemons -- purge systemd/cron/atd packages. This is the premise of
#     seccomp hard isolation: no resident process independent of the AI command tree exists,
#     so every execution path of AI stays under the seccomp (deny mount/umount/init_module/...)
#     + no_new_privs filter.
#   /etc/wsl.conf      -- automount=false (this distro only; host drives not auto-mounted,
#                          workspaces mounted manually by eagent-run.py before seccomp via drvfs);
#                         interop=false / appendWindowsPath=false (forbid Linux starting Windows exe)
#   /etc/sysctl.d/99-eagent.conf -- relax apparmor userns restriction (Ubuntu 24.04+; no-op on older kernels)
#
# Alternative without docker (manual, needs a no-systemd minimal distro; see docs sec 4.8 premise):
#   wsl.exe --export <minimal-distro> <homeDir>\wsl\eagent-rootfs.tar.gz
#   wsl.exe -d <minimal-distro> -u root -e sh -c "printf '[automount]\nenabled=false\nmountFsTab=false\n[interop]\nenabled=false\nappendWindowsPath=false\n' > /etc/wsl.conf"
#   wsl.exe --terminate <minimal-distro>
#   (Get-FileHash -Algorithm SHA256 <homeDir>\wsl\eagent-rootfs.tar.gz).Hash.ToLower() + '  eagent-rootfs.tar.gz' | Out-File -Encoding ascii <homeDir>\wsl\eagent-rootfs.tar.gz.sha256
param(
    [string]$Base = "ubuntu:24.04",
    [string]$OutDir = ".\dist"
)

# PS 5.1 会把原生命令(docker.exe)的 stderr 包成 NativeCommandError;若 ErrorActionPreference=Stop
# 会把「容器不存在」这类无害提示也当成终止错误。关键步骤都显式检查 $LASTEXITCODE,故无需 Stop。
$ErrorActionPreference = "Continue"
$CID = "eagent-rootfs-build"

function Say($m) { Write-Host $m }
function Ok($m)  { Write-Host "  [OK] $m" -ForegroundColor Green }
function Bad($m) { Write-Host "  [FAIL] $m" -ForegroundColor Red; exit 1 }

# ---- [0] check docker ----
$docker = (Get-Command docker -ErrorAction SilentlyContinue)
if (-not $docker) { Bad "docker not found. Install Docker Desktop (WSL2 backend), or run wsl-rootfs-build.sh on Linux/CI, or use the manual fallback in the header." }

Say "===== [eagent] no-systemd minimal rootfs build ($Base) ====="

# cleanup previous container (may not exist; stderr suppressed via 2>&1)
$null = & docker rm -f $CID 2>&1

# ---- [1] bake packages + write configs inside container ----
$setupScript = @'
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
# MINIMAL baked set (only what the runtime itself needs):
#   python3       -- REQUIRED: eagent-run.py runner (ctypes seccomp/process_vm_readv/ioctl), bash/sh cannot do this
#   util-linux    -- REQUIRED: mount + findmnt (drvfs workspace mount before seccomp)
#   ripgrep       -- single-binary AI search tool (~2MB, no dep tree); worker BashTool description expects it
#   ca-certificates -- small (~hundreds KB) but ensures AI can install software immediately (apt/curl https).
#                      Remove only if you accept AI must run `apt-get install ca-certificates` first.
# NOT included (AI can install on demand, worker git runs on host via JGit not in-distro git):
#   git           -- AI can `apt-get install git` when needed
apt-get install -y -qq --no-install-recommends python3 util-linux ripgrep ca-certificates
# purge resident daemons (systemd family / cron / atd): container is not systemd-PID1 anyway,
# this is belt-and-suspenders so the distro has no resident process independent of the AI command tree
# (premise of seccomp hard isolation).
apt-get remove -y --purge systemd systemd-sysv systemd-timesyncd systemd-resolved 2>/dev/null || true
apt-get remove -y --purge cron cronie at 2>/dev/null || true
apt-get autoremove -y -qq 2>/dev/null || true
# deep cleanup: apt lists/cache + docs/man/info (largest non-runtime bulk)
rm -rf /var/lib/apt/lists/*
rm -rf /var/cache/apt/archives/*
rm -rf /usr/share/doc/* /usr/share/man/* /usr/share/info/* /usr/share/lintian/* 2>/dev/null || true
# wsl-direct: disable automount (this distro only; host drives not auto-mounted; workspaces are
# mounted manually by runner via drvfs before seccomp)
printf '[automount]\nenabled=false\nmountFsTab=false\n[interop]\nenabled=false\nappendWindowsPath=false\n' > /etc/wsl.conf
printf 'kernel.apparmor_restrict_unprivileged_userns=0\n' > /etc/sysctl.d/99-eagent.conf
'@

Say "  docker run $Base bake/purge-systemd/wsl.conf (may take a few minutes, network dependent)..."
& docker run --name $CID $Base bash -euo pipefail -c $setupScript
if ($LASTEXITCODE -ne 0) { Bad "docker run bake failed (rc=$LASTEXITCODE)" }
Ok "bake complete"

# ---- [2] export rootfs -> tar.gz + sha256 ----
New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
$name = "eagent-rootfs"
$tarFile = Join-Path $OutDir "$name.tar"
$gzFile  = Join-Path $OutDir "$name.tar.gz"
$shaFile = Join-Path $OutDir "$name.tar.gz.sha256"

& docker export $CID -o $tarFile
if ($LASTEXITCODE -ne 0 -or -not (Test-Path $tarFile)) { Bad "docker export failed" }
Ok "docker export -> $tarFile"

# gzip via .NET GZipStream (no external tar/gzip needed)
$in = [System.IO.File]::OpenRead($tarFile)
$out = [System.IO.File]::Create($gzFile)
$gzip = [System.IO.Compression.GZipStream]::new($out, [System.IO.Compression.CompressionLevel]::Optimal)
$in.CopyTo($gzip)
$gzip.Dispose(); $out.Dispose(); $in.Dispose()
Remove-Item $tarFile -Force
Ok "gzip -> $gzFile"

# sha256
$hash = (Get-FileHash -Algorithm SHA256 $gzFile).Hash.ToLower()
"$hash  $name.tar.gz" | Out-File -Encoding ascii $shaFile
Ok "sha256 -> $shaFile"

$null = & docker rm -f $CID 2>&1

Say ""
Say "Artifacts:"
Get-Item $gzFile, $shaFile | Select-Object Name, @{n='Size(MB)';e={[math]::Round($_.Length/1MB,1)}}, LastWriteTime | Format-Table -AutoSize
Say "Deploy: 把两份文件放进仓库根 runtime/wsl/(或 -OutDir .\dist 后跑 npm run build:wsl 复制),重启 worker 即自动导入。"
