package dev.everyagent.plugin.api.config;

import java.nio.file.Path;

/**
 * Worker 配置只读接口（插件面向此接口编程，不直接依赖 WorkerProperties）。
 *
 * <p>按配置域拆为内嵌接口，方法名省略 {@code get/is} 前缀，返回值均为不可变快照或原始值。
 * 方法集仅覆盖插件实际调用的 getter，不做过度设计。
 *
 * <p>实现方（worker 侧）可提供一个适配器将 {@code WorkerProperties} 委托至此接口；
 * 插件通过 {@code ctx.getService(WorkerConfig.class)} 获取实例。
 */
public interface WorkerConfig {

    /**
     * 默认上下文窗口 token 数。
     *
     * <p>原定义于 {@code ContextOverflow.DEFAULT_CONTEXT_WINDOW_TOKENS}（值 256_000），
     * 该类依赖 AgentEntity 无法搬入 plugin-api，故在此常量暴露供插件直接引用。
     */
    long DEFAULT_CONTEXT_WINDOW_TOKENS = 256_000;

    /** 限流/护栏/上下文压缩等配置域。 */
    Limits limits();

    /** 模型调用重试配置域（空响应/瞬时错误退避）。 */
    Retry retry();

    /** 进程沙箱配置域。 */
    Sandbox sandbox();

    /** 危险操作授权配置域（PermissionGate）。 */
    Permissions permissions();

    /** 原生 git 执行配置域。 */
    Git git();

    /**
     * 系统目录绝对路径（空配置时取 {@code <user.home>/.everyagent}）。
     *
     * @return 系统目录绝对路径
     */
    Path resolveHomeDir();

    /**
     * 沙箱持久状态根目录绝对路径（空配置时取 {@code <系统目录>/sandbox}）。
     *
     * @return 沙箱持久根目录绝对路径
     */
    Path resolveSandboxPersistentRoot();

    /**
     * 系统技能目录绝对路径（空配置时取 {@code <系统目录>/skills}）。
     *
     * @return 技能目录绝对路径
     */
    Path resolveSkillsDir();

    /**
     * 程序附属文件目录(runtime)绝对路径——程序根下与 rg 二进制等核心附属文件
     * 同层的共享目录;打包 desktop 态 = {@code <resourcesPath>/runtime},源码
     * 开发态 = {@code <仓库根>/runtime}(程序根 = JVM 工作目录 user.dir,
     * 见架构 §7.17「程序附属文件」)。
     *
     * <p>插件的附属资源(如 sandbox-wsl-ubuntu 的 rootfs 镜像、eagent-run.py)
     * 由 desktop 构建链从插件 {@code runtime/} 子目录合并到此目录(插件
     * {@code enabled=false} 时不进包);插件应经本方法定位,而非假设 cwd。
     *
     * @return runtime 目录绝对路径(目录可能不存在,由调用方按需判断)
     */
    Path resolveRuntimeDir();

    // ================================================================
    // Limits
    // ================================================================

    interface Limits {

        AdaptiveMaxTokens adaptiveMaxTokens();

        int maxConcurrentTasks();

        /**
         * 一次用户提问(ask_user)等待答复的超时毫秒数,默认 30 分钟
         * (worker 配置 {@code worker.limits.ask-timeout-ms});消费方为 ask-user 插件。
         */
        long askTimeoutMs();

        long modelLengthStallMs();

        long lengthDisconnectMinTokens();

        ModelRate modelRate();

        boolean contextCompressionEnabled();

        double contextTriggerRatio();

        double contextTargetRatio();

        double contextSafetyRatio();

        long contextToolReserveTokens();

        int contextMaxToolResultChars();

        boolean contextOffsetEnabled();

        boolean contextSummaryEnabled();

        int contextSummaryMaxTokens();

        double tokenEstimatorConvergenceThreshold();

        int tokenEstimatorConvergenceSamples();

        double tokenEstimatorDriftThreshold();

        /**
         * 自适应输出预算配置（adaptive-max-tokens 插件）。
         * 检测 finish_reason=length 帧后自动放大 maxTokens 重试，达 ceiling 放弃。
         */
        interface AdaptiveMaxTokens {

            boolean enabled();

            /** ceiling 硬上限（tokens）；默认 262144（256K）。 */
            long ceiling();

