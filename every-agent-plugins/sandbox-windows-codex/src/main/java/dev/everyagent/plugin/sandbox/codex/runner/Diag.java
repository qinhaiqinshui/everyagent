package dev.everyagent.plugin.sandbox.codex.runner;

/**
 * runner 诊断打点总开关：<b>默认关</b>，环境变量 {@code EA_RUNNER_DIAG=1} 打开。
 *
 * <p>runner 进程内<b>不能用</b> slf4j/jul（2026-10-05 实测 {@code NoClassDefFoundError:
 * org/slf4j/LoggerFactory} 直接掀掉 runner——runner classpath 由 RunnerMaterializer 组装，
 * 不含日志框架；jul 的 ConsoleHandler 初始化时还绑定 tee 之前的旧 stderr），故诊断日志
 * 体系是「本开关 + {@code System.err.println} + installStderrTee 落 runner-stderr.log」。
 *
 * <p>挂本开关的是<b>纯诊断打点</b>（spawn 分段计时 file-timing/spawn-timing/stdio-prep、
 * calib 对照实验等）；功能性日志（verdict、uncaught、session 生命周期）不受开关控制，
 * 始终输出。
 */
final class Diag {

    /** 环境变量读取一次缓存；解析失败按关处理。 */
    static final boolean ON = isOn();

    private Diag() {
    }

    private static boolean isOn() {
        try {
            return "1".equals(System.getenv("EA_RUNNER_DIAG"));
        } catch (Throwable t) {
            return false;
        }
    }
}
