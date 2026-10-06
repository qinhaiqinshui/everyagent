---
title: 插件开发指南
nav_order: 1
has_children: false
---

# 插件开发指南（Every Agent Plugin Guide）

> **这个站是什么**：Every Agent **插件体系**的动手手册——怎么生成一个插件工程、`plugin.json` 每个字段被谁消费、后端能注册哪些扩展点、前端能挂哪些界面、怎么构建打包安装、以及当前代码的真实现状与坑。
> **与架构文档的分工**：[`../ARCHITECTURE.md`](../ARCHITECTURE.md) 是唯一架构事实源，讲「为什么这么设计 + 红线」（§14 是红线清单）；本指南讲「怎么动手」。
> **冲突时以 `../ARCHITECTURE.md` 为准**，并按项目纪律**先改文档再改代码**。
> **面向读者**：要给 Every Agent 加能力的开发者。写插件不需要改动 hub / worker / web 任何宿主代码。

---

## 1. 三条阅读路径

三条路径共用同一前置（先把工程跑起来 → 看懂清单字段），之后按你想做的事分叉：

| 你想做的事 | 按顺序读 |
|---|---|
| **写后端能力**（工具 / 沙箱 / Advisor / 任务生命周期 / RPC） | [快速上手](getting-started.md) → [plugin.json 全字段](plugin-manifest.md) → [后端模型总览](backend/overview.md) → [工具与沙箱](backend/tools-and-sandbox.md) → [Advisor 扩展](backend/advisors.md) → [任务生命周期与 RPC](backend/task-and-rpc.md) → [持久化与插件私有状态](backend/persistence-and-state.md) |
| **写前端界面**（侧边栏 / 编辑器 / 工具调用视图 / 事件） | [快速上手](getting-started.md) → [plugin.json 全字段](plugin-manifest.md) → [前端总览与加载链路](web/overview-and-loading.md) → [PluginContext API](web/context-api.md) → [UI 扩展点](web/ui-extensions.md) → [事件](web/events.md) |
| **打包分发安装自己的插件** | [快速上手](getting-started.md) → [构建与运行](guides/build-and-run.md) → [打包与安装](guides/packaging-and-install.md) → [调试与测试](guides/debugging-and-testing.md) → [故障排查](guides/troubleshooting.md) |

写完想对照现成实现，看 [内置插件清单](reference/builtin-plugins.md)；想查一个方法/字段的名字，看 [API 索引](reference/api-index.md)；现象对不上文档，先查 [已知问题与现状偏差](reference/known-issues.md)。

## 2. 全站目录（19 篇）

| 文档 | 讲什么 | 状态 |
|---|---|---|
| [index.md](index.md) | 本站首页：定位、阅读路径、目录、速览、写作规范 | **已成文** |
| [getting-started.md](getting-started.md) | 用 `create-everyagent-plugin` 脚手架 5 分钟跑通第一个插件 | **已成文** |
| [plugin-manifest.md](plugin-manifest.md) | `plugin.json` 逐字段：类型 / 缺省 / 谁消费 / 踩坑 + 三形态实例 | **已成文** |
| [backend/overview.md](backend/overview.md) | 后端模型：`EveryAgentPlugin` 生命周期、URLClassLoader 隔离、扫描→激活全链路 | **已成文** |
| [backend/tools-and-sandbox.md](backend/tools-and-sandbox.md) | ToolProvider / ToolExecutionInterceptor / SandboxProvider / FileReferenceHandler 与 PermissionGate、jail | **已成文** |
| [backend/advisors.md](backend/advisors.md) | AdvisorProvider / ChatModelEnhancer / TokenEstimator / SkillContributor / SearchProvider / AuthorizationHandler + Advisor 链 order 坐标 | **已成文** |
| [backend/task-and-rpc.md](backend/task-and-rpc.md) | TaskAdmissionPolicy / TaskLifecycleNode、`registerRpcMethod` 命名与 ACL、Slash 候选与 token 解析 | **已成文** |
| [backend/persistence-and-state.md](backend/persistence-and-state.md) | 插件私有数据的合法存放（`pluginDir()` + 自注册 RPC）、两种禁用机制 | **已成文** |
| [web/overview-and-loading.md](web/overview-and-loading.md) | 前端加载链路、bare import 白名单、为何必须 `import type` | **已成文** |
| [web/context-api.md](web/context-api.md) | `ctx.ui / sdk / events / fs / storage / commands` 逐个方法与 Disposable 语义 | **已成文** |
| [web/ui-extensions.md](web/ui-extensions.md) | 12 个 UI 扩展点逐个成节：字段表 + 注册代码 + 排序规则 | **已成文** |
| [web/events.md](web/events.md) | 宿主事件全集与 plugin-api 具名事件、`subscribe` / `getExtensionsVersion` 快照机制 | **已成文** |
| [guides/build-and-run.md](guides/build-and-run.md) | 三形态构建矩阵、插件不进 reactor 的独立构建、cwd 决定内置插件是否被加载 | **已成文** |
| [guides/packaging-and-install.md](guides/packaging-and-install.md) | `.eap` 包内部布局、6 个 `plugin.*` RPC 现状、手工安装目录布局 | **已成文** |
| [guides/debugging-and-testing.md](guides/debugging-and-testing.md) | 后端 JUnit5 单测范式（不依赖 worker）、前端 typecheck/build 自检、日志定位 | **已成文** |
| [guides/troubleshooting.md](guides/troubleshooting.md) | 以 `LoadedPlugin.status` 与 worker WARN 文案逐条为行的「现象→根因→修复」表 | **已成文** |
| [reference/builtin-plugins.md](reference/builtin-plugins.md) | 内置插件 × 扩展点索引：形态 / 注册了什么 / 能当哪个范例 | **已成文** |
| [reference/api-index.md](reference/api-index.md) | 后端注册点 + 前端类型包导出的一屏索引表 | **已成文** |
| [reference/known-issues.md](reference/known-issues.md) | 现状偏差、API 包未发布等：现象 / 影响 / 修复·规避 / 待办（#1~#5 已修复） | **已成文** |

