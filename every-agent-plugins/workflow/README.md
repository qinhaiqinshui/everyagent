<!-- ea: 插件根 README：本插件的构建 / 验证 / 生效 / 打包命令速查（按 kind 与 mode 索引对应小节） -->
# workflow

workflow 插件

| 项 | 值 |
| --- | --- |
| 插件 id | `workflow` |
| 形态 kind | `full`（java = 仅后端；web = 仅前端；full = 前后端都有） |
| 工程形态 mode | `builtin`（builtin = 仓库内 `every-agent-plugins/` 下；standalone = 仓库外独立工程） |
| 版本 / 作者 | 0.1.0 / everyagent |
| 后端入口类 | `dev.everyagent.plugin.workflow.WorkflowPlugin`（plugin.json 的 main，kind=web 时无此项） |

本 README 由脚手架生成，命令按「适用条件」标注——只执行匹配 kind=full、mode=builtin 的命令块。

## 目录说明

| 文件 | 作用 | 适用 kind | 适用 mode |
| --- | --- | --- | --- |
| `plugin.json` | 插件清单：id/name/version/description/author/enabled + `main`（java/full）+ `webMain`（web/full），宿主扫描与加载的唯一依据 | 全部 | 全部 |
| `README.md` / `.gitignore` | 本说明；忽略 `target/`、`web/index.js(.map)`、`web/index.css(.map)`、`node_modules/`、`dist/` 等构建产物 | 全部 | 全部 |
| `pom.xml` | Maven 构建（内联 maven-resources-plugin 把 plugin.json 复制进 `target/classes`） | java / full | 全部 |
| `src/main/java/dev/everyagent/plugin/workflow/WorkflowPlugin.java` | 后端入口类：实现 `EveryAgentPlugin`，`activate()` 里经 `WorkerPluginContext` 注册扩展点（16 个注册方法目录见类内注释） | java / full | 全部 |
| `src/main/resources/` | 插件资源（打进 jar），初始为空（`.gitkeep` 占位） | java / full | 全部 |
| `src/test/java/dev/everyagent/plugin/workflow/WorkflowPluginSmokeTest.java` | JUnit 5 冒烟测试：只依赖 every-agent-plugin-api，不依赖 worker（§14.9 红线） | java / full | 全部 |
| `web/index.ts` | 前端入口：默认导出 `PluginModule`，`activate(ctx)` 里经 `ctx.ui` 注册扩展点（示例注册侧边栏项） | web / full | 全部 |
| `package.json` / `tsconfig.json` | standalone 的 npm 清单与 TS 配置（paths 把 `@everyagent/plugin-api` 指向 vendor 类型副本） | web / full | standalone |
| `scripts/build.mjs` | standalone 前端构建：esbuild 打包 `web/index.ts` → `web/index.js`，选项与宿主 `build-plugins.mjs` 逐项一致 | web / full | standalone |
| `vendor/@everyagent/plugin-api/index.d.ts` | plugin-api 类型副本（逐字节复制自仓库 `every-agent-plugin-api/js/index.ts`；升级 API 时重新复制） | web / full | standalone |

构建产物 `target/`（Maven）与 `web/index.js(.map)` / `web/index.css(.map)`（esbuild）均为 gitignored，不入库。

## 功能概览

工作流插件：把「多 agent 协作 + 命令执行 + 出错人工介入」编排为可视化工作流——
画布定义、实例运行、并行调度与 supervisor 督导一体（前后端同一插件 id）。

- **持久化层**（`model/` `validate/` `store/`）：定义/实例 JSON 持久化于
  `workspaces/<wsId>/workflows/`（与 tasks/ 平级、工作区即隔离边界；
  `definitions/<wfId>/definition.json` + `instances/<wiId>/{meta.json,events.jsonl,runs/,agents/}`）；
  全量校验器；激活扫描（非终态实例标 failed）。
- **执行引擎**（`engine/`）：`WorkflowRuntime`（ExecContext 实现 + WorkflowSubject 标记，
  per-node 视图 + emitter 双路由：agent 域事件→`agents/<name>.jsonl`、实例域→events.jsonl；
  agentId=`<name>#<instanceId>`，匿名 AI 节点一次性会话不落盘）
  + `WorkflowEngine` 调度器（开始节点先行、边激活语义一次求值、同 agent fair 锁互斥、
  循环上限 maxRuns、实例状态机、错误三策略 fail/supervisor/askUser、取消）+ `NodeRunners`
  （命令节点走 nativeExec、AI 节点按会话时序 ①-⑥ 走 agentFactoryFor 装配链、
  supervisor 决策协议 retry/skip/fail/retryWithInput）。
- **RPC 面**（`rpc/`）：`workflow.*` 15 方法——`def.list`/`def.get`/`def.save`/`def.delete`/
  `def.export`/`def.import`、`run`、`instance.list`/`instance.get`/`instance.runLog`/
  `instance.events`/`instance.cancel`/`instance.delete`/`instance.decide`/`instance.dataDir`
  （实例目录绝对路径，前端「复制工作流路径」按需拉取，`task.dataDir` 同款纪律）；**写操作必带
  workspace 参数**（同 `task.run` 纪律）；实例事件混合模型：实时增量经 `EventSink.fanout`
  扇出到实例流频道 `u.<K>.worker.<wid>.wf.<wiId>.stream`（store 落盘后通知监听器，
  同实例锁内回调=推送序与落盘序一致），历史/断线补齐走 `instance.events` RPC 拉取，
  前端先订阅后回放、按 seq 去重合流。
- **hidden 五工具**（`WorkflowToolProvider` + `skill/workflow-tools/skill.md`）：
  `workflow_create/get/update/delete/list`，只服务普通任务的 AI（工作流自己的 agent
  不携带，`appliesTo` 排除），模型经 skill 文档按名调用。
