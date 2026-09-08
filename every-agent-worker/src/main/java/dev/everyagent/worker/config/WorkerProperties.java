package dev.everyagent.worker.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * worker 配置(架构 §5.1)。
 * 支持向多个 hub 注册:hubs 列表每项 {url, apiKey, hubKey};hubs 是唯一配置入口,
 * 为空时不连接任何 hub。同一 apiKey 配多个 hub = 输出冗余扇出;
 * 不同 apiKey = 多用户共用一个 worker,apiKey 只用于连接认证,不决定任务存储/归属;
 * 任务统一存 data/tasks/<taskId>/(不存在顶层 worker.api-key——apiKey 按 hub 条目各自配置)。
 */
@ConfigurationProperties("worker")
public class WorkerProperties {

    private String workerId = "company-pc";
    /** 多 hub 注册列表(hubs 为唯一入口;空 = 不连任何 hub)。 */
    private List<HubConfig> hubs = new ArrayList<>();
    /** 系统目录(架构 §5.9):模型配置/默认工作区/数据;空 = ~/.everyagent。 */
    private String homeDir = "";
    /** 默认工作区(init 时注册进注册表)。空 = <系统目录>/workspace。 */
    private String workspaceRoot = "";
    /** worker 数据目录(任务落盘/工作区注册表)。空 = <系统目录>/data。 */
    private String dataDir = "";
    /** 系统技能目录(skill 知识包;空 = <系统目录>/skills)。AI 工具只读访问,写一律拒绝。 */
    private String skillsDir = "";
    /**
     * 程序资源根(仅用于授权忽略前缀等,不再是程序附属文件的定位基础):
     * 程序附属文件(rg、eagent-run.py、WSL 托管镜像)统一随安装/解压分发到
     * {@code <程序根>/runtime/},worker 以字面相对路径 {@code ./runtime} 按 JVM 工作目录
     * (user.dir)解析(见 {@link #resolveRuntimeDir()}),与本字段无关。
     * 空 = 用 codeSource 定位 jar 所在目录;desktop 打包态由 desktop 注入(仅影响授权忽略前缀)。
     */
    private String programDir = "";
    private long hubInitialBackoffMs = 1_000;
    private long hubMaxBackoffMs = 30_000;
    private Limits limits = new Limits();
    /** 模型调用重试配置(空响应/瞬时错误,仿 n 护栏全局默认,不随模型配置档切换)。 */
    private Retry retry = new Retry();
    /**
     * 模型调用超时(ms)。spring-ai 的 OpenAiChatOptions 默认 timeout = 60s
     * (AbstractOpenAiOptions.DEFAULT_TIMEOUT),对 reasoning 模型(深度思考期间可能
     * 长时间无 chunk)过短,会被 okhttp 超时主动 CANCEL 流(表现为
     * {@code StreamResetException: stream was reset: CANCEL})。默认 10 分钟。
     */
    private long modelTimeoutMs = 600_000;
    /** 进程沙箱配置(原生 Windows:Job Object + Restricted Token;其他平台安全降级)。 */
    private Sandbox sandbox = new Sandbox();
    /** 原生 git 执行配置(git.* RPC 由宿主原生 git argv 直传执行,架构 §7.12)。 */
    private Git git = new Git();
    /** 危险操作授权配置(PermissionGate:工作区外访问/危险命令须用户授权)。 */
    private Permissions permissions = new Permissions();
    /** 外部工具二进制配置(rg 等;rg-path 为空 = 程序根 runtime/bin/ 定位)。 */
    private Tools tools = new Tools();
    /** 模型配置(只读,config.get 的唯一数据源):默认在 application.yml,用户可在 application-worker.yaml 覆盖整表。 */
    private List<Model> models = new ArrayList<>();

    /**
     * 模型配置项(Spring 配置绑定用可变 POJO;ConfigStore 启动时转为不可变快照)。
     * 字段对应 ConfigDtos.ModelConfig;params 为自由 JSON 结构(temperature 等)。
     * provider = model-pool 时该条是「容灾池」:model 字段用逗号分隔的池成员 configId
     * 列表(首个 = 主模型),无 baseUrl/apiKey/模型名,实际请求由各成员模型发出。
     */
    public static class Model {
        private String configId;
        private String provider;
        private String baseUrl;
        private String model;
        private String apiKey;
        private Map<String, Object> params = new java.util.LinkedHashMap<>();
        private Boolean isDefault;

        public Model() {
        }

        public String getConfigId() {
            return configId;
        }

        public void setConfigId(String configId) {
            this.configId = configId;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public Map<String, Object> getParams() {
            return params;
        }

        public void setParams(Map<String, Object> params) {
            this.params = params == null ? new java.util.LinkedHashMap<>() : params;
        }

        public Boolean getIsDefault() {
            return isDefault;
        }

        public void setIsDefault(Boolean isDefault) {
            this.isDefault = isDefault;
        }
    }

