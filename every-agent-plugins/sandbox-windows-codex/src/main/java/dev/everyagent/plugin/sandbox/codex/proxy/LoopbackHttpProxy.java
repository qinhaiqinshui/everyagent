package dev.everyagent.plugin.sandbox.codex.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 宿主侧 loopback HTTP(S) 代理（对齐 codex network-proxy 的默认形态：进程内嵌、仅回环、
 * CONNECT 隧道透传、不做 TLS 终结、不生成任何证书、SOCKS5 裁剪不做——ALL_PROXY 回退
 * http url，与 codex 关闭 socks 时一致）。
 *
 * <p><b>端口策略（对齐 reserve_windows_managed_listener）</b>：先试配置首选端口 →
 * 管理端口段 {@link #MANAGED_RANGE_START}..{@link #MANAGED_RANGE_END}（3128..3159，
 * codex WINDOWS_MANAGED_HTTP_PROXY_PORTS 同段）顺序探测；<b>绝不绑定随机端口</b>——
 * 配置端口 0/非法值直接按段探测，段内全忙则启动失败（调用方降级直连并 WARN）。
 * 固定段的意义：监听端口确定性落在预声明集合内（可观测、可排障、env 注入地址稳定），
 * 不存在「随机端口漂移后防火墙放行面失效」的问题（见 ProxyRegistry 类 Javadoc 的
 * 防火墙协同决策）。
 *
 * <p><b>行为</b>：
 * <ul>
 *   <li>{@code CONNECT host:port}：目标过 {@link HostPolicy} → 放行即
 *       {@code 200 Connection Established} 后裸字节双向转发（TLS 由端到端客户端自理，
 *       代理对载荷不可见）；阻断即 {@code 403} + {@code x-proxy-error} 头；</li>
 *   <li>明文 HTTP 绝对 URI（{@code GET http://host/path HTTP/1.1}）：改写为 origin-form
 *       直连目标转发，补 {@code Connection: close}（代理不解析响应分帧，双向泵到 EOF，
 *       对客户端表现为合法的连接关闭语义）；</li>
 *   <li>阻断一律 403 + {@code x-proxy-error: <原因>}（对齐 codex，可观测而非静默失败，
 *       防「被拦」被误判为断网）；解析失败 400、上游连不通 502，同带 x-proxy-error；</li>
 *   <li>仅 bind 127.0.0.1；每连接/每泵向各一个虚拟线程（Java 25），无第三方依赖。</li>
 * </ul>
 *
 * <p>生命周期：宿主（worker）进程内嵌，{@link #close()} 关监听与全部在途连接；
 * worker 崩溃代理即消失——沙箱内表现为代理连接失败，与 codex 同边界（文档化）。
 */
public final class LoopbackHttpProxy implements AutoCloseable {

    /** 配置首选端口默认值（codex 默认 http 代理端口）。 */
    public static final int DEFAULT_PREFERRED_PORT = 3128;
    /** 管理端口段起点（含）。 */
    public static final int MANAGED_RANGE_START = 3128;
    /** 管理端口段终点（含）。 */
    public static final int MANAGED_RANGE_END = 3159;

    /** 头块读取上限（CONNECT/绝对 URI 请求头，超限按 400 断开）。 */
    static final int MAX_HEADER_BYTES = 64 * 1024;
    /** 头块读取超时（客户端连上不说话的连接不长期占线程）。 */
    static final int HEADER_READ_TIMEOUT_MS = 30_000;
    /** 上游 TCP 连接超时。 */
    static final int UPSTREAM_CONNECT_TIMEOUT_MS = 10_000;
    /** 逐向泵缓冲。 */
    private static final int PUMP_BUFFER = 16 * 1024;

    private static final System.Logger LOG =
            System.getLogger(LoopbackHttpProxy.class.getName());

    private final ServerSocket serverSocket;
    private final HostPolicy policy;
    private final Thread acceptor;
    private final Set<Socket> liveClientSockets = ConcurrentHashMap.newKeySet();

    // 观测计数（start/阻断打日志，计数供测试与排障）
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong tunneled = new AtomicLong();
    private final AtomicLong plainForwarded = new AtomicLong();
    private final AtomicLong blocked = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile boolean closed;

    private LoopbackHttpProxy(ServerSocket serverSocket, HostPolicy policy) {
        this.serverSocket = serverSocket;
        this.policy = policy;
        this.acceptor = Thread.ofVirtual().name("codex-proxy-accept-", 0).start(this::acceptLoop);
    }

    /**
     * 启动：按「首选端口 → 管理段顺序」探测绑定 127.0.0.1；全部占用抛 IOException
     * （消息含尝试端口数），绝不回落随机端口。
     */
    public static LoopbackHttpProxy start(int preferredPort, HostPolicy policy)
            throws IOException {
        List<Integer> attempts = new ArrayList<>();
        IOException last = null;
        for (int port : probeOrder(preferredPort)) {
            attempts.add(port);
            ServerSocket socket = new ServerSocket();
            try {
                socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 128);
                LoopbackHttpProxy proxy = new LoopbackHttpProxy(socket, policy);
                LOG.log(System.Logger.Level.INFO,
                        "[codex-proxy] 已启动 127.0.0.1:{0}(首选 {1},第 {2} 次探测)",
                        new Object[] { port, preferredPort, attempts.size() });
                return proxy;
            } catch (BindException busy) {
                last = busy;
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // 关失败只泄漏一个 fd，绑定失败路径继续探测
                }
            }
        }
        throw new IOException("loopback 代理端口段全部被占用(尝试 " + attempts.size()
                + " 个端口:" + firstLast(attempts) + "),拒绝随机端口兜底", last);
    }

    private static String firstLast(List<Integer> attempts) {
        return attempts.size() <= 2 ? attempts.toString()
                : attempts.get(0) + ".." + attempts.get(attempts.size() - 1);
    }

    /**
     * 探测顺序（纯函数，单测覆盖）：非法/0 首选端口被拒收（跳过），管理段去重保序。
     */
    static List<Integer> probeOrder(int preferredPort) {
        Set<Integer> order = new LinkedHashSet<>();
        if (preferredPort >= 1 && preferredPort <= 0xFFFF) {
            order.add(preferredPort);
        }
        for (int port = MANAGED_RANGE_START; port <= MANAGED_RANGE_END; port++) {
            order.add(port);
        }
        return new ArrayList<>(order);
    }

    /** 实际监听端口。 */
    public int port() {
        return serverSocket.getLocalPort();
    }

    /** 监听地址（断言仅回环用）。 */
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) serverSocket.getLocalSocketAddress();
    }

    /** 是否仍在运行。 */
    public boolean isRunning() {
        return !closed && !serverSocket.isClosed();
    }

    /** 观测计数快照。 */
    public record Stats(long accepted, long tunneled, long plainForwarded, long blocked,
            long failed) {
    }

    public Stats stats() {
        return new Stats(accepted.get(), tunneled.get(), plainForwarded.get(),
                blocked.get(), failed.get());
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // 关监听失败无碍：closed 标志已置，在途连接逐一关闭
        }
        for (Socket socket : liveClientSockets) {
            closeQuietly(socket);
        }
        LOG.log(System.Logger.Level.INFO, "[codex-proxy] 已关闭 stats={0}", stats());
    }

    // ---- 接受循环与单连接处理 ----

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket client = serverSocket.accept();
                accepted.incrementAndGet();
                liveClientSockets.add(client);
                Thread.ofVirtual().name("codex-proxy-conn-", 0)
                        .start(() -> handleQuietly(client));
            } catch (IOException acceptClosed) {
                if (!closed) {
                    failed.incrementAndGet();
                    LOG.log(System.Logger.Level.WARNING,
                            "[codex-proxy] accept 异常,代理退出: {0}", acceptClosed.toString());
                }
                return;
            }
        }
    }

    private void handleQuietly(Socket client) {
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(HEADER_READ_TIMEOUT_MS);
            handle(client);
        } catch (Exception e) {
            failed.incrementAndGet();
        } finally {
            closeQuietly(client);
            liveClientSockets.remove(client);
        }
    }

    private void handle(Socket client) throws IOException {
        String headerBlock = readHeaderBlock(client.getInputStream());
        if (headerBlock == null) {
            return; // 客户端在发头前断开/超限（readHeaderBlock 已按 400 应答或对端已关）
        }
        int lineEnd = headerBlock.indexOf("\r\n");
        String requestLine = lineEnd >= 0 ? headerBlock.substring(0, lineEnd) : headerBlock;
        String[] parts = requestLine.split(" ");
        if (parts.length < 3 || !parts[2].regionMatches(true, 0, "HTTP/", 0, 5)) {
            writeErrorResponse(client, 400, "malformed-request-line");
            return;
        }
        String method = parts[0];
        if ("CONNECT".equalsIgnoreCase(method)) {
            handleConnect(client, parts[1]);
        } else {
            handlePlainHttp(client, headerBlock, method, parts[1]);
        }
    }

    // ---- CONNECT 隧道 ----

    private void handleConnect(Socket client, String authority) throws IOException {
        HostAndPort target = parseAuthority(authority, 443);
        if (target == null) {
            writeErrorResponse(client, 400, "malformed-connect-authority");
            return;
        }
        HostPolicy.Decision decision = policy.resolve(target.host(), target.port());
        if (!decision.allowed()) {
            blocked.incrementAndGet();
            LOG.log(System.Logger.Level.INFO,
                    "[codex-proxy] CONNECT 阻断 {0}: {1}",
                    new Object[] { authority, decision.reason() });
            writeErrorResponse(client, 403, decision.reason());
            return;
        }
        Socket upstream = connectUpstream(decision, authority);
        if (upstream == null) {
            writeErrorResponse(client, 502, "upstream-connect-failed:" + authority);
            return;
        }
        tunneled.incrementAndGet();
        // 应答 200 后裸转发；客户端的 TLS/任意字节对代理不可见（不做 MITM）
        client.getOutputStream().write(
                "HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        client.getOutputStream().flush();
        client.setSoTimeout(0);
        relay(client, upstream);
    }

    // ---- 明文 HTTP 绝对 URI 转发 ----

    private void handlePlainHttp(Socket client, String headerBlock, String method,
            String rawUri) throws IOException {
        URI uri;
        try {
            uri = new URI(rawUri);
        } catch (URISyntaxException malformed) {
            writeErrorResponse(client, 400, "malformed-request-uri");
            return;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!uri.isAbsolute() || uri.getHost() == null) {
            // 代理上收到 origin-form 请求 = 客户端误把代理当源站，明确可观测地拒绝
            writeErrorResponse(client, 400, "origin-form-not-supported-use-absolute-uri");
            return;
        }
        if (!"http".equals(scheme)) {
            writeErrorResponse(client, 400, "scheme-not-supported-via-plain-proxy:" + scheme);
            return;
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        HostPolicy.Decision decision = policy.resolve(uri.getHost(), port);
        if (!decision.allowed()) {
            blocked.incrementAndGet();
            LOG.log(System.Logger.Level.INFO,
                    "[codex-proxy] HTTP 转发阻断 {0}: {1}",
                    new Object[] { uri.getHost(), decision.reason() });
            writeErrorResponse(client, 403, decision.reason());
            return;
        }
        Socket upstream = connectUpstream(decision, uri.getHost() + ":" + port);
        if (upstream == null) {
            writeErrorResponse(client, 502, "upstream-connect-failed:" + uri.getHost());
            return;
        }
        plainForwarded.incrementAndGet();
        client.setSoTimeout(0);
        String forward = rewritePlainRequest(headerBlock, method, uri);
        upstream.getOutputStream().write(forward.getBytes(StandardCharsets.ISO_8859_1));
        upstream.getOutputStream().flush();
        relay(client, upstream);
    }

    /**
     * 绝对 URI → origin-form 改写：请求行换成 path[?query]，剥 Proxy-Connection、
     * Proxy-Authorization、Connection、Keep-Alive 逐跳头，补 {@code Connection: close}
     * （代理不做响应分帧，双向泵到 EOF 即对客户端呈合法的连接关闭），Host 缺失时补齐。
     */
    static String rewritePlainRequest(String headerBlock, String method, URI uri) {
        int lineEnd = headerBlock.indexOf("\r\n");
        String rest = lineEnd >= 0 ? headerBlock.substring(lineEnd + 2) : "";
        StringBuilder path = new StringBuilder(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                ? "/" : uri.getRawPath());
        if (uri.getRawQuery() != null) {
            path.append('?').append(uri.getRawQuery());
        }
        StringBuilder out = new StringBuilder();
        out.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        boolean hostSeen = false;
        for (String line : rest.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String name = line.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            if (name.equals("proxy-connection") || name.equals("proxy-authorization")
                    || name.equals("connection") || name.equals("keep-alive")) {
                continue; // 逐跳头不转发
            }
            if (name.equals("host")) {
                hostSeen = true;
            }
            out.append(line).append("\r\n");
        }
        if (!hostSeen) {
            String authority = uri.getPort() > 0
                    ? uri.getHost() + ":" + uri.getPort() : uri.getHost();
            out.append("Host: ").append(authority).append("\r\n");
        }
        out.append("Connection: close\r\n\r\n");
        return out.toString();
    }

    // ---- 共用底层 ----

    private Socket connectUpstream(HostPolicy.Decision decision, String targetDesc) {
        IOException last = null;
        for (InetSocketAddress target : decision.targets()) {
            Socket socket = new Socket();
            try {
                socket.connect(target, UPSTREAM_CONNECT_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                return socket;
            } catch (IOException e) {
                last = e;
                closeQuietly(socket);
            }
        }
        failed.incrementAndGet();
        LOG.log(System.Logger.Level.INFO,
                "[codex-proxy] 上游连接失败 {0} 尝试{1}个地址: {2}",
                new Object[] { targetDesc, decision.targets().size(), String.valueOf(last) });
        return null;
    }

    /** 双向裸泵：任一向 EOF/异常即双向关闭（长连接语义交还给端到端协议）。 */
    private void relay(Socket a, Socket b) {
        Thread toB = Thread.ofVirtual().unstarted(() -> pump(a, b));
        Thread toA = Thread.ofVirtual().unstarted(() -> pump(b, a));
        toB.start();
        toA.start();
        // 任一向结束都立即关两端，另一向的泵随 IOException 收尾
        try {
            toB.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeQuietly(a);
        closeQuietly(b);
    }

    private static void pump(Socket from, Socket to) {
        byte[] buffer = new byte[PUMP_BUFFER];
        try (InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream()) {
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                out.flush();
            }
        } catch (IOException closed) {
            // 对端关闭/连接中断：relay 的关闭逻辑统一收口，这里静默退出
        } finally {
            closeQuietly(to);
        }
    }

    /** 读到首个空行（\r\n\r\n）为止的头块；超限应答 400 后返回 null。 */
    private static String readHeaderBlock(InputStream in) throws IOException {
        byte[] buffer = new byte[2048];
        StringBuilder sb = new StringBuilder();
        int total = 0;
        while (true) {
            int n;
            try {
                n = in.read(buffer);
            } catch (java.net.SocketTimeoutException timeout) {
                return null; // 客户端不说话：直接断开
            }
            if (n == -1) {
                return null;
            }
            total += n;
            if (total > MAX_HEADER_BYTES) {
                return null; // 头块超限：调用方 closeQuietly 收尾（对端多为失控客户端）
            }
            sb.append(new String(buffer, 0, n, StandardCharsets.ISO_8859_1));
            if (sb.indexOf("\r\n\r\n") >= 0) {
                return sb.substring(0, sb.indexOf("\r\n\r\n"));
            }
        }
    }

    /** 阻断/错误应答：403/400/502 + x-proxy-error（原因单行化防头注入）。 */
    private static void writeErrorResponse(Socket client, int status, String reason)
            throws IOException {
        String phrase = status == 403 ? "Forbidden"
                : status == 502 ? "Bad Gateway" : "Bad Request";
        String safeReason = reason == null ? "unknown"
                : reason.replaceAll("[\\r\\n]", " ");
        client.getOutputStream().write((
                "HTTP/1.1 " + status + " " + phrase + "\r\n"
                        + "x-proxy-error: " + safeReason + "\r\n"
                        + "Content-Length: 0\r\n"
                        + "Connection: close\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        client.getOutputStream().flush();
    }

    /** CONNECT authority 解析：host / host:port / [v6]:port（纯函数，单测覆盖）。 */
    static HostAndPort parseAuthority(String authority, int defaultPort) {
        if (authority == null || authority.isBlank()) {
            return null;
        }
        String s = authority.strip();
        String host;
        int port = defaultPort;
        if (s.startsWith("[")) {
            int close = s.indexOf(']');
            if (close < 0) {
                return null;
            }
            host = s.substring(1, close);
            if (close + 1 < s.length()) {
                String rest = s.substring(close + 1);
                if (!rest.startsWith(":")) {
                    return null;
                }
                Integer parsed = parsePort(rest.substring(1));
                if (parsed == null) {
                    return null;
                }
                port = parsed;
            }
        } else {
            int colon = s.lastIndexOf(':');
            if (colon >= 0) {
                Integer parsed = parsePort(s.substring(colon + 1));
                if (parsed == null) {
                    return null;
                }
                host = s.substring(0, colon);
                port = parsed;
            } else {
                host = s;
            }
        }
        if (host.isBlank()) {
            return null;
        }
        return new HostAndPort(host, port);
    }

    private static Integer parsePort(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            int port = Integer.parseInt(raw);
            return port >= 1 && port <= 0xFFFF ? port : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** host/port 对（CONNECT authority 解析结果）。 */
    record HostAndPort(String host, int port) {
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // 尽力关闭
        }
    }
}