- **前端界面**（`web/`）：自研 SVG 画布（拖拽/连线/条件标签/运行态着色，Pointer
  Events 统一鼠标/触屏——节点拖拽、锚点连线、背景平移触屏可用）+ 定义编辑器
  （palette/节点属性面板/七种边条件/运行变量/出错策略/Agents 总表，输入模板用宿主
  RichComposer）+ 侧边栏（**只展示运行实例**，组头 ▶ 运行/管理/刷新+批量删除
  ——宿主任务侧边栏同款单组勾选批删；定义不在侧栏展示）+ **定义管理标签页**
  （workflow-manage，每工作区一个：列表增删改查、导入、
  导出、运行、查看 JSON）+ 实例运行页（只读画布实时投影、askUser 决策横幅、事件流
  断线重连）+ 运行日志页（物流式时间线：命令 stdout/stderr、AI 输入输出、督导介入
  链）。**移动端适配**（≤768px，插件自持 `useIsMobile`，断点对齐宿主）：编辑器左右
  栏/实例页详情面板退化为滑入抽屉与底部抽屉（选中自动弹出、×/遮罩收起）、侧边栏与
  管理页行操作 ⋯ 菜单常显并放大触控目标、管理页顶栏动作组换行全宽、输入框 16px 防
  iOS 聚焦自动放大；桌面布局不变（移动样式全部收在各面板 `@media` 块内）。
  人工验证清单汇总见 [MANUAL-TEST.md](MANUAL-TEST.md)。
- **设计决策**：
  - **命令节点不经 PermissionGate**——与 git/term 同级信任：工作流是用户显式编排的
    受控操作（命令固定写进 definition.json、可审查），不是模型即兴决定；执行走
    nativeExec（cwd=工作区根），与 git/term 工具同一通道同一纪律。
  - AI 节点不设节点级超时（依赖模型链路既有护栏，timeoutMs 仅命令节点有效）；
  - v1 无断点续跑（worker 重启即实例标 failed，同任务语义）。

## 构建与验证

### 后端（kind = java / full）

builtin 形态（在**仓库根**执行；插件不进根 reactor，必须 `-f` 单独构建）：

```powershell
mvn -f every-agent-plugins/workflow/pom.xml package
# 首次构建若报找不到 io.github.qinhaiqinshui:every-agent-plugin-parent，先在仓库根执行一次：
mvn -pl every-agent-plugin-parent,every-agent-plugin-api -am install -DskipTests
```

standalone 形态（在**本目录**执行；插件 SDK 坐标 io.github.qinhaiqinshui:every-agent-plugin-parent / -plugin-api）：

```powershell
# SDK 首发到 Maven Central 之后：直接构建即可
mvn package                                              # 本插件目录

# SDK 首发 Central 之前的过渡：先克隆宿主仓库（https://github.com/qinhaiqinshui/everyagent），
# 在其仓库根执行一次（装进本地 Maven 仓库），再回本目录 mvn package：
# mvn -pl every-agent-plugin-parent,every-agent-plugin-api -am install -DskipTests
```

验证：`package` 已包含冒烟测试（`target/surefire-reports/`）；产物为 `target/*.jar`，jar 内须含 `plugin.json`（`jar tf target/*.jar | findstr plugin.json`）。

### 前端（kind = web / full）

builtin 形态（在**仓库根**执行；该脚本固定扫描 `every-agent-plugins/<id>/web/index.ts`，不在 dev/build 任何流水线里，改一次跑一次）：

```powershell
cd every-agent-web
npm run build:plugins     # PowerShell 若被执行策略拦下 npm，改用 npm.cmd run build:plugins
npm run typecheck         # 宿主 tsconfig 已 include 内置插件 web/，类型错误在这里暴露
```

standalone 形态（在**本目录**执行；esbuild 选项与宿主逐项一致，勿私自改 target/format/jsx）：

```powershell
npm install
node scripts/build.mjs    # 产物 web/index.js（如有 CSS import 另产 index.css，均带 .map）
```

## 改动如何生效

- **重启 worker**：后端、前端、plugin.json 的任何改动都没有热重载，必须重启 worker 进程。
- **web 改动**额外需要重跑前端构建（builtin：`npm run build:plugins`；standalone：`node scripts/build.mjs`）并**刷新页面**——宿主加载的是 `web/index.js` 产物，不读 `web/index.ts`。
- builtin 形态要求 worker 的 cwd 在仓库根（内置扫描按 `user.dir` 找 `every-agent-plugins/`）。
- 验证是否加载：worker 日志出现「插件已激活: id=workflow」，或前端调用 `plugin.list` RPC。
- standalone / 手工分发安装：把产物放进 worker 机器的 `~/.everyagent/plugins/workflow/`（后端 jar 放 `lib/*.jar`，前端 bundle 放 `web/index.js`，plugin.json 在顶层），再重启 worker。

## 打包分发（.eap）

```powershell
# builtin 形态（仓库根执行）
node create-everyagent-plugin pack every-agent-plugins/workflow
# standalone 形态（本目录执行）
node create-everyagent-plugin pack .
```

`.eap` 打包：产出 `workflow-0.1.0.eap`，zip 顶层目录名 = 插件 id，内含 `plugin.json` +（java/full）`lib/*.jar` +（web/full）`web/` 产物；`--verify` 打包后自检（条目树 + CRC + 顶层目录核对）。安装走 worker 的 `plugin.install` RPC（或手工解压到 `~/.everyagent/plugins/<id>/`），重启 worker 生效。

## 更多文档

插件开发全量文档见仓库 `docs/plugin-guide/`（形态选择、扩展点清单、构建分发指南；builtin 形态可直接相对仓库根阅读）。