    /** 单个 hub 注册项:{url, apiKey, hubKey} 一条连接,三者均必填。 */
    public static class HubConfig {
        private String url = "ws://localhost:9100/ws";
        private String apiKey = "";
        /**
         * 连接 hub 的凭证(对 hub 的保护);与 apiKey 不同,由部署者统一配置。
         * 必填——hub 侧存其 sha256 并强校验,缺失/不符连接被拒(resolveHubs 启动即拦截空值)。
         */
        private String hubKey = "";

        public HubConfig() {
        }

        public HubConfig(String url, String apiKey, String hubKey) {
            this.url = url;
            this.apiKey = apiKey;
            this.hubKey = hubKey == null ? "" : hubKey;
        }

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getHubKey() {
            return hubKey;
        }

        public void setHubKey(String hubKey) {
            this.hubKey = hubKey == null ? "" : hubKey;
        }
    }

    /**
     * 解析生效的 hub 列表(hubs 为唯一入口;空列表 = 不连任何 hub)。
     * url 为空的条目静默跳过(与既有语义一致);url 有效但 api-key 或 hub-key 缺失 → 抛异常
     * (HubPool @PostConstruct 随之失败,worker 拒绝启动——凭据必填,不再有"空 = 不携带"旁路)。
     */
    public List<HubConfig> resolveHubs() {
        List<HubConfig> out = new ArrayList<>();
        if (hubs != null) {
            for (int i = 0; i < hubs.size(); i++) {
                HubConfig h = hubs.get(i);
                if (h == null || h.getUrl() == null || h.getUrl().isBlank()) {
                    continue;
                }
                boolean apiKeyBlank = h.getApiKey() == null || h.getApiKey().isBlank();
                boolean hubKeyBlank = h.getHubKey() == null || h.getHubKey().isBlank();
                if (apiKeyBlank || hubKeyBlank) {
                    throw new IllegalStateException("worker.hubs[" + i + "](" + h.getUrl()
                            + ") 缺" + (apiKeyBlank ? " api-key" : "") + (hubKeyBlank ? " hub-key" : "")
                            + ":两者均必填(hub 强校验 hubKey,apiKey 定义数据归属),缺配置拒绝启动");
                }
                out.add(h);
            }
        }
        return out;
    }

    /** 系统目录绝对路径;配置为空时取 <user.home>/.everyagent。 */
    public java.nio.file.Path resolveHomeDir() {
        String h = homeDir == null || homeDir.isBlank() ? null : homeDir.trim();
        return (h == null ? java.nio.file.Path.of(System.getProperty("user.home"), ".everyagent")
                : java.nio.file.Path.of(h)).toAbsolutePath().normalize();
    }

    /** 数据目录绝对路径;配置为空时取 <系统目录>/data。 */
    public java.nio.file.Path resolveDataDir() {
        String d = dataDir == null || dataDir.isBlank() ? null : dataDir.trim();
        return (d == null ? resolveHomeDir().resolve("data") : java.nio.file.Path.of(d))
                .toAbsolutePath().normalize();
    }

    /** 沙箱持久状态根目录绝对路径;配置为空时取 <数据目录>/sandbox。 */
    public java.nio.file.Path resolveSandboxPersistentRoot() {
        String p = sandbox.getPersistentRoot() == null || sandbox.getPersistentRoot().isBlank()
                ? null : sandbox.getPersistentRoot().trim();
        return (p == null ? resolveDataDir().resolve("sandbox") : java.nio.file.Path.of(p))
                .toAbsolutePath().normalize();
    }

    /** 初始工作区绝对路径;配置为空时取 <系统目录>/workspace(默认工作区)。 */
    public java.nio.file.Path resolveInitialWorkspace() {
        String w = workspaceRoot == null || workspaceRoot.isBlank() ? null : workspaceRoot.trim();
        return (w == null ? resolveHomeDir().resolve("workspace") : java.nio.file.Path.of(w))
                .toAbsolutePath().normalize();
    }

    /** 系统技能目录绝对路径;配置为空时取 <系统目录>/skills。 */
    public java.nio.file.Path resolveSkillsDir() {
        String s = skillsDir == null || skillsDir.isBlank() ? null : skillsDir.trim();
        return (s == null ? resolveHomeDir().resolve("skills") : java.nio.file.Path.of(s))
                .toAbsolutePath().normalize();
    }

