---
title: 调试与测试
nav_order: 15
parent: guides
has_children: false
---

**一句话定位**：本篇回答三个问题——后端插件**怎么写单测**（JUnit5 范式 + 测试桩现状）、前端改动**怎么自检**（typecheck → build → 控制台）、出了问题**去哪看**（worker 日志文案全集 + 五个高频剧本）。命令都是可复制的；凡没跑过的都标了「未实测」。

## 1. 后端测试范式

### 1.1 铁律：测试只依赖 plugin-api

插件 pom 的依赖清单就是测试的依赖边界——`every-agent-plugin-api` + `spring-boot-starter-test`（test scope），仅此两项（范例：`every-agent-plugins/system-info/pom.xml:51-57`；仓库内带测试的插件 pom 逐一核对全部如此）。`every-agent-worker` 任何坐标**任何 scope 都不许出现**（[`../../ARCHITECTURE.md`](../../ARCHITECTURE.md) §14.9 红线）：worker 是宿主，不是插件的可依赖库；你的测试要测的是「我的 Advisor / 我的工具 / 我的注册逻辑」，不是 worker 的行为。

带来的直接推论：**插件测试里没有 Spring 上下文、没有 worker 运行时**。被测对象直接 `new`，依赖用 Mockito mock，plugin-api 的接口类型（`WorkerConfig`、`StreamEmitter`、`TaskLifecycleContext` 等）都可以直接 mock——它们就在你的编译类路径上。

摆放与命名跟着仓库惯例走：测试类放 `src/test/java` 下与被测类同包、命名 `<被测类>Test`（如 `SystemInfoAdvisorTest`），Maven surefire 默认捡走 `*Test.java`——脚手架生成的工程已自带一个 `SmokeTest`，照着扩即可。

### 1.2 真实范例一：Advisor 测试怎么写（system-info）

`every-agent-plugins/system-info/src/test/java/dev/everyagent/plugin/sysinfo/SystemInfoAdvisorTest.java` 全文 5 个用例，结构是标准的「构造 → 行为断言」，零 Spring：

```java
// 类骨架（SystemInfoAdvisorTest.java:25）
class SystemInfoAdvisorTest {

    @Test
    void orderIsHighestPlus50() {                       // :27-31 断言注册 order
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 50,
                new SystemInfoAdvisor("ws", null).getOrder());
    }

    @Test
    void beforeInjectsSystemMessageAfterLeadingSystemArea() {   // :34-49
        ChatClientRequest out = new SystemInfoAdvisor("/c/test", null)
                .before(requestWith("你是助手", "你好"), mock(AdvisorChain.class));
        List<Message> instructions = out.prompt().getInstructions();
        assertTrue(instructions.get(0) instanceof SystemMessage); // 原 system 仍在最前
        // ... 注入位置与末位 user 保持的断言
    }

    // 辅助：手工拼一个最小 ChatClientRequest（:91-96）
    private static ChatClientRequest requestWith(String systemText, String userText) {
        List<Message> instructions = new ArrayList<>();
        instructions.add(new SystemMessage(systemText));
        instructions.add(new UserMessage(userText));
        return ChatClientRequest.builder()
                .prompt(new Prompt(instructions, mock(ChatOptions.class))).build();
    }
}
```

三个可抄的点：

- **Advisor 本体单独可测**：`before(request, chain)` 的 `chain` 参数 mock 掉即可（`mock(AdvisorChain.class)`），因为注入类 Advisor 在 before/after 里根本不调链——链调用是 Spring AI 框架的事；
- **断言落在消息序列上**：`out.prompt().getInstructions()` 拿到改动后的消息列表，按位置断言「原 system 仍在首、注入插在哪、user 仍在末」——这是 Advisor 行为测试的核心手法；
- **order 是第一等测试对象**：order 决定链上位置（见[Advisor 扩展点](../backend/advisors.md) §2 坐标图），把它写进断言防止无意改动。

### 1.3 真实范例二：多 SPI 协作对象的测试（task-queue）