            /** 升级倍率：budget = min(base × multiplier^attempt, ceiling)。 */
            double multiplier();

            /** 最大重试次数。 */
            int maxRetries();

            /** 低水位回落比例：连续 N 轮输出 < budget × ratio → 衰减回 base。 */
            double fallbackRatio();

            /** 低水位回落所需连续轮次。 */
            int fallbackRounds();
        }

        /**
         * 模型请求限流全局默认。
         * per-model 的 rpm/max-concurrency/tpm 在 worker.models[].params 各自配置；
         * 这里统一排队、tpm 估算与 EMA 校准的全局参数。
         */
        interface ModelRate {

            /** 每模型等待队列容量。 */
            int queueCapacity();

            /** 排队最长等待时间（ms）。 */
            long waitTimeoutMs();

            /** tpm 记账/估算滑动窗口（秒）。 */
            long estWindowSec();

            /** tpm 压力触发延迟的保守余量。 */
            double estSafetyRatio();

            /** 估算系数 EMA 学习率（0~1）。 */
            double estEmaAlpha();

            /** 估算系数下界保护。 */
            double estFactorMin();

            /** 估算系数上界保护。 */
            double estFactorMax();

            /** 全局默认 rpm。 */
            int defaultRpm();

            /** 全局默认最大并发。 */
            int defaultMaxConcurrency();

            /** 全局默认 tpm（0 = 不按 tpm 限流）。 */
            long defaultTpm();
        }
    }

    // ================================================================
    // Retry
    // ================================================================

    interface Retry {

        /** 策略常量：固定间隔退避。 */
        String STRATEGY_FIXED = "fixed";

        /** 策略常量：指数退避。 */
        String STRATEGY_EXPONENTIAL = "exponential";

        /** 空响应（无正文/无 reasoning/无工具调用）最大重试次数。 */
        int maxEmptyResponseRetries();

        /** 可重试瞬时错误（限流 429 / 5xx / 网络抖动）的最大退避重试次数。 */
        int maxRequestRetries();

        /**
         * 退避间隔（ms）：fixed 策略恒返回 backoffBaseMs；
         * exponential 策略返回 base × factor^(attempt-1)。attempt ≤ 0 按 1 计。
         *
         * @param attempt 重试序号（从 1 开始）
         * @return 退避毫秒数
         */
        long backoffMs(long attempt);
    }

    // ================================================================
    // Sandbox
    // ================================================================

    interface Sandbox {

        /** 是否启用 OS 级沙箱；关闭则 exec 直接 spawn。 */
        boolean enabled();

        /** 单命令看门狗超时（ms）。 */
        long timeoutMs();

        /**
         * 生效的内存上限（MB）：解析 auto(-1) 语义——
         * 读取系统总物理内存，&lt; 8GB 返回 0（不限制），≥ 8GB 返回总内存的 70%；
         * 显式值（含 0 = 不限制）原样返回。
         *
         * @return 生效内存上限（MB），0 = 不限制
         */
        long resolveMemoryLimitMb();

        /** CPU 硬上限百分比（0-100）；0 = 不限制。 */
        int cpuHardCapPercent();

        /** 作业对象活动进程数上限（含 shell 自身，≥1）；0 = 不限制。 */
        int activeProcessLimit();

        /** 是否允许沙箱内命令访问网络。 */
        boolean allowNetwork();

        /** 沙箱后端类型（auto / wsl-direct / wsl-bwrap / windows-mic / none）。 */
        String type();

        /** WSL 后端专属配置。 */
        Wsl wsl();

        interface Wsl {

            /** WSL 发行版名称。 */
            String distro();

            /** rootfs tarball 路径。 */
            String tarball();
        }
    }

    // ================================================================
    // Permissions
    // ================================================================

    interface Permissions {

        /** AI 审议超时（ms）；超时按拒绝对待（fail-closed）。 */
        long reviewTimeoutMs();

        /** 审议专用模型 configId；空 = 使用任务当前模型。 */
        String reviewModel();

        /** AI 审议超时/异常缺省按拒绝处理（fail-closed）；false = 回退人工弹窗。 */
        boolean reviewDenyOnError();
    }

    // ================================================================
    // Git
    // ================================================================

    interface Git {

        /** git 可执行文件绝对路径；空 = 自动探测。 */
        String executable();

        /** 单个 git 命令超时（ms）。 */
        long timeoutMs();
    }
}