    /**
     * 程序资源根绝对路径;配置为空时用 codeSource 定位本类所在 jar 的目录(裸 jar 场景)。
     * desktop 已不再注入 {@code --worker.program-dir}(程序附属文件定位走 ./runtime)。
     * 仅用于授权忽略前缀(§5.5)等,不再是程序附属文件(runtime/)的定位基础——
     * 程序附属文件定位统一走 {@link #resolveRuntimeDir()}(字面相对 user.dir)。
     */
    public java.nio.file.Path resolveProgramDir() {
        String p = programDir == null || programDir.isBlank() ? null : programDir.trim();
        if (p != null) {
            return java.nio.file.Path.of(p).toAbsolutePath().normalize();
        }
        try {
            java.security.CodeSource cs = WorkerProperties.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                java.nio.file.Path loc = java.nio.file.Path.of(cs.getLocation().toURI());
                // jar 文件取父目录;classes 目录(IDE 运行)本身即根
                if (java.nio.file.Files.isRegularFile(loc)) {
                    loc = loc.getParent();
                }
                if (loc != null) {
                    return loc.toAbsolutePath().normalize();
                }
            }
        } catch (Exception ignored) {
            // codeSource 不可得:回退 user.dir
        }
        return java.nio.file.Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
    }

    /**
     * 程序附属文件目录绝对路径;恒为 {@code <程序根>/runtime}(程序根 = JVM 工作目录 user.dir)。
     * 程序附属文件(rg、eagent-run.py、WSL 托管镜像)随安装/解压分发到程序根下 runtime/,
     * worker 以字面相对路径 {@code ./runtime} 按 user.dir 解析——开发态(IDE 工作目录 = 仓库根)
     * 与打包态(desktop spawn 时 cwd = 程序根 resources 目录)都命中同一布局,与 program-dir 无关。
     */
    public java.nio.file.Path resolveRuntimeDir() {
        return java.nio.file.Path.of("runtime").toAbsolutePath().normalize();
    }

    /** 任务永久保留(用户删除是唯一出口),无 retention/trim 概念。 */
    public static class Limits {
        private int maxConcurrentTasks = 20;
        private int maxConcurrentSubs = 8;
        private long askTimeoutMs = 1_800_000;
        private long subWaitTimeoutMs = 300_000;
        private long maxEventsPerTask = 500_000;
        private long shipStallMs = 60_000;
        /**
         * 死循环检测阈值:连续 N 轮完全相同的工具调用(名称+参数集合签名)即收口。
         * ≤0 关闭检测。默认 3(与 novel_agent-n 运行护栏一致)。
         */
        private int maxRepeatedToolRounds = 3;
        /**
         * 上下文压缩开关(默认开;false = 完全关闭,恢复不压缩现状)。
         * 触发:每轮模型请求前估算用量 > 窗口×triggerRatio×safetyRatio 即压缩,
         * 压缩到 ≤ 窗口×targetRatio×safetyRatio(见 ContextCompressionAdvisor)。
         */
        private boolean contextCompressionEnabled = true;
        /** 上下文压缩触发阈值(窗口比例)。默认 0.95 = 用量超窗口 95% 触发。 */
        private double contextTriggerRatio = 0.95;
        /** 上下文压缩完成目标(窗口比例)。默认 0.50 = 压缩到窗口 50% 以内。 */
        private double contextTargetRatio = 0.50;
        /** 估算误差安全系数(预算与阈值按该系数下调,保守留余量)。默认 0.9。 */
        private double contextSafetyRatio = 0.9;
        /** 工具定义等固定预留 token(估算用量时累加,量级小:实测 tool input ~1.6k)。默认 4096。 */
        private long contextToolReserveTokens = 4096;
        /**
         * 是否启用「上轮实测 offset 校准」:true = 用上一轮实测用量校准上下文估算,
         * false = 回退纯 reserve 估算。默认 true。
         */
        private boolean contextOffsetEnabled = true;
        /** 是否启用 LLM 摘要压缩;false = 仅保留确定性 user 简版(仍压缩,不做 LLM 摘要)。默认 true。 */
        private boolean contextSummaryEnabled = true;
        /** 摘要输出 token 估算上限(LLM 摘要压缩时,摘要目标长度的估算值)。默认 512。 */
        private int contextSummaryMaxTokens = 512;
        /**
         * 单条 ToolResponseMessage 超过该字符数时做确定性截断(首尾保留,中段省略)。
         * 默认 40000。
         */
        private int contextMaxToolResultChars = 40000;

        public int getMaxConcurrentTasks() {
            return maxConcurrentTasks;
        }

        public void setMaxConcurrentTasks(int maxConcurrentTasks) {
            this.maxConcurrentTasks = maxConcurrentTasks;
        }

        public int getMaxConcurrentSubs() {
            return maxConcurrentSubs;
        }

        public void setMaxConcurrentSubs(int maxConcurrentSubs) {
            this.maxConcurrentSubs = maxConcurrentSubs;
        }