task-queue 测的是 `TaskQueue`（纯计数/事件广播）与 `QueueAdmissionNode`（生命周期节点）两个非 Advisor 对象，演示另外三个手法：

| 手法 | 出处 | 说明 |
| --- | --- | --- |
| mock plugin-api 的值类型接口 | `TaskQueueTest.java:21-27`（`mock(WorkerConfig.class)` + `when(config.limits()).thenReturn(limits)`） | 配置读取被 stub 掉，测试不碰真实配置文件 |
| 虚拟线程测阻塞语义 | `QueueAdmissionNodeTest.java:65` 起的 `blocksWhenPermitsExhausted`（`:80` 处 `Thread.ofVirtual().start(...)` + `Thread.sleep(200)` 观察仍阻塞） | 排队/唤醒这类时序行为，仓库现行测试就是「睡 200ms 断言状态」的朴素写法，可直接沿用 |
| `verify` 断言事件广播 | `TaskQueueTest.java:88-89`、`QueueAdmissionNodeTest.java:176-177`（`verify(eventSink, atLeastOnce()).fanout(any(), eq("task.queued"), any(), any(), any())`） | 侧效应（发事件）用 Mockito verify 而非返回值断言 |
| 异常路径不 release | `QueueAdmissionNodeTest.java:137-148`（`doThrow(InterruptedException)` 于 `:140`，`verify(mockQueue, never()).release(anyString())` 于 `:148`） | 「失败时不做某事」用 `never()` |

注意 `TaskChain` 是函数式接口，测试里直接 lambda 当 `next` 节点（`c -> TaskOutcome.done(0, 0)`），洋葱模型的下游就这么被替身掉。

### 1.4 官方测试桩现状：没有，手写一个

如实登记：**plugin-api 不附带任何 `WorkerPluginContext` 的 fake / 测试实现**。plugin-api 自己的 `src/test` 只有两个与插件无关的自测（`shell/ExecResultsClixmlTest.java`、`util/SecretPatternsTest.java`）；全仓 25 个内置插件的测试里 `implements WorkerPluginContext` **零命中**——因为它们全都绕开 ctx，直接测被注册的类本体（§1.2/§1.3 的做法）。

但有一个场景绕不开 ctx：**你想断言「`activate()` 到底注册了什么」**（注册没走到 = 插件没生效的最常见根因，见 §4 剧本 a）。此时手写一个记录型 stub，把每次注册记进集合，测试直接断言集合内容。完整代码如下（已对本地 Maven 仓库的 `dev.everyagent:every-agent-plugin-api:1.0.0` jar 用 `javac` 编译验证通过；接口签名与 `WorkerPluginContext.java:42-94` + `TaskPluginContext.java:17,24` 的 15 个注册方法逐一核对）：