> 目录由本文件冻结：`nav_order` 按上表顺序 1~19 递增，`parent` 为所在子目录名（`backend` / `web` / `guides` / `reference`），首页无 `parent`。新增页面要同时改本表、本条编号与 §1 阅读路径。

## 3. 5 分钟速览：一个插件由哪些部分组成

内置插件住在仓库根的 `every-agent-plugins/<id>/`，一个目录就是一个插件：

```
every-agent-plugins/<id>/
├── plugin.json                      # 唯一清单：id / name / version / description / author / enabled + main / webMain
├── pom.xml                          # 仅 java 形态需要；parent = dev.everyagent:every-agent-parent:1.0.0
├── src/main/java/…/<Id>Plugin.java  # 实现 EveryAgentPlugin，activate(ctx) 里注册一切能力
├── web/
│   ├── index.ts                     # 前端入口（源码）
│   └── index.js / index.css         # esbuild 产物：npm run build:plugins 生成，已被 .gitignore 排除
└── target/
    ├── classes/plugin.json          # mvn package 时由 maven-resources-plugin 复制进来
    └── <id>-<version>.jar
```

**三种形态**（区别只在 `plugin.json` 写了 `main` 还是 `webMain`）：

| 形态 | `plugin.json` 关键字段 | 目录构成 | 实测例 |
|---|---|---|---|
| java-only | 只有 `main` | `plugin.json` + `pom.xml` + `src/` | `every-agent-plugins/task-queue/` |
| java + web | `main` + `webMain` | 上两行合并 | `every-agent-plugins/git/` |
| 纯 web | 只有 `webMain` | `plugin.json` + `web/`（**无 pom、无 src**） | `every-agent-plugins/pdf-viewer/`、`plugin-manager/`、`update-file-view/` |

worker 侧的判定规则（`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/scanner/BuiltInPluginScanner.java`）：子目录根有 `plugin.json` 才被视为插件；若存在 `target/classes/plugin.json` 则判为 java 插件并要求 `target/` 下有可加载 jar，否则 WARN「内置插件未构建,请先 mvn package」并跳过；没有 `target/` 产物则按纯 web 插件纳入（此类插件在后端登记为「声明式插件」）。

**全站通用的几条硬事实**（出处：`.everyagent/web-plugin-facts.md`、`.everyagent/plan-plugin-scaffold-docs.md`「已核实事实」，本指南各篇不再重复论证）：

