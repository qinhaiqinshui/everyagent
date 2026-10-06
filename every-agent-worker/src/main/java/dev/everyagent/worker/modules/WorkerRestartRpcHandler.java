package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.RpcContext;
import dev.everyagent.worker.rpc.RpcDispatcher;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * worker.restart RPC(基础设施层,架构 §5.5):重启 worker 进程。
 *
 * <p>语义:应答 ok 后由独立非守护线程复走 {@code /admin/shutdown} 同款关闭路径
 * (优雅关闭 ApplicationContext:断开 hub 连接、销毁插件),随后以重建的启动命令
 * 把本进程重新拉起——<b>自重启,不依赖 desktop/任务计划等外部 supervisor</b>。
 *
 * <p>启动命令重建策略(重建失败时拒绝执行并应答 err,worker 不重启):
 * <ul>
 *   <li>优先 {@link ProcessHandle#current()} 的完整 argv(Linux 可用,保真含 JVM 选项);</li>
 *   <li>Windows 上 {@code arguments()}/{@code commandLine()} 实测(JDK 25)返回空,
 *       按 {@code sun.java.command} + {@code java.class.path} 重建最小命令:
 *       {@code -jar} 形态 = 原 exe(保留 javaw/java 区别)+ {@code -jar} + fat jar 路径(classpath 单条目即 jar 路径,
 *       JDK 25 实测 {@code sun.java.command} 只含 jar 路径不带 {@code -jar} 前缀,两变体均归一处理)+ 程序参数;
 *       classpath 形态(dev:spring-boot:run/IDE)= exe + {@code -cp java.class.path} + 主类/参数;
 *       原 JVM {@code -D}/{@code -X} 选项不保留。</li>
 * </ul>
 *
 * <p>顺序保证:先 {@code context.close()} 返回(Tomcat 已停、6102 端口已释放)再 spawn
 * 新进程,无端口竞态;子进程继承 cwd(user.dir)、环境变量(EVERYAGENT_HOME 等)与
 * stdio({@code inheritIO},desktop/bat 启动时即继续写 worker.out.log)。
 * 进程级冷启动:运行中任务被中断,重启后 boot 扫描把非终态任务标 failed(架构 §7.7),任务数据不丢。
 */
@Component
public class WorkerRestartRpcHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkerRestartRpcHandler.class);

    /** 应答发出后留给 Shipper 把 rpc.ok 帧刷到 hub 的等待时间(非阻塞出站队列,宽裕即可)。 */
    private static final long REPLY_FLUSH_MILLIS = 1000;

    private final RpcDispatcher dispatcher;
    private final ConfigurableApplicationContext context;
    private final WorkerProperties props;

    public WorkerRestartRpcHandler(RpcDispatcher dispatcher,
                                   ConfigurableApplicationContext context,
                                   WorkerProperties props) {
        this.dispatcher = dispatcher;
        this.context = context;
        this.props = props;
    }

    @PostConstruct
    void init() {
        dispatcher.register(RpcMethods.WORKER_RESTART, this::rpcWorkerRestart);
    }

    private void rpcWorkerRestart(RpcContext ctx) {
        // 先重建启动命令再应答:重建失败(找不到 exe/主命令)时拒绝重启并回 err,worker 保持运行。
        List<String> command;
        try {
            command = buildRelaunchCommand(
                    ProcessHandle.current().info().command().orElse(null),
                    ProcessHandle.current().info().arguments().orElse(null),
                    System.getProperty("sun.java.command", ""),
                    System.getProperty("java.class.path", ""),
                    System.getProperty("java.home", ""));
        } catch (IllegalStateException e) {
            log.warn("worker.restart 被拒绝:{}", e.getMessage());
            ctx.err("RESTART_UNSUPPORTED", e.getMessage());
            return;
        }
        ctx.ok(Json.obj().put("restarting", true));
        // 独立非守护线程执行关闭+拉起:RpcDispatcher 的虚拟线程池在 context 关闭时被 shutdownNow,
        // 不能在 RPC 线程里做(会被自己杀掉自己);非守护也保证 JVM 不会中途先退。
        Thread restarter = new Thread(() -> doRestart(command), "worker-restart");
        restarter.setDaemon(false);
        restarter.start();
    }

    private void doRestart(List<String> command) {
        try {
            Thread.sleep(REPLY_FLUSH_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        log.info("worker.restart({}) 开始:优雅关闭 ApplicationContext,随后以新进程拉起: {}",
                props.getWorkerId(), String.join(" ", command));
        try {
            // 优雅关闭:与 /admin/shutdown 同路径(断开 hub 连接、销毁插件、停止内嵌容器释放 6102)。
            context.close();
            Process relaunch = new ProcessBuilder(command)
                    .directory(new File(System.getProperty("user.dir")))
                    .inheritIO()
                    .start();
            log.info("worker.restart:新进程已拉起 pid={}(旧进程随即退出)", relaunch.pid());
        } catch (Throwable t) {
            // context 已关闭,本进程已无服务能力;记录后退出,避免留下僵尸 JVM。
            log.error("worker.restart:重新拉起 worker 进程失败,worker 将退出且不自动恢复(请手动启动): {}",
                    t.getMessage(), t);
        } finally {
            // 退出本进程(context 已关闭,服务已停;shutdown hook 中 Spring 的 context.close 幂等,logback 顺带刷盘)。
            System.exit(0);
        }
    }

    /**
     * 重建本进程的启动命令(纯函数,便于测试)。
     *
     * @param exe           当前 JVM 可执行文件路径(可为 null → 按 javaHome 回退)
     * @param arguments     完整 argv 中的参数段(可为 null/空 → Windows 形态走属性重建)
     * @param sunJavaCommand {@code sun.java.command}:主命令(类名或 -jar 路径)+ 程序参数,不含 JVM 选项
     * @param javaClassPath {@code java.class.path}:fat jar 启动时即 jar 路径(单条目)
     * @param javaHome      {@code java.home}(exe 缺失时的回退锚点)
     * @throws IllegalStateException 无法重建(拒绝重启)
     */
    static List<String> buildRelaunchCommand(String exe, String[] arguments, String sunJavaCommand,
                                             String javaClassPath, String javaHome) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJavaExe(exe, javaHome));
        if (arguments != null && arguments.length > 0) {
            // Linux 等平台:ProcessHandle 提供完整 argv(含 JVM 选项),保真重建。
            cmd.addAll(Arrays.asList(arguments));
            return cmd;
        }
        String main = sunJavaCommand == null ? "" : sunJavaCommand.trim();
        if (main.isEmpty()) {
            throw new IllegalStateException(
                    "无法重建启动命令:ProcessHandle arguments() 不可用且 sun.java.command 缺失,请手动重启 worker 进程");
        }
        // fat jar 形态:java.class.path 单条目即 jar 路径,以它为精确边界切出「-jar <jar> [程序参数]」,
        // 避免按空白切分时 jar 路径含空格被拆坏。sun.java.command 两种变体都归一处理:
        // JDK 25 实测 -jar 启动只含 jar 路径(无 "-jar" 前缀);部分 JDK 带 "-jar" 前缀。
        String jar = javaClassPath == null ? "" : javaClassPath.trim();
        boolean prefixed = main.startsWith("-jar");
        String after = prefixed ? main.substring("-jar".length()).trim() : main;
        if (!jar.isEmpty() && jar.indexOf(File.pathSeparatorChar) < 0
                && (after.equals(jar) || after.startsWith(jar + " "))) {
            cmd.add("-jar");
            cmd.add(jar);
            String extra = after.substring(jar.length()).trim();
            if (!extra.isEmpty()) {
                cmd.addAll(Arrays.asList(extra.split("\\s+")));
            }
            return cmd;
        }
        if (prefixed) {
            // 带 "-jar" 前缀但与 classpath 单条目不匹配(异常形态):退回整串按空白切(jar 路径含空格时可能失败)。
            cmd.addAll(Arrays.asList(main.split("\\s+")));
            return cmd;
        }
        // classpath 形态(dev:spring-boot:run / IDE 启动):显式 -cp 完整类路径 + 主类/参数。
        String cp = javaClassPath == null || javaClassPath.isBlank() ? "." : javaClassPath;
        cmd.add("-cp");
        cmd.add(cp);
        cmd.addAll(Arrays.asList(main.split("\\s+")));
        return cmd;
    }

    /** 解析 java 可执行文件:优先 ProcessHandle 上报的原始 exe(本进程正在运行,天然可信,保留 javaw/java 区别),回退 java.home。 */
    private static String resolveJavaExe(String exe, String javaHome) {
        if (exe != null && !exe.isBlank()) {
            return exe;
        }
        if (javaHome == null || javaHome.isBlank()) {
            throw new IllegalStateException("无法重建启动命令:当前 JVM 可执行文件路径不可得,请手动重启 worker 进程");
        }
        String bin = isWindows() ? "javaw.exe" : "java";
        Optional<Path> found = Arrays.stream(new String[]{bin, isWindows() ? "java.exe" : "java"})
                .map(name -> Paths.get(javaHome, "bin", name))
                .filter(Files::isExecutable)
                .findFirst();
        return found.orElseThrow(() -> new IllegalStateException(
                "无法重建启动命令:" + javaHome + " 下未找到 java 可执行文件,请手动重启 worker 进程"))
                .toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