```java
// 放进你插件的 src/test/java 下任意包；用不到的记录项可删，但接口方法必须全实现
import dev.everyagent.plugin.api.PluginConfig;
import dev.everyagent.plugin.api.WorkerPluginContext;
import dev.everyagent.plugin.api.WorkerServices;
import dev.everyagent.plugin.api.model.ChatModelEnhancer;
import dev.everyagent.plugin.api.permission.AuthorizationHandler;
import dev.everyagent.plugin.api.rpc.RpcMethod;
import dev.everyagent.plugin.api.skill.SkillContributor;
import dev.everyagent.plugin.api.slash.SlashProvider;
import dev.everyagent.plugin.api.slash.SlashTokenResolver;
import dev.everyagent.plugin.api.spi.AdvisorProvider;
import dev.everyagent.plugin.api.spi.FileReferenceHandler;
import dev.everyagent.plugin.api.spi.SandboxProvider;
import dev.everyagent.plugin.api.spi.SearchProvider;
import dev.everyagent.plugin.api.spi.TokenEstimator;
import dev.everyagent.plugin.api.spi.ToolExecutionInterceptor;
import dev.everyagent.plugin.api.spi.ToolProvider;
import dev.everyagent.plugin.api.task.TaskAdmissionPolicy;
import dev.everyagent.plugin.api.task.TaskLifecycleNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 记录型 WorkerPluginContext 测试桩：把 activate() 里的每次注册记进公开集合。 */
class RecordingPluginContext implements WorkerPluginContext {

    final List<ToolProvider> toolProviders = new ArrayList<>();
    final List<AdvisorProvider> advisorProviders = new ArrayList<>();
    final List<SandboxProvider> sandboxProviders = new ArrayList<>();
    final List<AuthorizationHandler> authorizationHandlers = new ArrayList<>();
    final List<ToolExecutionInterceptor> toolInterceptors = new ArrayList<>();
    final Map<String, RpcMethod> rpcMethods = new LinkedHashMap<>();
    final Map<String, SlashProvider> slashProviders = new LinkedHashMap<>();
    final List<TaskLifecycleNode> lifecycleNodes = new ArrayList<>();

    private final String pluginId;

    RecordingPluginContext(String pluginId) { this.pluginId = pluginId; }

    @Override public String pluginId() { return pluginId; }
    @Override public Path pluginDir() { return Path.of("build/test-plugin-" + pluginId); }

    @Override public void registerToolProvider(ToolProvider provider) { toolProviders.add(provider); }
    @Override public void registerAdvisorProvider(AdvisorProvider provider) { advisorProviders.add(provider); }
    @Override public void registerSandboxProvider(SandboxProvider provider) { sandboxProviders.add(provider); }
    @Override public void registerSearchProvider(SearchProvider provider) { /* 按需记录 */ }
    @Override public void registerAuthorizationHandler(AuthorizationHandler handler) { authorizationHandlers.add(handler); }
    @Override public void registerToolExecutionInterceptor(ToolExecutionInterceptor interceptor) { toolInterceptors.add(interceptor); }
    @Override public void registerSkillContributor(SkillContributor contributor) { }
    @Override public void registerTokenEstimator(TokenEstimator estimator) { }
    @Override public void registerChatModelEnhancer(ChatModelEnhancer enhancer) { }
    @Override public void registerFileReferenceHandler(FileReferenceHandler handler) { }
    @Override public void registerRpcMethod(String method, RpcMethod handler) { rpcMethods.put(method, handler); }
    @Override public void registerSlashProvider(String id, SlashProvider provider) { slashProviders.put(id, provider); }
    @Override public void registerSlashTokenResolver(SlashTokenResolver resolver) { }
    @Override public void registerTaskAdmissionPolicy(TaskAdmissionPolicy policy) { }
    @Override public void registerTaskLifecycleNode(TaskLifecycleNode node) { lifecycleNodes.add(node); }

    @Override public WorkerServices services() { throw new UnsupportedOperationException("测试桩不提供服务"); }
    @Override public PluginConfig config() { throw new UnsupportedOperationException("测试桩不提供配置"); }
    @Override public <T> T getService(Class<T> type) { throw new UnsupportedOperationException("测试桩不提供 getService"); }
}
```

用法（断言 activate 注册面）：

```java
@Test
void activateRegistersExactlyOneToolProvider() {
    RecordingPluginContext ctx = new RecordingPluginContext("my-tool");
    new MyToolPlugin().activate(ctx);                 // 你的入口类
    assertEquals(1, ctx.toolProviders.size());        // 注册了、且只注册了一个
}
```

### 1.5 spring-boot-starter-test 里实际用到的能力

对全部插件测试源码的 import 实测统计（这是「能用什么」的地面真相，不是 starter 的宣传单）：

| 能力 | 实测使用 | 备注 |
| --- | --- | --- |
| JUnit 5（`org.junit.jupiter`） | `@Test` 50 处、`@TempDir` 18 处、`@BeforeEach` 6 处、`Assumptions` 6 处 | 插件测试的主力；`@TempDir` 用于文件类插件 |
| Mockito | 15 个测试文件：`mock` / `when` / `verify` / `never` / `times` / `doThrow` / `doReturn` / `ArgumentCaptor` / `ArgumentMatchers.*` | 由 starter-test 传递引入，无需单独声明 |
| Spring AI 类型 | `Prompt` / `Message` / `ChatClientRequest` / `ChatModel` / `ChatResponse` 等 | 经 `every-agent-plugin-api` 传递进编译类路径，测试可直接构造 |
| AssertJ | **零使用** | 能用但没人用；仓库惯例是 JUnit 原生断言，新代码保持一致即可 |