        public long getAskTimeoutMs() {
            return askTimeoutMs;
        }

        public void setAskTimeoutMs(long askTimeoutMs) {
            this.askTimeoutMs = askTimeoutMs;
        }

        public long getSubWaitTimeoutMs() {
            return subWaitTimeoutMs;
        }

        public void setSubWaitTimeoutMs(long subWaitTimeoutMs) {
            this.subWaitTimeoutMs = subWaitTimeoutMs;
        }

        public long getMaxEventsPerTask() {
            return maxEventsPerTask;
        }

        public void setMaxEventsPerTask(long maxEventsPerTask) {
            this.maxEventsPerTask = maxEventsPerTask;
        }

        public long getShipStallMs() {
            return shipStallMs;
        }

        public void setShipStallMs(long shipStallMs) {
            this.shipStallMs = shipStallMs;
        }

        public int getMaxRepeatedToolRounds() {
            return maxRepeatedToolRounds;
        }

        public void setMaxRepeatedToolRounds(int maxRepeatedToolRounds) {
            this.maxRepeatedToolRounds = maxRepeatedToolRounds;
        }

        public boolean isContextCompressionEnabled() {
            return contextCompressionEnabled;
        }

        public void setContextCompressionEnabled(boolean contextCompressionEnabled) {
            this.contextCompressionEnabled = contextCompressionEnabled;
        }

        public double getContextTriggerRatio() {
            return contextTriggerRatio;
        }

        public void setContextTriggerRatio(double contextTriggerRatio) {
            this.contextTriggerRatio = contextTriggerRatio;
        }

        public double getContextTargetRatio() {
            return contextTargetRatio;
        }

        public void setContextTargetRatio(double contextTargetRatio) {
            this.contextTargetRatio = contextTargetRatio;
        }

        public double getContextSafetyRatio() {
            return contextSafetyRatio;
        }

        public void setContextSafetyRatio(double contextSafetyRatio) {
            this.contextSafetyRatio = contextSafetyRatio;
        }

        public long getContextToolReserveTokens() {
            return contextToolReserveTokens;
        }

        public void setContextToolReserveTokens(long contextToolReserveTokens) {
            this.contextToolReserveTokens = contextToolReserveTokens;
        }

        public boolean isContextOffsetEnabled() {
            return contextOffsetEnabled;
        }

        public void setContextOffsetEnabled(boolean contextOffsetEnabled) {
            this.contextOffsetEnabled = contextOffsetEnabled;
        }

        public boolean isContextSummaryEnabled() {
            return contextSummaryEnabled;
        }

        public void setContextSummaryEnabled(boolean contextSummaryEnabled) {
            this.contextSummaryEnabled = contextSummaryEnabled;
        }

        public int getContextSummaryMaxTokens() {
            return contextSummaryMaxTokens;
        }

        public void setContextSummaryMaxTokens(int contextSummaryMaxTokens) {
            this.contextSummaryMaxTokens = contextSummaryMaxTokens;
        }

        public int getContextMaxToolResultChars() {
            return contextMaxToolResultChars;
        }

        public void setContextMaxToolResultChars(int contextMaxToolResultChars) {
            this.contextMaxToolResultChars = contextMaxToolResultChars;
        }
    }

    /**
     * 模型调用重试(空响应重试 + 瞬时错误退避重试,分别由两个 advisor 消费;
     * 默认值与 novel_agent-n 运行护栏 GUARDRAIL_SETTINGS_DEFAULTS 一致)。
     */
    public static class Retry {
        /** 空响应(无正文/无 reasoning/无工具调用)最大重试次数;耗尽收口为任务错误。 */
        private int maxEmptyResponseRetries = 2;
        /** 可重试瞬时错误(限流 429 / 5xx / 网络抖动)的最大退避重试次数。 */
        private int maxRequestRetries = 5;
        /** 退避基础间隔(ms)。 */
        private long backoffBaseMs = 3_000;
        /** 退避增长系数。 */
        private double backoffFactor = 5;

        /** 指数退避间隔:base * factor^(attempt-1)(与 n 的 computeRetryDelayMs 同式,无上限)。 */
        public long backoffMs(long attempt) {
            long a = Math.max(1, attempt);
            return Math.round(backoffBaseMs * Math.pow(backoffFactor, a - 1));
        }

        public int getMaxEmptyResponseRetries() {
            return maxEmptyResponseRetries;
        }

        public void setMaxEmptyResponseRetries(int maxEmptyResponseRetries) {
            this.maxEmptyResponseRetries = maxEmptyResponseRetries;
        }

        public int getMaxRequestRetries() {
            return maxRequestRetries;
        }

        public void setMaxRequestRetries(int maxRequestRetries) {
            this.maxRequestRetries = maxRequestRetries;
        }

