package dev.everyagent.worker.modules;

import dev.everyagent.contract.json.Json;
import dev.everyagent.worker.config.WorkerProperties;
import dev.everyagent.worker.hub.HubPool;
import dev.everyagent.worker.os.pty.TerminalPty;
import dev.everyagent.worker.os.pty.TerminalPtyFactory;
import dev.everyagent.worker.proto.Channels;
import dev.everyagent.worker.proto.RpcMethods;
import dev.everyagent.worker.rpc.BadParamsException;
import dev.everyagent.worker.rpc.RpcDispatcher;
import dev.everyagent.worker.rpc.RpcContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内嵌终端会话管理(架构 §7.18):term.open/input/resize/close 四个 RPC 方法,
 * 全部经 Sandbox jailed 到工作区根(与 fs.* 同款沙箱)。PTY 输出由独立虚拟线程
 * 读循环持续推送到 termStream 频道(term.output / term.exited),前端 sub/unsub。
 *
 * <p>会话表 termId → TerminalSession 在内存中(worker 不可变地障,进程退出即销毁);
 * PTY 引擎基于 pty4j(跨平台 ConPTY/WinPTY/openpty),复用框架禁止重复造轮子。
 */
@Component
public class TerminalService {

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

        TerminalPty pty = TerminalPtyFactory.open(cwd, cols, rows, shell, null);

        String ownerKey = ctx.ownerKey();
        TerminalSession session = new TerminalSession(pty, termId, ownerKey, props.getWorkerId());
        sessions.put(termId, session);

        // 起虚拟线程读 PTY 输出,持续推送到 termStream 频道(Java 25 虚拟线程)
        Thread.startVirtualThread(() -> readLoop(termId));

        log.info("终端会话已打开: termId={}, cwd={}, cols={}, rows={}, shell={}",
                termId, cwd, cols, rows, shell);

        ctx.ok(Json.obj().put("termId", termId).put("pid", 0));
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
     * term.close:取会话 → pty.close() → sessions.remove → ok({})。
     */
    private void close(RpcContext ctx) throws IOException {
        String termId = ctx.strParam("termId");
        TerminalSession session = sessions.remove(termId);
        if (session == null) {
            throw new BadParamsException("终端会话不存在: " + termId);
        }
        try {
            session.pty.close();
        } catch (IOException e) {
            log.debug("关闭 PTY 异常(已忽略): termId={}", termId, e);
        }
        log.info("终端会话已关闭: termId={}", termId);
        ctx.ok(Json.obj());
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
     */
    static final class TerminalSession {
        final TerminalPty pty;
        final String termId;
        final String ownerKey;
        final String workerId;

        TerminalSession(TerminalPty pty, String termId, String ownerKey, String workerId) {
            this.pty = pty;
            this.termId = termId;
            this.ownerKey = ownerKey;
            this.workerId = workerId;
        }
    }
}