一个 JDK 25 环境的已知噪音：Mockito 的 inline-mock-maker 会动态自挂 Java agent，测试启动时打印 `WARNING: A Java agent has been loaded dynamically ...` 与 `Mockito is currently self-attaching ...` ——无害，不影响结果（本文实测 system-info 测试全绿时即有此警告）。

## 2. 前端验证

前端没有单测框架，自检闭环是三步：**类型检查 → 构建 → 浏览器控制台**。

### 2.1 typecheck：宿主 tsconfig 替你覆盖

仓库内（builtin 形态）插件的 `web/*.ts` **不需要自带 tsconfig**：宿主 `every-agent-web/tsconfig.json:32` 的 `include: ["src", "../every-agent-plugins/*/web"]` 把所有插件 web 目录纳入，`:22` 的 `paths` 又把 `@everyagent/plugin-api` 指到 `../every-agent-plugin-api/js/index.ts` 的真源码类型。所以：

```powershell
cd every-agent-web
npm.cmd run typecheck     # tsc --noEmit；脚手架模板验证记录：约 3.8s（PowerShell 下须 npm.cmd）
```

零报错 = 你的 `import type` 用法、`ctx.ui.register*` 的参数形状全部过关。注意 typecheck **不产出任何东西**，产物要靠下一步。standalone 形态的插件用自带的 `tsconfig.json` + `vendor/` 类型副本，各自 `tsc --noEmit`，原理相同（脚手架生成，见[快速开始](../getting-started.md)）。

### 2.2 build:plugins 与 watch

```powershell
cd every-agent-web
npm.cmd run build:plugins         # 全量构建，产物写回 every-agent-plugins/<id>/web/index.js(+.css/.map)
npm.cmd run watch:plugins         # 常驻 watch：首次全量构建后监听变更自动重编，Ctrl+C 退出
```

- 全量构建的预期输出（`every-agent-web/scripts/build-plugins.mjs:166,183` 逐字）：`[build-plugins] 发现 N 个插件入口:`（逐行列出 id）与 `[build-plugins] 完成，共构建 N 个插件。`；脚手架验证记录：全量不足 1 秒（0.4s）。
- `watch:plugins` 与 `--only` **已是实态**（`every-agent-web/package.json:10` 的 `watch:plugins` script；esbuild 已显式声明为 devDependency 0.21.5）：watch 模式打印 `[build-plugins] watch 模式:首次构建后监听变更自动重编，Ctrl+C 退出。`（`build-plugins.mjs:130`）、`[build-plugins] 监听中…`（`:140`）；可组合成 `node scripts/build-plugins.mjs --only git,subagent --watch` 只盯自己的插件（`:20-22` 用法注释、`:124` 过滤提示）。
- **watch 省的只是构建这一步**：宿主页面加载的是 blob URL 快照，改完代码仍要**手动刷新浏览器**才生效——前端没有热替换。

### 2.3 浏览器控制台：两条日志定成败

前端 loader 只打两条插件日志（`every-agent-web/src/plugin/pluginLoader.ts` 逐字）：

- 成功：`[plugins] 插件已激活: <id> (<name>)`（`:406`，`activate(ctx)` 正常返回后）；
- 失败：`[plugins] 插件 <id> 加载失败:` + 异常对象（`:408`，取 webSource / import / activate 任一环节抛错）。

两条都没有 ≠ 加载成功，更可能是**被过滤了**（`plugin.list` 拿不到/`hasWebMain=false`/被禁用，过滤条件 `active && hasWebMain && !disabled` 在 `:362-367`）——这个分支**静默无日志**（连 `plugin.list` RPC 失败都只是静默 return，`:357-359`），排查走 §4 剧本 c。

