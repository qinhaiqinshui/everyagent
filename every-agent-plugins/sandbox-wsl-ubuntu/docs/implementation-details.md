# WSL Ubuntu 沙箱实现细节

> 本文内容提取自 [`docs/ARCHITECTURE.md`](../../../docs/ARCHITECTURE.md) §7.10「命令沙箱(插件化,多后端)」,
> 仅保留与 WSL Ubuntu 后端(`sandbox-wsl-ubuntu`)实现直接相关的段落,原始技术内容完整不变,
> 章节引用(如 §7.8、§7.17)保持原样。

---

## 后端对比表 · WSL Ubuntu 行

| 后端 | id | priority | grant/revoke | toSandbox | 说明 |
|---|---|---|---|---|---|
| **WSL Ubuntu** | `wsl-ubuntu` | 10 | 批量 drvfs 挂载(READ_ONLY → `-o ro`)/ best-effort umount | 已授权根 → `/c/...`(`WslPathView` 纯映射;未授权路径原样不谎报) | 原 wsl-direct 改名,独立插件;**plugin.json 默认 enabled=false** |

---

## WSL 发行版可用性(自动导入 rootfs)

**WSL 发行版可用性由 sandbox-wsl-ubuntu 插件全责保证(desktop 零参与,2026-10 移交)**:发行版探测与自动导入(`wsl -l -q` 探测 → 缺失时 sha256 校验 + `wsl --import EveryAgent` → 重探)全部住在 `WslUbuntuSandboxProvider.isAvailable()` 内,由上述后端选择时机的首次 `select()` 惰性触发——插件未启用/未注册即全链路零 WSL 调用。desktop 主进程**不做任何 WSL 探测**(曾有的启动期 utilityProcess preflight 硬编码了插件领域知识——发行版名/镜像文件名/导入语义——且与插件启用开关脱节,禁用插件后仍 spawn wsl.exe,已删除)。插件自带资源(rootfs 镜像、eagent-run.py)按三级链定位:`<pluginDir>/wsl/`(.eap 安装/源码开发态)→ `WorkerConfig.resolveRuntimeDir()/wsl/`(打包 desktop 态,镜像经插件 `runtime/` 目录并入程序根 runtime/,见 §7.17 程序附属文件;插件 `enabled=false` 时不进安装包)→ 配置 `worker.sandbox.wsl.tarball`(手动场景,相对系统目录解析)。

---

## 网络策略的 WSL 实现(unshare -n)

**网络策略**:两级开关,默认放行——① 全局 `worker.sandbox.allow-network=false`(经 `SandboxConfig.networkDenied` 交给后端:wsl-ubuntu 真断网、codex 选 Offline 账户;direct 只剥代理 env 拦不住直连);② 任务级 `/禁用网络`(**只有 wsl-ubuntu 后端做得到**,故整个能力归 `sandbox-wsl-ubuntu` 插件自带:插件自己注册 slash 命令提供者 + token 提交解析器,状态即任务级 `network.access` slash token(落盘任务目录 `slash-tokens.json`,核心不感知 kind),由 `NetworkTaskFlag.isOn` 经 `WorkerServices.slashTokens()` 查询、`WslUbuntuCommandExecutor` 每次 spawn 实时读;`/` 菜单条目按当前生效后端 `sandbox().id()` 决定是否出现,其他后端不提供该命令)。落地由沙箱插件自己的 CommandExecutor 负责:wsl-ubuntu = 发行版内 `unshare -n` 新建无 eth0 的 netns(DNS/回环全断)。worker 核心不持有任何任务级禁网状态,通用 `CommandExecutor`/`OsSandbox` 也不再判定网络。

---

## 路径翻译的 WSL 实现(WslPathView)

**路径翻译流程:**

1. 路径提供方登记 → `SandboxPathRegistry.register/sync()` → 账本差量经 `sandbox.grant()` 下发(建立权限 + 映射基准)。
2. 工具参数翻译:`FsToolSupport` 收到 AI 传的路径后调 `pathRegistry.toHostPath()` → 转发 `sandbox.toHost()`;无映射返回 null,原样保留让 Java NIO 自然报错。
3. 非工具路径翻译:`SkillAdvisor`(知识包路径)和 `ExternalFileTokenResolver`(@ 引用)直接调 `pathRegistry.toSandboxPath()` → 转发 `sandbox.toSandbox()`(默认恒等)。
4. 命令组装翻译:由各后端自持(wsl 用 `WslPathView` 的挂载表把已授权根及其子路径映射为 `/c/...`;codex/mic 恒等,命令本就跑宿主路径)。

> `WslPathView` 是纯映射:已授权根及其子路径映射为 `/c/...`;未授权路径原样不谎报。
> drvfs 挂载天然两者兼得(权限 + 映射基准由 grant 一次性确立)。

---

## SandboxBackend SPI 中与 WSL 相关的方法语义

- `grant(List<PathGrant>)` — 声明这些宿主路径在沙箱内**可访问**(建立权限 + 映射基准)。幂等、可重放。**best-effort 且不放大(§7.8 P5)**:无法在请求粒度落地时(典型:待建文件——创建需父目录写权限)**跳过并记日志,绝不放大到父目录**。
- `revoke(List<Path>)` — 撤销权限与映射。幂等;**只带路径,不带任务/工作区语义**(是否还有人需要该根由上层聚合判断)。
- `toSandbox(Path)` / `toHost(String)` — **纯查询、无副作用、默认恒等**;上层无条件调用一次,不需要判断「这个后端是否需要翻译」。翻译是沙箱世界的属性,且**由 grant 确立的映射基准派生**(wsl 的 drvfs 挂载天然两者兼得;codex 只涉权限,映射恒等)。

---

## WSL 专属配置项

- `worker.sandbox.type`: `auto`(默认)| `wsl-ubuntu` | `none`。归一化别名:旧值 `wsl-direct`/`direct` → `wsl-ubuntu`(静默兼容);`wsl-bwrap`/`wsl`/`bwrap` → 归一为 `auto` 并 WARN;`windows-mic`/`acl`/`mic`(sandbox-windows-mic 插件已移除)→ 归一为 `auto` 并 WARN;未知/空 → `auto`。WSL 专属配置(distro/tarball 等)与 codex 专属配置均由各插件通过 plugin.json `contributes.config` 自管。
