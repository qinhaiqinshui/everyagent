# runtime — 程序附属文件（唯一真源，随安装包分发，运行时只读引用）

本目录是**唯一的程序附属文件目录**（仓库根 `runtime/`），随安装包分发到**程序根**下：

- **IDE 开发态**：程序根 = 仓库根，worker 以 `./runtime` 按 `user.dir` 直接命中本目录。
- **desktop 打包态**：`electron-builder.yml` 的 `extraResources`（`from: ../runtime`）
  把本目录原样搬到 `<resourcesPath>/runtime`，与 web/backend/jre 同层；worker 的 cwd =
  `process.resourcesPath`，读 `./runtime` 命中同一落点。

worker 运行时以**字面相对路径 `./runtime`** 按 `user.dir` **只读引用**这些文件，不再把它们
解压/复制到用户系统目录 `~/.everyagent/`（架构 §5.9 系统目录只放机器级状态与用户数据，不放程序）。

## 目录内容

| 路径 | 用途 | 消费方 |
|---|---|---|
| `bin/rg.exe`（Windows）/ `bin/rg`（Linux, musl 静态） | ripgrep，注入 bash/powershell 子进程 `PATH` 供 AI 直接执行 `rg` | `RipgrepBinary`（定位 `./runtime/bin/`） |
| `wsl/eagent-run.py` | WSL 发行版侧启动器（stdin 载荷 → bwrap / root 直连） | `WslBwrapSandbox.resolveRunner()` / `WslDirectSandbox`（定位 `./runtime/wsl/`） |
| `wsl/eagent-rootfs.tar.gz` + `.sha256` | 托管发行版 `eagent` 镜像，发行版缺失时自动 `wsl --import` | `WslBwrapSandbox.tarballFor()` / desktop preflight（定位 `./runtime/wsl/`） |

> 镜像与 rg 二进制体积大，不入 git（见仓库根 `.gitignore` 的 `runtime/wsl/eagent-rootfs.tar.gz*`）；
> `eagent-run.py` 随源码入 git。

## ripgrep（rg）二进制

| 平台 | 文件 |
|---|---|
| Windows (x86_64) | `bin/rg.exe` |
| Linux (x86_64, musl 静态) | `bin/rg` |

更新方式（以 14.1.1 为例，版本以实际为准）：

- Windows: <https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-pc-windows-msvc.zip>
  → 解压取 `rg.exe` → 覆盖 `bin/rg.exe`
- Linux: <https://github.com/BurntSushi/ripgrep/releases/download/14.1.1/ripgrep-14.1.1-x86_64-unknown-linux-musl.tar.gz>
  → 解压取 `rg` → 覆盖 `bin/rg`

规则：

- 文件名固定不带版本号；升级 = 直接覆盖文件。
- Linux 版需 musl 静态构建（容器内无 glibc 依赖）。
- 开发迭代可用配置 `worker.tools.rg-path` 直接指向本机二进制，免重新打包；配置非法时 rg 不可用（不注入 PATH）。

## WSL 托管发行版镜像

生成方式（仓库根目录执行，二选一）：

```powershell
# 直接构建到仓库根 runtime/wsl/
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wsl-rootfs-build.ps1 -OutDir .\runtime\wsl
```

或（构建到 dist 再由 build:wsl 复制到仓库根 runtime/wsl/）：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\wsl-rootfs-build.ps1 -OutDir .\dist
cd every-agent-desktop; npm run build:wsl
```

Linux/CI：

```bash
scripts/wsl-rootfs-build.sh ./runtime/wsl
```

打包：`electron-builder.yml` 的 `extraResources`（`from: ../runtime`）把本目录原样搬进
`<resourcesPath>/runtime`。运行时 desktop preflight 直接用该目录镜像自动 `wsl --import eagent`
（不再复制到 `~/.everyagent/wsl/`）；worker 探测的 `tarballFor()` 也优先读
`./runtime/wsl/eagent-rootfs.tar.gz`。

> 若不打包镜像（目录里无 rootfs 文件），desktop 仍可启动，但 wsl-direct 后端会因缺发行版
> 回退 windows-mic，需手动构建/放置镜像后重启。