### 2.4 最小改-验循环

```text
改 web/index.ts 一行
  → npm.cmd run build:plugins（或开着 watch:plugins 等自动重编）
  → 浏览器刷新（F5）
  → 控制台找 `[plugins] 插件已激活: <id>`，UI 上看效果
```

前端改动**不需要重启 worker**：页面每次加载都经 `plugin.webSource` RPC 现拉 `web/index.js` 内容。需要重启的是后端 java 改动（重新 `mvn package` + 重启 worker）。

standalone 形态的插件不进 `build:plugins` 的扫描（脚本只扫 `every-agent-plugins/<id>/web/index.ts`），改完跑自己工程里的 `node scripts/build.mjs` 再刷新——循环其余步骤不变。

## 3. 后端调试

### 3.1 worker 日志在哪

两路同时输出（`every-agent-worker/src/main/resources/logback-spring.xml:63-65`，root 级别 INFO，CONSOLE + FILE 双 appender）：

- **启动控制台**：开发时最直接；
- **日志文件**：`<home>/logs/worker.log`，每日滚动成 `worker.<yyyy-MM-dd>.log`、保留 7 天（`logback-spring.xml:15,18-22`）。`<home>` 解析优先级：`EVERYAGENT_HOME` 环境变量 → `worker.home-dir` 配置（默认 `~/.everyagent`，`application.yml:19`；dev profile 为 `~/.everyagent-dev`，`application-dev.yml:11`）。

按插件 id 过滤一行命令：

```powershell
Select-String -Path "$HOME\.everyagent\logs\worker.log" -Pattern "my-tool"
# dev profile 启动则用 $HOME\.everyagent-dev\logs\worker.log
```

`logging.level` 起点在 `every-agent-worker/src/main/resources/application.yml:163`（`dev.everyagent` 默认 INFO；`dev.everyagent.worker.tools` / `.task` 等包已是 DEBUG）；要临时调某个包，用 `--logging.level.dev.everyagent.plugin.<你的包>=DEBUG` 覆盖。

### 3.2 插件日志文案全集（逐字，grep 用）

加载链路（`PluginLoader.java`）与内置扫描（`BuiltInPluginScanner.java`）的全部插件相关 INFO/WARN：

| 级别 | 文案（`{}` 为占位） | 行号 | 含义 |
| --- | --- | --- | --- |
| info | `[plugins] 扫描到 {} 个插件(来自 {} 个扫描器)` | PluginLoader.java:180 | 扫描完成计数 |
| info | `[plugins] 加载完成: {}/{} 个插件成功加载` | PluginLoader.java:186 | 加载汇总；分母≠分子时逐个对下面的 WARN |
| info | `[plugins] 插件已激活: id={} name={} v{} entry={} source={}` | PluginLoader.java:337 | **激活成功的唯一标志** |
| info | `[plugins] 声明式插件已注册: id={} name={} v{} (无 Java 入口,source={})` | PluginLoader.java:263 | 无 `main` 的纯 web 插件走这条 |
| info | `[plugins] 插件已禁用,跳过激活: id={} name={} (source={})` | PluginLoader.java:241 | 在 `.disabled-plugins` 名单里 |
| warn | `[plugins] 未扫描到任何插件` | PluginLoader.java:172 | 先查 cwd（内置目录按 user.dir 解析，见[构建与运行](build-and-run.md)） |
| warn | `[plugins] 插件清单不存在,跳过: {} (source={})` | PluginLoader.java:203 | 目录根缺 plugin.json |
| warn | `[plugins] 插件清单解析失败,跳过: {} ({})(source={})` | PluginLoader.java:210 | JSON 语法错（⚠️ 见下方特例） |
| warn | `[plugins] 插件 {} 已加载,跳过重复: {} (source={})` | PluginLoader.java:222 | 同 id 重复，先到先得 |
| warn | `[plugins] 插件 {} 声明了入口类但无可用 jar (source={})` | PluginLoader.java:287 | 内置插件没跑 `mvn package` |
| warn | `[plugins] 插件 {} 的入口类 {} 未实现 EveryAgentPlugin 接口` | PluginLoader.java:312 | `main` 写错类 |
| warn | `[plugins] 插件 {} 激活失败: {}` | PluginLoader.java:341 | **activate() 抛异常**，见 §3.3 |
| warn | `[plugins] 插件 {} 的 lib 目录不可读,跳过: {}` | PluginLoader.java:279 | 外部插件 lib/ 布局问题 |
| warn | `[plugins-builtin] 内置插件未构建,请先 mvn package: {}` | BuiltInPluginScanner.java:95 | 有清单有 target/classes 但缺 jar |
| warn | `[plugins-builtin] 内置插件源码目录不存在,跳过扫描: {}(桌面安装包出现此行 = 打包未含 every-agent-plugins)` | BuiltInPluginScanner.java:60 | cwd 下没有 every-agent-plugins/ |

