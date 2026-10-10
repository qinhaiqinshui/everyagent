# 用户提问（ask-user）

提供 ask_user 工具，支持 agent 向用户发起单选题提问并等待答复（java-only 内置插件）。

| 项 | 值 |
| --- | --- |
| 插件 id | `ask-user` |
| 形态 | kind=java（仅后端）× mode=builtin（仓库内 `every-agent-plugins/`） |
| 版本 / 作者 | 0.1.0 / everyagent |
| 后端入口类 | `dev.everyagent.plugin.askuser.AskUserPlugin`（plugin.json 的 main） |

## 目录说明

| 文件 | 作用 |
| --- | --- |
| `plugin.json` | 插件清单（id/name/version/description/author/main/enabled），宿主扫描与加载的唯一依据 |
| `pom.xml` | Maven 构建（parent=every-agent-parent；内联 maven-resources-plugin 把 plugin.json 复制进 `target/classes`） |
| `src/main/java/dev/everyagent/plugin/askuser/AskUserPlugin.java` | 后端入口类，实现 `EveryAgentPlugin`，`activate()` 里经 `WorkerPluginContext` 注册扩展点 |
| `src/main/resources/` | 插件资源（打进 jar），初始为空（`.gitkeep` 占位） |
| `src/test/java/dev/everyagent/plugin/askuser/AskUserPluginSmokeTest.java` | JUnit 5 冒烟测试：只依赖 every-agent-plugin-api，不依赖 worker（§14.9 红线） |

构建产物 `target/` 为 gitignored，不入库。

## 构建与验证

在**仓库根**执行（插件不进根 reactor，必须 `-f` 单独构建）：

```powershell
mvn -f every-agent-plugins/ask-user/pom.xml package
# 首次构建若报找不到 dev.everyagent:every-agent-plugin-api，先在仓库根执行一次：
mvn -pl every-agent-plugin-api -am install -DskipTests
```

验证：`package` 已包含冒烟测试（`target/surefire-reports/`）；产物为 `target/*.jar`，jar 内须含 `plugin.json`（`jar tf target/*.jar | findstr plugin.json`）。

## 改动如何生效

- **重启 worker**：任何改动都没有热重载；builtin 形态要求 worker 的 cwd 在仓库根（内置扫描按 `user.dir` 找 `every-agent-plugins/`）。
- 验证是否加载：worker 日志出现 `[ask-user] 已激活`，或前端调用 `plugin.list` RPC。

## 打包分发（.eap，可选）

```powershell
node create-everyagent-plugin pack every-agent-plugins/ask-user --verify
```

产物 `ask-user-0.1.0.eap`（+ `.sha256` 旁文件）：zip 顶层目录名 = 插件 id，内含 `plugin.json` + `lib/*.jar`。安装走 worker 的 `plugin.install` RPC，重启 worker 生效。

## 更多文档

插件开发全量文档见仓库 `docs/plugin-guide/`。