| 事实 | 证据 |
|---|---|
| `plugin.json` 里 `webMain` 的**值不被前端消费**（只决定 `hasWebMain`）；宿主一律请求 `plugin.webSource{pluginId, path:'web/index.js'}` | `every-agent-web/src/plugin/pluginLoader.ts` |
| 内置插件目录由 `worker.builtin-plugins-dir` 决定，默认按**进程 cwd** 解析；外部插件目录 `worker.plugins-dir`，默认 `~/.everyagent/plugins/<id>/`，其 java jar 约定落 `<id>/lib/*.jar` | 上述两个 Scanner 源文件 |
| 前端产物 `web/index.js` 不在 `dev` / `build` / 桌面任何流水线里，改前端**必须手工** `npm run build:plugins`（`every-agent-web/scripts/build-plugins.mjs`） | `.everyagent/web-plugin-facts.md` §3 |
| bare import 白名单只有 5 项：`react`、`react-dom`、`react/jsx-runtime`、`antd`、`@ant-design/icons`，运行时改写为 5 个 `window.__EA_*` 全局 | 同上 §4 |
| 仓库内插件的 `web/*.ts` 由宿主 `every-agent-web/tsconfig.json` 的 `include` 覆盖，**无需自带 tsconfig** | `every-agent-web/tsconfig.json` |
| `enable` / `disable` / `install` / `uninstall` 一律**重启 worker 才生效**（插件系统没有 `deactivate` 钩子） | `../ARCHITECTURE.md` §8.5 |
| `@everyagent/plugin-api`（js，0.11.0）与 `dev.everyagent:every-agent-plugin-api`（1.0.0）**均未发布**到公共仓库（npm / Maven Central 实测 404），故仓库外开发需自带类型副本 | `.everyagent/plan-plugin-scaffold-docs.md` |

## 4. 能力面速查与术语

一个插件的全部能力都在 `activate(ctx)` 里注册。能挂的地方一共这些：

| 侧 | 挂载面 | 数量 | 详解 |
|---|---|---|---|
| 后端 | `WorkerPluginContext` 自身的 `register*` 方法 | 13 | 下表分组 |
| 后端 | 继承自 `TaskPluginContext` 的 `register*` | 2 | [任务生命周期与 RPC](backend/task-and-rpc.md) |
| 前端 | `ctx.ui` 的 `register*` 扩展点 | 12 | [UI 扩展点](web/ui-extensions.md) |
| 前端 | `ctx.ui` 的动作方法（开 tab / 开文件 / 开 diff / 写 composer） | 5 | [PluginContext API](web/context-api.md) |
| 前端 | `ctx` 能力域（`ui` / `sdk` / `events` / `fs` / `storage` / `commands`） | 6 | [PluginContext API](web/context-api.md) |
| 前端 | `PluginDomainEvent` 具名事件（其余靠 `(string & {})` 兜底） | 9 | [事件](web/events.md) |

后端 15 个注册点的分组与归属篇（方法名逐条取自 `every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/WorkerPluginContext.java` 与 `.../api/task/TaskPluginContext.java`）：

| 分组 | 方法 | 详解篇 |
|---|---|---|
| 工具与沙箱 | `registerToolProvider` `registerToolExecutionInterceptor` `registerSandboxProvider` `registerFileReferenceHandler` | [工具与沙箱](backend/tools-and-sandbox.md) |
| Agent 增强 | `registerAdvisorProvider` `registerChatModelEnhancer` `registerTokenEstimator` `registerSkillContributor` `registerSearchProvider` `registerAuthorizationHandler` | [Advisor 扩展](backend/advisors.md) |
| 任务编排 | `registerTaskAdmissionPolicy` `registerTaskLifecycleNode` | [任务生命周期与 RPC](backend/task-and-rpc.md) |
| 对外接口 | `registerRpcMethod` `registerSlashProvider` `registerSlashTokenResolver` | [任务生命周期与 RPC](backend/task-and-rpc.md) |

常遇到的词：

| 术语 | 含义 |
|---|---|
| 内置插件 | 住在仓库 `every-agent-plugins/<id>/`，`source=builtin`；扫描根目录默认按进程 cwd 解析 |
| 外部插件 | 住在 `~/.everyagent/plugins/<id>/`，`source=external`；按 `../ARCHITECTURE.md` §8.5，两类扫描器按 source 排序、**builtin 优先**加载 |
| 形态 | java-only / 纯 web / java+web，只由 `plugin.json` 写了 `main` 还是 `webMain` 决定 |
| 声明式插件 | 只有 `webMain`、无 java 构建产物时，worker 给该插件登记的 `status` 文案 |
| 激活 | 后端 `EveryAgentPlugin.activate(WorkerPluginContext)` / 前端 `PluginModule.activate(PluginContext)`；插件的一切贡献只在这里注册 |
| `.eap` | 分发格式：zip。安装端按 zip 内**首个目录名**取 pluginId（无目录条目时回退 zip 文件名去掉 `.eap`），故打包时顶层目录必须就是 `<id>/`（见 [打包与安装](guides/packaging-and-install.md)） |

## 5. 两条必读硬约束