⚠️ **plugin.json 语法错的特例**：清单解析的 catch 只接 `IOException`（`PluginLoader.java:210` 所在分支），而 jackson 的解析异常是 `RuntimeException` 子类——尾逗号、注释这类语法错误会**穿透逐插件的循环直接冒出 `@PostConstruct`，worker 启动失败**（异常类型已实测；启动失败复现未实测，详见[故障排查](troubleshooting.md)）。

### 3.3 「单插件失败不拖垮 worker」的日志表现

激活是逐插件 try/catch 的（`PluginLoader.java:340-343`）：`activate()` 抛任何 `Exception` 都被接住，worker 继续加载下一个。日志表现两点：

1. **堆栈会打**：`log.warn("[plugins] 插件 {} 激活失败: {}", id, e.getMessage(), e)` 第三参就是 Throwable——WARN 级别下**完整堆栈直接跟在那行后面**，不用调日志级别就能看到 activate 死在哪一行；
2. **plugin.list 会骗你**：失败插件被记成 `status = "激活失败: <消息>"`（`LoadedPlugin`），但 `plugin.list` 的 `active` 字段只看禁用名单（`!isDisabled`），**激活失败的插件照样报 `active=true`**。判断「真的跑起来了没有」必须 grep 日志里的 `插件已激活: id=<你的id>`（`PluginLoader.java:337`），两个可见性陷阱详见[持久化与状态](../backend/persistence-and-state.md) §4。

### 3.4 IDE 远程调试（一句话）

给 worker 挂 JDWP（如 `java -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 -jar ...`，入口类 `dev.everyagent.worker.WorkerApplication`），IDE 连 Remote JVM Debug 后可在插件代码里下断点——插件的类经 `URLClassLoader` 加载，断点与变量查看按普通类对待即可。**未实测**，仅给出标准姿势。

## 4. 常见调试场景剧本

先按第一眼症状对号，每条剧本都是「症状 → 定位步骤 → 修复」三段：

| 第一眼症状 | 直接跳 |
| --- | --- |
| 后端生效类问题（Advisor / 工具没动静） | 剧本 a / b |
| 前端无踪影、无报错 | 剧本 c |
| 事件回调永不触发 | 剧本 d |
| RPC 报错或无响应 | 剧本 e |

### 剧本 a：Advisor 没生效（模型行为毫无变化）

**症状**：插件日志显示「插件已激活」，但 agent 行为看不出 Advisor 在场。
**定位**：

1. 先确认注册真的发生了：日志 grep `插件已激活: id=<id>`（区分「激活失败: ...」`PluginLoader.java:341`）；更硬的证据是单测——用 §1.4 的 `RecordingPluginContext` 断言 `activate(ctx)` 后 `ctx.advisorProviders.size() == 1`。`activate()` 里注册代码放在条件分支后面/提前 return 是高频根因。
2. `appliesTo` 把自己拦了：`AdvisorProvider.appliesTo` 缺省返回 `true`（`every-agent-plugin-api/src/main/java/dev/everyagent/plugin/api/spi/AdvisorProvider.java:30`），但只挂主 agent 之类的分流逻辑写错（比较错 `agentId`）时直接全程 false——分流范例见[Advisor 扩展点](../backend/advisors.md) §1.4。
3. order 撞车：对照[Advisor 扩展点](../backend/advisors.md) §2 的全链 order 坐标图，别复用内置已占用的偏移；注册表本身零校验（不会替你拒绝坏 order），撞了不报错、只表现为链上位置不对。
**修复**：补注册断言单测；按 advisors.md §2.6 的空档选 order；appliesTo 判据照 §1.4 范例收敛。

