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

> ripgrep 二进制体积大，不入 git。

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

WSL 沙箱镜像由 `sandbox-wsl-ubuntu` 插件自己管理。镜像构建脚本输出到
插件自己的 `runtime/wsl/` 目录，由 `copy-plugin-runtime.mjs`（通用插件
资源打包脚本）自动复制到共享 `runtime/wsl/`。

插件被禁用（`plugin.json` 中 `enabled: false`）时，镜像不会被复制，
已有的残留会被清理——不会打进安装包。

| 路径 | 用途 | 消费方 |
|---|---|---|
| `wsl/eagent-run.py` | WSL 发行版侧启动器（stdin 载荷 → root 直连） | `WslCommon.resolveRunner()`（定位 `<pluginDir>/wsl/`） |
| `wsl/eagent-rootfs.tar.gz` + `.sha256` | 托管发行版 `EveryAgent` 镜像，发行版缺失时自动 `wsl --import` | `WslCommon.tarballFor()`（定位 `<pluginDir>/wsl/`） |

> 镜像体积大，不入 git；`eagent-run.py` 随源码入 git。

生成方式（仓库根目录执行）：

```bash
every-agent-plugins/sandbox-wsl-ubuntu/scripts/wsl-rootfs-build.ps1
# 产物输出到 every-agent-plugins/sandbox-wsl-ubuntu/runtime/wsl/
# npm run dist 时由 build:plugin-runtime 自动复制到共享 runtime/wsl/
```