> ⚠️ **① 插件禁止依赖 `every-agent-worker`** —— 依据 [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §14.9：插件 pom 里出现对 `every-agent-worker` 的依赖，**任何 scope（compile / provided / runtime / test）一律禁止**。插件唯一允许的实现依赖是 `every-agent-plugin-api`。测试需要 `TaskRuntime` / `AgentContext` / 配置等桩时，在**测试源码内自建实现 plugin-api 接口的等价桩类**，不得把 worker 的具体实现类（`TaskEntry` / `AgentEntity` / `WorkerProperties` / `SlashCommandRegistry` 等）当测试脚手架；类型确实要跨 worker 与插件共享时，先下沉到 plugin-api（文档先行）。

> ⚠️ **② 前端插件禁止 `@/` 引用宿主模块，类型包只能 `import type`** —— 依据 [`../ARCHITECTURE.md`](../ARCHITECTURE.md) §8.5「公共 API 边界（VSCode 模式）」：插件源码里出现 `@/...` 即为违规（现仓所有内置插件源码零命中）；UI 组件与图标一律插件自实现。对 `@everyagent/plugin-api` **只能写 `import type`**——宿主加载走 `rewriteBareImports` + blob URL，只改写上面那 5 个 bare import，`export … from 'react'` 这类再导出与动态 `import('antd')` 不被改写，运行时会抛错（详见 [前端总览与加载链路](web/overview-and-loading.md)）。

## 6. 常见疑问直达

| 现象 / 疑问 | 去看 |
|---|---|
| 插件装好了，但侧边栏图标就是不出现 | [故障排查](guides/troubleshooting.md)（配合 [前端总览与加载链路](web/overview-and-loading.md)） |
| 改了 `web/*.ts` 页面没反应 | [构建与运行](guides/build-and-run.md)：前端产物要手工 `build:plugins` |
| worker 日志出现「内置插件未构建,请先 mvn package」 | [构建与运行](guides/build-and-run.md) → [故障排查](guides/troubleshooting.md) |
| 启用了插件但没生效 / 禁用了还在跑 | [持久化与插件私有状态](backend/persistence-and-state.md)：两种禁用机制都要重启 worker |
| 想加一个 `/` 斜杠命令、一个自定义 RPC | [任务生命周期与 RPC](backend/task-and-rpc.md) |
| 想给自己的插件存数据 | [持久化与插件私有状态](backend/persistence-and-state.md) |
| 想在聊天流里自定义某类工具 / 某轮结果的展示 | [UI 扩展点](web/ui-extensions.md)（`ui.tool_call_views`、`ui.trace_types`、`ui.round_tail_panels`） |
| 能不能引用 worker 的类、宿主 `@/` 模块 | 不能，见本页 §5 两条红线 |
| 文档写的和代码不一样 | [已知问题与现状偏差](reference/known-issues.md)，并以 `../ARCHITECTURE.md` 为准 |

## 7. 写作与链接规范（给后续文档作者）

本站全站统一，靠这些约定保持一致，不靠个人偏好：

1. **顶部 YAML front-matter**（每篇必带；字段名与顺序照抄）：

   ```yaml
   ---
   title: 插件开发指南
   nav_order: 1
   has_children: false
   ---
   ```

   子页用 `nav_order: 2..19`、`parent: backend|web|guides|reference`（取所在子目录名），`has_children` 一律 `false`。
2. **站内互链一律相对路径 + `.md` 后缀**，例：`[plugin.json 字段](plugin-manifest.md)`、`[后端总览](backend/overview.md)`。不用 `/docs/...` 绝对路径、不用 `./` 冗余前缀。
3. **引用代码给相对仓库根的文件路径**，例：`every-agent-worker/src/main/java/dev/everyagent/worker/plugin/loader/PluginLoader.java`；不写 `C:\...` 这类机器绝对路径。
4. **不虚构未验证的行为**：写进正文的技术断言要么给出上面那种代码路径，要么注明结论来源；未实测就明写「未实测」，不拿推测冒充事实。
5. **不用图片外链**；结构图、链路图用 ASCII 代码块。
6. **中文正文、代码注释中文**；示例代码要可复制即跑，命令按 Windows PowerShell 口径写（`npm` 被执行策略拦截时走 `npm.cmd`）。
7. 一处职责一处文档：字段表在 `plugin-manifest.md`，扩展点详解在对应 `backend/`、`web/` 篇，索引只在 `reference/api-index.md`，别处用链接指过去。

## 附录：发布到 GitHub Pages

当前为**纯 Markdown、无构建步骤**。开启 Pages：仓库 Settings → Pages → Build and deployment → Source: **Deploy from a branch** → Branch: `main` / folder: `/docs`。
若要侧边栏导航主题，推荐落点是 `docs/_config.yml`（just-the-docs），本篇与各页 front-matter 的 `nav_order` / `parent` 已按该主题设计，也可被 VitePress / MkDocs 直接复用。（`_config.yml` 由后续步骤单独引入，本指南正文不依赖它。）