### 剧本 b：工具注册了，AI 就是不调用

**症状**：`插件已激活` 在日志里，模型对话里从不出现你的工具。
**定位**：

1. `ToolProvider.appliesTo` 返回 false：命令类工具的惯例判据是当前沙箱后端（`ctx.sandbox() != null && "<后端id>".equals(ctx.sandbox().id())`，范例 `every-agent-plugins/sandbox-windows-mic/src/main/java/dev/everyagent/plugin/sandbox/mic/WindowsMicShellToolProvider.java:41-43`）——后端没选中你的工具就不存在，见[工具与沙箱](../backend/tools-and-sandbox.md) §1.4。
2. `createTools` 拿不到依赖静默返回空：`shellExecutor()` 为 null 时返回 `List.of()` 是对的姿势，但等于零工具（同文件 `:47-50`）。
3. 描述质量：工具 `@Tool` 描述没说清「何时该用」，模型自然不选——描述是给模型看的接口，写「做什么 + 什么时候用」，改完重新 `mvn package` + 重启 worker。
**修复**：逐条对照[工具与沙箱](../backend/tools-and-sandbox.md) §1.5 常见坑表（含「provider 里存 per-run 状态」「以为插件是 Spring bean」两条结构性错误）。

### 剧本 c：前端面板/图标不出现

**症状**：后端都激活了，浏览器里毫无踪影，控制台也**没有**报错。
**定位**（静默是这条链的特征，过滤在 `pluginLoader.ts:362-367`，不命中零日志）：

1. `plugin.list` 里 `hasWebMain` 是不是 true：`webMain` 缺失/为空 → 整体被过滤且无任何报错（[前端总览](../web/overview-and-loading.md) §1 的失败症状表）。
2. `build:plugins` 跑了没有：宿主硬编码加载 `web/index.js`（`webMain` 的值不被消费，只决定 `hasWebMain`；`pluginLoader.ts:423`）——`web/index.ts` 改了没重跑构建 = 页面还在拉旧产物。查 `every-agent-plugins/<id>/web/index.js` 的修改时间。
3. `active=false`？在不在 `.disabled-plugins` 名单（[持久化与状态](../backend/persistence-and-state.md) §3）。
4. 面板**出现了但内容不对**（被内置覆盖/顺序不合预期）：除侧边栏外扩展点没有 order，合并顺序是「插件在前」（[前端总览](../web/overview-and-loading.md) §5.2）。
**修复**：跑 `npm.cmd run build:plugins` → 确认 `webMain: "web/index.ts"` 在清单里 → 刷新页面；仍不行按 §2.3 看控制台两条日志。

### 剧本 d：事件收不到（`ctx.events.on` 永不触发）

**症状**：订阅写好了，回调一次都没进。
**定位**：

1. **先对照死事件清单**：38 个宿主事件里 24 个当前全仓**零 emit**（订阅无效），含 plugin-api 具名 9 个中的 4 个（`file-content-saved` / `task-created` / `task-deleted` / `task-trace-changed`）——逐个对照[前端事件](../web/events.md) §3.2 的表；`task-deleted` 还有「死订阅陷阱」先例（file-change 在订阅一个永不响的铃）。
2. 拼错事件名**不报错**：`PluginDomainEvent` 的 `(string & {})` 兜底放行任意字符串——挂个探针确认实际事件名：`ctx.events.on(名字, console.log)`（events.md §6 纪律）。
3. 想追流式输出？流式增量**不走这条总线**（`agent-message-streaming` / `task-trace-changed` 都是死事件），正确姿势是接 UI 扩展点让宿主替你渲染（events.md §4）。
**修复**：换 §3.1 的 14 个活事件，或改走扩展点/`ctx.sdk.rpc`。

