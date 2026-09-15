package dev.everyagent.worker.modules;

import dev.everyagent.contract.frame.Frames;
import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubLink;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.os.pty.TerminalPty;
import dev.everyagent.worker.os.pty.TerminalPtyFactory;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.rpc.RpcContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内嵌终端会话管理(架构 §7.18):term.open/input/resize/close 四个 RPC 方法,
 * 全部经 Sandbox jailed 到工作区根(与 fs.* 同款沙箱)。PTY 输出由独立虚拟线程
 * 读循环持续推送到 termStream 频道(term.output / term.exited),前端 sub/unsub。
 *
 * <p>会话表 termId → TerminalSession 在内存中(worker 不可变地障,进程退出即销毁);
 * PTY 引擎基于 pty4j(跨平台 ConPTY/WinPTY/openpty),复用框架禁止重复造轮子。
 *
 * <p><b>孤儿进程防护(三层兜底)</b>:
 * <ol>
 *   <li>前端正常关闭标签页 → term.close RPC → 会话回收;</li>
 *   <li>前端刷新/浏览器崩溃 → hub 检测 WS 断开 → subscriber.leave 通知 →
 *       {@link #onHubMessage} 解析 term.*.stream 频道的 leave → 回收该 termId 的会话;
 *       若 leave 通知丢失(hub 缓陷),{@link #onHubDisconnected} 兜底清理该连接上
 *       最后活跃的所有终端会话;</li>
 *   <li>worker 进程关闭 → {@link #destroy} 清理全部会话。</li>
 * </ol>
 * 前端无法可靠发送 term.close(页面刷新即 WS 拆除),worker 侧兜底是唯一可靠路径,
 * 与 DataPusherManager 的 onHubDisconnected 清理推送器是完全同款的架构范式。
 */
@Component
public class TerminalService implements HubPool.Listener {

    private static final Logger log = LoggerFactory.getLogger(TerminalService.class);

    /** 会话表:termId → TerminalSession(内存态,worker 进程退出即销毁)。 */
    private final ConcurrentHashMap<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    private final WorkspaceManager workspaces;
    private final HubPool pool;
    private final WorkerProperties props;

    public TerminalService(RpcDispatcher dispatcher, WorkspaceManager workspaces, HubPool pool,
            WorkerProperties props) {
        this.workspaces = workspaces;
        this.pool = pool;
        this.props = props;

        dispatcher.register(RpcMethods.TERM_OPEN, this::open);
        dispatcher.register(RpcMethods.TERM_INPUT, this::input);
        dispatcher.register(RpcMethods.TERM_RESIZE, this::resize);
        dispatcher.register(RpcMethods.TERM_CLOSE, this::close);
    }

    @PostConstruct
    void init() {
        pool.addListener(this);
    }

    // ---- RPC 方法实现 ----

    /**
     * term.open:沙箱校验 workspace+path(必为目录)→ TerminalPtyFactory.open →
     * 存入会话表 → 起虚拟线程读 PTY 输出推送到 termStream 频道 → 返回 {termId, pid}。
     */
    private void open(RpcContext ctx) throws IOException {
        Sandbox sb = new Sandbox(workspaces.resolve(ctx.strParam("workspace")));
        Path cwd = sb.resolveExisting(ctx.strParam("path"));
        if (!Files.isDirectory(cwd)) {
            throw new BadParamsException("path 必须为目录: " + ctx.strParam("path"));
        }

        String termId = ctx.strParam("termId");
        int cols = (int) ctx.optLongParam("cols", 80);
        int rows = (int) ctx.optLongParam("rows", 24);
        String shell = ctx.optStrParam("shell", null);

        if (cols < 1 || rows < 1) {
            throw new BadParamsException("cols/rows 必须 >= 1 (got cols=" + cols + ", rows=" + rows + ")");
        }

        TerminalPty pty;
        try {
            pty = TerminalPtyFactory.open(cwd, cols, rows, shell, null);
        } catch (IOException e) {
            // PTY 创建失败:把根因透传给前端(而非笼统的 "Couldn't create PTY"),
            // 便于排查(如 ConPTY/winpty 原生库加载失败、shell 路径不存在等)。
            String cause = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            Throwable root = e.getCause();
            if (root != null && root.getMessage() != null) {
                cause += " → " + root.getMessage();
            }
            throw new IOException("创建终端失败: " + cause, e);
        }

        String ownerKey = ctx.ownerKey();
        long pid = pty.pid();
        TerminalSession session = new TerminalSession(pty, termId, ownerKey, props.getWorkerId(),
                ctx.conn(), pid);
        sessions.put(termId, session);

        // 起虚拟线程读 PTY 输出,持续推送到 termStream 频道(Java 25 虚拟线程)
        Thread.startVirtualThread(() -> readLoop(termId));

        log.info("终端会话已打开: termId={}, pid={}, cwd={}, cols={}, rows={}, shell={}",
                termId, pid, cwd, cols, rows, shell);

        ctx.ok(Json.obj().put("termId", termId).put("pid", pid));
    }

    /**
     * term.input:取会话 → pty.write(Base64.decode(data)) → ok({})。
     */
    private void input(RpcContext ctx) throws IOException {
        String termId = ctx.strParam("termId");
        TerminalSession session = sessions.get(termId);
        if (session == null) {
            throw new BadParamsException("终端会话不存在: " + termId);
        }
        String data = ctx.strParam("data");
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException e) {
            throw new BadParamsException("data 不是合法 base64");
        }
        session.pty.write(bytes);
        ctx.ok(Json.obj());
    }

    /**
     * term.resize:取会话 → pty.resize(cols, rows) → ok({})。
     */
    private void resize(RpcContext ctx) throws IOException {
        String termId = ctx.strParam("termId");
        TerminalSession session = sessions.get(termId);
        if (session == null) {
            throw new BadParamsException("终端会话不存在: " + termId);
        }
        int cols = (int) ctx.optLongParam("cols", 80);
        int rows = (int) ctx.optLongParam("rows", 24);
        if (cols < 1 || rows < 1) {
            throw new BadParamsException("cols/rows 必须 >= 1 (got cols=" + cols + ", rows=" + rows + ")");
        }
        session.pty.resize(cols, rows);
        ctx.ok(Json.obj());
    }

    /**
     * term.close:取会话 → closeSession → ok({})。
     */
    private void close(RpcContext ctx) throws IOException {
        String termId = ctx.strParam("termId");
        TerminalSession session = sessions.get(termId);
        if (session == null) {
            throw new BadParamsException("终端会话不存在: " + termId);
        }
        closeSession(termId);
        log.info("终端会话已关闭: termId={}", termId);
        ctx.ok(Json.obj());
    }

    // ---- 内部工具 ----

    /**
     * 回收终端会话(幂等):从会话表移除 → close PTY → kill 子进程。
     * readLoop 在 PTY close 后 read 返回 null/异常自然退出,finally 也会 remove(幂等 no-op)。
     */
    private void closeSession(String termId) {
        TerminalSession session = sessions.remove(termId);
        if (session == null) {
            return;
        }
        try {
            session.pty.close();
        } catch (IOException e) {
            log.debug("关闭 PTY 异常(已忽略): termId={}", termId, e);
        }
    }

    /** 从终端 stream 频道名解析 termId;非终端频道返回 null。 */
    private static String termIdOf(String channel) {
        if (channel == null || !channel.startsWith("u.")) {
            return null;
        }
        int ownerEnd = channel.indexOf('.', 2);
        if (ownerEnd < 0) {
            return null;
        }
        String rest = channel.substring(ownerEnd + 1);
        if (!rest.startsWith("term.") || !rest.endsWith(".stream")) {
            return null;
        }
        String termId = rest.substring("term.".length(), rest.length() - ".stream".length());
        return termId.isEmpty() ? null : termId;
    }

    // ---- HubPool.Listener:孤儿进程防护(前端刷新/WS 断开时 worker 侧兜底清理) ----

    /**
     * hub 消息:监听 subscriber.leave 通知——前端断开 WS 时 hub 发 leave 到
     * u.&lt;K&gt;.term.&lt;termId&gt;.stream 频道,据此回收对应终端会话。
     * subscriber.join 忽略(终端会话由前端主动 term.open 建立,不依赖 join)。
     */
    @Override
    public void onHubMessage(HubLink conn, JsonNode frame) {
        String event = frame.path("event").asString("");
        if (!Frames.SUBSCRIBER_LEAVE.equals(event)) {
            return;
        }
        String channel = frame.path("channel").asString("");
        String termId = termIdOf(channel);
        if (termId == null) {
            return; // 非终端频道
        }
        TerminalSession session = sessions.get(termId);
        if (session == null) {
            return;
        }
        // 只清理属于该连接(ownerKey 匹配)的会话,避免多 hub 跨连接误清
        if (!conn.k().equals(session.ownerKey)) {
            return;
        }
        log.info("subscriber.leave 回收终端会话: termId={}, channel={}", termId, channel);
        closeSession(termId);
    }

    /**
     * hub 连接断开:该连接上的所有前端已掉线,清理关联的终端会话防泄漏。
     * 与 DataPusherManager.onHubDisconnected 清理推送器同款兜底范式。
     */
    @Override
    public void onHubDisconnected(HubLink conn) {
        String ownerKey = conn.k();
        for (String termId : new ArrayList<>(sessions.keySet())) {
            TerminalSession session = sessions.get(termId);
            if (session != null && ownerKey.equals(session.ownerKey)) {
                log.info("hub 断开回收终端会话: termId={}, ownerKey={}", termId, ownerKey);
                closeSession(termId);
            }
        }
    }

    /**
     * worker 关闭:清理全部终端会话,kill 所有 PTY 子进程,防孤儿进程。
     */
    @PreDestroy
    void destroy() {
        for (String termId : new ArrayList<>(sessions.keySet())) {
            log.info("worker 关闭回收终端会话: termId={}", termId);
            closeSession(termId);
        }
    }

    // ---- PTY 读循环(虚拟线程) ----

    /**
     * 持续读 PTY 输出并推送到 termStream 频道:
     * <ul>
     *   <li>读到数据 → pubForOwner(ownerKey, termStream, "term.output", null, {termId, data(base64)}, null)</li>
     *   <li>EOF(null)或异常 → pubForOwner(ownerKey, termStream, "term.exited", null, {termId}, null) → 回收会话</li>
     * </ul>
     * 读循环独占 PTY 的 read 端(与 write/resize/close 不并发调用同一实例,
     * 但 close 幂等,term.close 的 pty.close 会触发 read 返回 null/异常自然退出)。
     */
    private void readLoop(String termId) {
        TerminalSession session = sessions.get(termId);
        if (session == null) {
            return;
        }
        String ownerKey = session.ownerKey;
        String channel = Channels.termStream(ownerKey, termId);
        Base64.Encoder enc = Base64.getEncoder();

        try {
            while (true) {
                byte[] chunk = session.pty.read();
                if (chunk == null) {
                    // EOF:子进程退出或 PTY 关闭
                    break;
                }
                if (chunk.length == 0) {
                    continue;
                }
                ObjectNode payload = Json.obj()
                        .put("termId", termId)
                        .put("data", enc.encodeToString(chunk));
                pool.pubForOwner(ownerKey, channel, "term.output", null, payload, null);
            }
        } catch (IOException e) {
            log.debug("PTY 读循环 IO 异常: termId={}", termId, e);
        } catch (Exception e) {
            log.warn("PTY 读循环异常: termId={}", termId, e);
        } finally {
            // 推送 term.exited 通知前端清理
            ObjectNode exitPayload = Json.obj().put("termId", termId);
            try {
                pool.pubForOwner(ownerKey, channel, "term.exited", null, exitPayload, null);
            } catch (Exception e) {
                log.warn("推送 term.exited 失败: termId={}", termId, e);
            }
            // 回收会话(若 term.close 已先 remove,这里是 no-op)
            sessions.remove(termId);
            try {
                session.pty.close();
            } catch (IOException e) {
                log.debug("读循环末尾关闭 PTY 异常(已忽略): termId={}", termId, e);
            }
            log.info("终端读循环结束: termId={}", termId);
        }
    }

    // ---- 内部:终端会话上下文 ----

    /**
     * 终端会话上下文:持有 PTY 实例与发布所需的身份信息。
     * readLoop 在独立虚拟线程中运行,通过本对象获取 ownerKey 等发布参数。
     * conn 用于 hub 断开时按连接清理(hub 断开 → onHubDisconnected → 按 ownerKey 匹配回收)。
     */
    static final class TerminalSession {
        final TerminalPty pty;
        final String termId;
        final String ownerKey;
        final String workerId;
        final HubLink conn;
        final long pid;

        TerminalSession(TerminalPty pty, String termId, String ownerKey, String workerId,
                HubLink conn, long pid) {
            this.pty = pty;
            this.termId = termId;
            this.ownerKey = ownerKey;
            this.workerId = workerId;
            this.conn = conn;
            this.pid = pid;
        }
    }
}