        public long getBackoffBaseMs() {
            return backoffBaseMs;
        }

        public void setBackoffBaseMs(long backoffBaseMs) {
            this.backoffBaseMs = backoffBaseMs;
        }

        public double getBackoffFactor() {
            return backoffFactor;
        }

        public void setBackoffFactor(double backoffFactor) {
            this.backoffFactor = backoffFactor;
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setWorkerId(String workerId) {
        this.workerId = workerId;
    }

    public List<HubConfig> getHubs() {
        return hubs;
    }

    public void setHubs(List<HubConfig> hubs) {
        this.hubs = hubs;
    }

    public String getWorkspaceRoot() {
        return workspaceRoot;
    }

    public void setWorkspaceRoot(String workspaceRoot) {
        this.workspaceRoot = workspaceRoot;
    }

    public String getHomeDir() {
        return homeDir;
    }

    public void setHomeDir(String homeDir) {
        this.homeDir = homeDir;
    }

    public String getDataDir() {
        return dataDir;
    }

    public void setDataDir(String dataDir) {
        this.dataDir = dataDir;
    }

    public String getSkillsDir() {
        return skillsDir;
    }

    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    public String getProgramDir() {
        return programDir;
    }

    public void setProgramDir(String programDir) {
        this.programDir = programDir;
    }

    public long getHubInitialBackoffMs() {
        return hubInitialBackoffMs;
    }

    public void setHubInitialBackoffMs(long hubInitialBackoffMs) {
        this.hubInitialBackoffMs = hubInitialBackoffMs;
    }

    public long getHubMaxBackoffMs() {
        return hubMaxBackoffMs;
    }

    public void setHubMaxBackoffMs(long hubMaxBackoffMs) {
        this.hubMaxBackoffMs = hubMaxBackoffMs;
    }

    public Limits getLimits() {
        return limits;
    }

    public void setLimits(Limits limits) {
        this.limits = limits;
    }

    public Retry getRetry() {
        return retry;
    }

    public void setRetry(Retry retry) {
        this.retry = retry;
    }

    public long getModelTimeoutMs() {
        return modelTimeoutMs;
    }

    public void setModelTimeoutMs(long modelTimeoutMs) {
        this.modelTimeoutMs = modelTimeoutMs;
    }

    /** 进程沙箱配置。 */
    public static class Sandbox {
        /** 是否启用 OS 级沙箱;关闭则 exec 直接 spawn(仅超时/输出上限护栏)。 */
        private boolean enabled = true;
        /** 单命令看门狗超时(ms);超时中止子进程。 */
        private long timeoutMs = 1_800_000;
        /** 作业对象内存上限(MB);0 = 不限制。Windows Job Object 生效。 */
        private long memoryLimitMb = 512;
        /** CPU 硬上限百分比(0-100);0 = 不限制。Windows Job Object 生效。 */
        private int cpuHardCapPercent = 50;
        /**
         * 作业对象活动进程数上限(含 shell 自身,必须 ≥1)。
         * <p>1 = 禁子进程(shell 不能再启 rg/git 等任何子进程);注意 rgt 移除后
         * 模型是在沙箱 shell 内直接调 rg,该值必须 ≥2 才允许 rg 作为子进程运行。
         * 默认 32:允许 shell 内有限子进程树(rg/git/mvn 等),仍能防止失控进程树逃逸。
         * 0 = 不限制。Windows Job Object 生效。
         */
        private int activeProcessLimit = 32;
        /**
         * 网络出口策略:
         * <ul>
         *   <li>{@code deny-all} - 子进程不继承任何代理,且强制清空网络相关 env(默认,最严);</li>
         *   <li>{@code audit-only} - 允许网络但子进程 env 注入审计代理(代理层实现 allowlist)。</li>
         * </ul>
         * 注意:Job Object 管不了网络,deny-all 靠剥离代理 env + 父进程不提供代理实现;
         * 真网络隔离需配合本地代理(本版未实现,留 TODO)。
         */
        private String networkPolicy = "deny-all";
        /**
         * 是否允许沙箱内命令提权(root/sudo)。默认 false = 禁止:
         * <ul>
         *   <li>wsl-bwrap 后端:false 以发行版普通用户身份运行;true 加
         *       {@code --unshare-user --uid 0 --gid 0},在新 user namespace 内以 root
         *       运行(宿主侧仍为非特权,需内核允许非特权 userns,与 bwrap 同前置);</li>
         *   <li>windows-mic 后端:false = Restricted Token + Low IL 降权(默认);true =
         *       跳过降权,以当前进程 token 运行(保留 Medium IL 与既有特权);</li>
         *   <li>direct 后端(非 Windows):本就不降权,该开关无附加效果。</li>
         * </ul>
         */
        private boolean allowPrivilegeEscalation = false;
        /**
         * 是否启用 seccomp 内核级提权拦截(仅 wsl-bwrap 后端生效,见
         * docs/ARCHITECTURE.md §7.11):true 时命令内 exec setuid 二进制
         * (sudo/su 等)会先经 PermissionGate 授权(AI 审议优先 → 无人值守拒绝 → 人工弹窗),
         * 拒绝则该次 exec 返回 EPERM。默认 true;seccomp 不可用(旧内核/权限不足)时
         * 由 eagent-run.py 探测并退化为无拦截 + 日志告警。
         */
        private boolean interceptPrivilege = true;
        /**
         * 是否允许沙箱内命令访问网络。默认 false = 禁止(维持 deny-all 语义)。
         * true 强制放行(wsl-bwrap 不加 --unshare-net;direct/mic 不剥代理 env);
         * false 回落到 networkPolicy(deny-all 硬/软拒,audit-only 放行)。
         */
        private boolean allowNetwork = false;
        /**
         * worker 级共享持久状态(装一次、处处可用):true 时 wsl-bwrap 后端把持久根下的
         * home/opt/usr-local/resolv.conf 以读写绑定挂入沙箱,并注入持久 env 文件——
         * 所有任务/工作区共享同一份工具链与用户态配置(JDK/Maven/全局缓存等),不必重复安装。
         * windows-mic / direct 后端运行在宿主文件系统上,天然已持久,此开关主要控制持久 env 注入。
         */
        private boolean persistentState = true;
        /** 持久状态根目录(空 = &lt;数据目录&gt;/sandbox);worker 级共享,跨任务/重启保留。 */
        private String persistentRoot = "";

        /**
         * 沙箱类型(配置键 worker.sandbox.type;语义即“沙箱后端”,docs/ARCHITECTURE.md §7.10):
         * <ul>
         *   <li>{@code auto}(默认) - Windows 平台默认 wsl-direct(发行版可丢弃、root 完整权限);
         *       探测失败回退 windows-mic。非 Windows 无内核沙箱,直接 spawn;</li>
         *   <li>{@code wsl-direct} - 发行版 root 直连(automount 关闭 + 手动挂载工作区);
         *       AI 拥有发行版完整权限,装坏可重装整个发行版;</li>
         *   <li>{@code wsl-bwrap} - 显式启用:命令经 wsl.exe 进发行版、bubblewrap 挂载
         *       命名空间隔离(bind 白名单 + 只读基座);探测失败仍回退 windows-mic;</li>
         *   <li>{@code windows-mic} - 现有 Restricted Token + Low IL + 目录标注路径
         *       (Windows 原生,别名 {@code acl});</li>
         *   <li>{@code none} - 不隔离直接 spawn(同 sandbox.enabled=false)。</li>
         * </ul>
         * 别名:{@code acl} ≡ windows-mic,{@code wsl} ≡ wsl-bwrap,{@code direct} ≡ wsl-direct
         * (归一见 OsSandbox)。
         * 注意:wsl 系列后端要求 worker 自身为非降权进程(WSL 服务拒绝 Low-IL/restricted 调用方);
         * 非 Windows 平台任何取值都退化为直接 spawn(本版无内核级沙箱,配置无意义)。
         */
        private String type = "auto";
        /** WSL 后端专属配置。 */
        private Wsl wsl = new Wsl();

        /** WSL(wsl-bwrap)后端配置:发行版/只读岛/pwsh。 */
        public static class Wsl {
            /**
             * 发行版名:空(默认)= WSL 默认发行版(wsl -l -v 带 * 者,开发机通常即 Ubuntu)
             * ——机器无关的「已有可用」,免配置即可探测通过。生产托管路径:wsl --import
             * 导入 {@code eagent} 后显式配置(零污染基础层、interop 关闭)。
             * 须已安装 python3 与 bwrap(探测把关,失败断因见 {@code WslBwrapSandbox.probe})。
             */
            private String distro = "";
            /**
             * 托管发行版镜像路径(tar.gz,相对 worker 系统目录或绝对):仅作<b>兜底</b>——
             * 优先使用程序根 {@code ./runtime/wsl/eagent-rootfs.tar.gz}(随安装包
             * 分发、只读引用,见 {@code WslBwrapSandbox.tarballFor});此处配置在程序根
             * 无镜像时生效(兼容旧/手动放置)。文件在位且发行版缺失时,启动探测自动
             * {@code wsl --import eagent}(免管理员、离线;sha256 以同目录 {@code <镜像名>.sha256}
             * 把关,缺失/不符拒绝导入)。默认指向打包含义下的旧约定位置——开发机无此文件即
             * 自动关闭,零打扰;置空串显式关闭。
             */
            private String tarball = "wsl/eagent-rootfs.tar.gz";
            /**
             * 工作区内只读岛(工作区相对路径列表):可写区内的 ro 子路径(后挂载遮蔽先挂载)。
             * 默认空——agent 需要正常提交,.git 不默认保护(设计文档 §4.3 的修正)。
             */
            private List<String> roIslands = new ArrayList<>();
            /** 发行版内提供 pwsh(powershell 方言);默认 false,bash 为唯一方言。 */
            private boolean pwshEnabled = false;
            /**
             * bash 命令以登录 shell 执行(bash -lc):true 时每条命令自动加载 /etc/profile
             * 与 ~/.profile(即 ~/.bash_profile / ~/.profile),使 profile 里 export 的环境变量
             * 对每条命令持久生效。默认 true(自动加载 profile,环境变量持久生效);白名单环境
             * 重建仍保留,profile 里的 export 允许覆盖部分白名单变量。置 false 恢复
             * 纯白名单确定性(bash -c,不加载 profile)。
             */
            private boolean loginShell = true;

            public String getDistro() {
                return distro;
            }

            public void setDistro(String distro) {
                this.distro = distro;
            }

            public String getTarball() {
                return tarball;
            }

            public void setTarball(String tarball) {
                this.tarball = tarball;
            }

            public List<String> getRoIslands() {
                return roIslands;
            }

            public void setRoIslands(List<String> roIslands) {
                this.roIslands = roIslands;
            }

            public boolean isPwshEnabled() {
                return pwshEnabled;
            }

            public void setPwshEnabled(boolean pwshEnabled) {
                this.pwshEnabled = pwshEnabled;
            }

            public boolean isLoginShell() {
                return loginShell;
            }

            public void setLoginShell(boolean loginShell) {
                this.loginShell = loginShell;
            }
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Wsl getWsl() {
            return wsl;
        }

        public void setWsl(Wsl wsl) {
            this.wsl = wsl;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public long getMemoryLimitMb() {
            return memoryLimitMb;
        }

        public void setMemoryLimitMb(long memoryLimitMb) {
            this.memoryLimitMb = memoryLimitMb;
        }

        public int getCpuHardCapPercent() {
            return cpuHardCapPercent;
        }

        public void setCpuHardCapPercent(int cpuHardCapPercent) {
            this.cpuHardCapPercent = cpuHardCapPercent;
        }

        public int getActiveProcessLimit() {
            return activeProcessLimit;
        }

        public void setActiveProcessLimit(int activeProcessLimit) {
            this.activeProcessLimit = Math.max(0, activeProcessLimit);
        }

        public String getNetworkPolicy() {
            return networkPolicy;
        }

        public void setNetworkPolicy(String networkPolicy) {
            this.networkPolicy = networkPolicy;
        }

        public boolean isAllowPrivilegeEscalation() {
            return allowPrivilegeEscalation;
        }

        public void setAllowPrivilegeEscalation(boolean allowPrivilegeEscalation) {
            this.allowPrivilegeEscalation = allowPrivilegeEscalation;
        }

        public boolean isInterceptPrivilege() {
            return interceptPrivilege;
        }

        public void setInterceptPrivilege(boolean interceptPrivilege) {
            this.interceptPrivilege = interceptPrivilege;
        }

        public boolean isAllowNetwork() {
            return allowNetwork;
        }

        public void setAllowNetwork(boolean allowNetwork) {
            this.allowNetwork = allowNetwork;
        }

        public boolean isPersistentState() {
            return persistentState;
        }

        public void setPersistentState(boolean persistentState) {
            this.persistentState = persistentState;
        }

        public String getPersistentRoot() {
            return persistentRoot;
        }

        public void setPersistentRoot(String persistentRoot) {
            this.persistentRoot = persistentRoot;
        }

        /**
         * 统一网络判定:allowNetwork=true 显式放行;否则回落 networkPolicy,
         * 仅 deny-all 视为拒网(direct/mic 剥代理 env,wsl-bwrap 加 --unshare-net)。
         */
        public boolean networkDenied() {
            return !allowNetwork && "deny-all".equalsIgnoreCase(networkPolicy);
        }
    }

    /**
     * 原生 git 执行配置(git.* RPC 由 worker 宿主原生 git argv 直传执行,
     * 不经 wsl/mic 沙箱后端——git 是前端按钮触发的平台受控操作,见
     * docs/GIT_NATIVE_MIGRATION.md §2)。本配置承载可执行文件定位与命令超时。
     */
    public static class Git {
        /** git 可执行文件绝对路径(如 C:\\Program Files\\Git\\bin\\git.exe);空 = 自动探测(常见安装路径 + PATH)。 */
        private String executable = "";
        /** 单个 git 命令超时(ms);clone/pull/push 大仓库可能较慢,默认 5 分钟。 */
        private long timeoutMs = 300_000;

        public String getExecutable() {
            return executable;
        }

        public void setExecutable(String executable) {
            this.executable = executable;
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = timeoutMs;
        }
    }

    public Sandbox getSandbox() {
        return sandbox;
    }

    public void setSandbox(Sandbox sandbox) {
        this.sandbox = sandbox;
    }

    public Git getGit() {
        return git;
    }

    public void setGit(Git git) {
        this.git = git;
    }

    public Permissions getPermissions() {
        return permissions;
    }

    public void setPermissions(Permissions permissions) {
        this.permissions = permissions;
    }

    public Tools getTools() {
        return tools;
    }

    public void setTools(Tools tools) {
        this.tools = tools;
    }

    public List<Model> getModels() {
        return models;
    }

    public void setModels(List<Model> models) {
        this.models = models == null ? new ArrayList<>() : models;
    }

    /**
     * 外部工具二进制配置(ripgrep 等)。rg 二进制真源在程序根 {@code <程序根>/runtime/bin/}
     * (随安装/解压分发、运行时只读引用,见 §5.10),{@code rg-path} 仅是开发期可选覆盖项。
     */
    public static class Tools {
        /** ripgrep 可执行文件绝对路径(如 /opt/rg 或 C:\rg\rg.exe);为空 = 程序根 runtime/bin/ 定位。 */
        private String rgPath = "";

        public String getRgPath() {
            return rgPath;
        }

        public void setRgPath(String rgPath) {
            this.rgPath = rgPath;
        }
    }

    /**
     * 危险操作授权(PermissionGate,架构 §5.5 authorization 形态):
     * AI 工具的工作区外文件访问与危险命令(删除类等)须用户弹窗授权,
     * 拒绝/超时以错误文本回灌模型(循环不中断)。授权两档:本轮运行(内存)/本任务(grants.json)。
     */
    public static class Permissions {
        /**
         * 危险命令正则(大小写不敏感,在剥除引号段后的命令文本上匹配任意位置);命中即需授权。
         * 覆盖 cmd / PowerShell / POSIX 的删除类与磁盘破坏类动词。
         */
        private List<String> dangerousPatterns = List.of(
                // cmd(format 须后跟卷参数,避免 git log --format= 误报)
                "\\bdel\\b", "\\berase\\b", "\\brd\\b", "\\brmdir\\b", "\\bdiskpart\\b",
                "\\bformat\\b(?=\\s+(/\\S+\\s+)*[a-z]:)", "\\bcipher\\s+/w\\b",
                // PowerShell(含 alias)
                "\\bremove-item\\b", "\\bclear-content\\b", "\\bformat-volume\\b",
                // POSIX / 通用 alias
                "\\brm\\b(?=\\s)", "\\brmdir\\b", "\\bshred\\b", "\\bmkfs(\\.\\w+)?\\b", "\\bfdisk\\b",
                "\\bparted\\b");

        /**
         * 授权弹窗超时(ms);超时按拒绝处理。独立于 ask-timeout-ms(问答可等 30min,授权不必)。
         */
        private long authTimeoutMs = 300_000;

        /**
         * AI 审议超时(ms):privilege 授权走 AiAuthReviewer 时,单次 HTTP options.timeout
         * 与外层总预算硬闸的窗口;超时按拒绝对待(fail-closed)。
         */
        private long reviewTimeoutMs = 60_000;

        /**
         * AI 审议超时/异常缺省按拒绝处理(fail-closed);false = 回退人工弹窗,绝不因此放行。
         */
        private boolean reviewDenyOnError = true;

        /**
         * 可选:审议专用模型的 ConfigStore configId;为空则使用任务当前模型。
         */
        private String reviewModel = "";

        public List<String> getDangerousPatterns() {
            return dangerousPatterns;
        }

        public void setDangerousPatterns(List<String> dangerousPatterns) {
            this.dangerousPatterns = dangerousPatterns;
        }

        public long getAuthTimeoutMs() {
            return authTimeoutMs;
        }

        public void setAuthTimeoutMs(long authTimeoutMs) {
            this.authTimeoutMs = authTimeoutMs;
        }

        public long getReviewTimeoutMs() {
            return reviewTimeoutMs;
        }

        public void setReviewTimeoutMs(long reviewTimeoutMs) {
            this.reviewTimeoutMs = reviewTimeoutMs;
        }

        public boolean isReviewDenyOnError() {
            return reviewDenyOnError;
        }

        public void setReviewDenyOnError(boolean reviewDenyOnError) {
            this.reviewDenyOnError = reviewDenyOnError;
        }

        public String getReviewModel() {
            return reviewModel;
        }

        public void setReviewModel(String reviewModel) {
            this.reviewModel = reviewModel;
        }
    }
}