### 剧本 e：RPC 调不通（`ctx.sdk.rpc` 报错或无响应）

**症状**：前端调自注册的 RPC 方法，报 NOT_FOUND / INTERNAL，或干脆没下文。
**定位**：

1. **worker 日志是第一现场**：`RpcDispatcher` 对坏请求打 `rpc 请求缺 reqId/method: {}`（`every-agent-worker/src/main/java/dev/everyagent/worker/rpc/RpcDispatcher.java:77`，WARN）；handler 执行抛异常打 `rpc {} 执行异常`（`:107`，ERROR 带完整堆栈）。先 grep 这两条。
2. 方法名逐字核对：`registerRpcMethod("<method>", ...)` 与前端 `ctx.sdk.rpc('<method>', ...)` 必须完全一致（域.动作命名约定见[任务与 RPC](../backend/task-and-rpc.md)）；方法没注册时按未知方法处理。
3. 参数形状：参数经 JSON 往返，键名/类型对不上时 handler 里取不到值——「执行异常」堆栈里通常直接能看到 NPE/取空。
**修复**：对着[任务与 RPC](../backend/task-and-rpc.md) 的注册与 ACL 章节（频道前缀 `u.<ownerKey>.`）核对；handler 侧用 §1.4 的 `rpcMethods` Map 单测验证注册名。

## 5. 测试命令速查表

| 目的 | 常规命令 | 说明 |
| --- | --- | --- |
| java：跑插件单测 | `mvn -f every-agent-plugins\<id>\pom.xml test` | 只跑到 surefire，**不产出 jar**；改完代码快速回归 |
| java：测试 + 打包 | `mvn -f every-agent-plugins\<id>\pom.xml package` | 先跑测试再出 jar（脚手架模板验证配方）；产物在 `target/` |
| java：沙箱受限配方 | `mvn -o "-Dmaven.repo.local=$HOME\.m2\repository" -f every-agent-plugins\<id>\pom.xml test` | 安装级 settings 把 localRepository 钉在不可写位置时用：离线 `-o` + 显式指到用户 .m2（本文实测 system-info：`Tests run: 5, Failures: 0`，全程 17.8s；`test`/`package` 均只写工作区内 `target/`） |
| web：类型检查 | `cd every-agent-web; npm.cmd run typecheck` | 宿主 tsconfig 覆盖全部 builtin 插件 web/（§2.1） |
| web：全量构建 | `cd every-agent-web; npm.cmd run build:plugins` | 产物写回各插件 `web/index.js`；PowerShell 下必须 `npm.cmd` |
| web：监听重编 | `cd every-agent-web; npm.cmd run watch:plugins`（可 `node scripts/build-plugins.mjs --only <id> --watch`） | 已是实态（§2.2）；刷新页面仍需手动 |
| 打包自检 | `node create-everyagent-plugin pack <pluginDir> --verify` | 五项 zip 自检，见[打包与安装](packaging-and-install.md) §2.4 |
| worker 日志定位 | `Select-String -Path <home>\logs\worker.log -Pattern "\[plugins\]"` | 文案全集见 §3.2 |

`test` 与 `package` 的区别一句话：`test` 止于测试阶段（适合纯回归）；`package` 会先跑同一套测试再打出 jar（发布/装机前用它，一步到位）。插件**不进根 reactor**，必须 `-f` 指到插件自己的 pom（原因与完整构建矩阵见[构建与运行](build-and-run.md)）。

## 6. 下一步读

- [故障排查](troubleshooting.md)——以 `LoadedPlugin.status` 文案与 worker WARN 逐条对号的排查表，本文 §3.2 的日志在那是「现象 → 根因 → 修复命令」三列表；
- [已知问题与现状偏差](../reference/known-issues.md)——死事件、假 Disposable、API 包未发布等现状登记，写代码前扫一眼少踩坑。
