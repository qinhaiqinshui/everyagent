package dev.everyagent.plugin.sandbox.codex.proxy;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link LoopbackHttpProxy} 宿主侧行为：CONNECT 隧道（裸字节 + HttpClient 端到端）、
 * 明文 HTTP 绝对 URI 转发、403+x-proxy-error 阻断、端口 fallback、仅回环监听。
 *
 * <p>全部走本机回环（真实 socket），不依赖外网；沙箱/CI 环境连回环 TCP 都不可用时
 * 经 canary 假设跳过（枚举/绑定本身在此环境无法验证时诚实降级）。
 */
class LoopbackHttpProxyTest {

    /** 回环 TCP canary：bind+connect 都通才跑（受限网络环境诚实跳过）。 */
    static boolean loopbackWorks;

    @BeforeAll
    static void canary() {
        try (ServerSocket probe = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            probe.setSoTimeout(2_000);
            Thread acceptor = Thread.ofVirtual().start(() -> {
                try (Socket s = probe.accept()) {
                    s.getOutputStream().write(1);
                } catch (IOException ignored) {
                    // canary 失败统一按 false 处理
                }
            });
            try (Socket client = new Socket()) {
                client.connect(new InetSocketAddress("127.0.0.1", probe.getLocalPort()), 2_000);
                boolean ok = client.getInputStream().read() == 1;
                acceptor.join(3_000);
                loopbackWorks = ok;
            }
        } catch (Exception unavailable) {
            loopbackWorks = false;
        }
        assumeTrue(loopbackWorks, "本环境回环 TCP bind/connect 不可用(如 offline 沙箱账户),跳过代理行为测试");
    }

    @TempDir
    Path tmp;

    private LoopbackHttpProxy proxy;
    private HttpServer origin;
    private ServerSocket echo;

    @AfterEach
    void tearDown() throws IOException {
        if (proxy != null) {
            proxy.close();
            proxy = null;
        }
        if (origin != null) {
            origin.stop(0);
            origin = null;
        }
        if (echo != null) {
            echo.close();
            echo = null;
        }
    }

    // ---- 端口策略 ----

    @Test
    void probeOrderRejectsPortZeroAndKeepsManagedRange() {
        List<Integer> order = LoopbackHttpProxy.probeOrder(0);
        assertEquals(3128, order.get(0), "非法首选端口被拒收,直接从管理段起探");
        assertEquals(LoopbackHttpProxy.MANAGED_RANGE_START, order.get(0));
        assertEquals(LoopbackHttpProxy.MANAGED_RANGE_END, order.get(order.size() - 1));
        assertEquals(32, order.size(), "管理段 3128..3159 共 32 个");
        List<Integer> dupProbe = LoopbackHttpProxy.probeOrder(3128);
        assertEquals(32, dupProbe.size(), "首选端口在段内时不重复探测");
        List<Integer> outside = LoopbackHttpProxy.probeOrder(8080);
        assertEquals(8080, outside.get(0), "段外首选端口先试,段顺序兜底");
        assertEquals(33, outside.size());
    }

    @Test
    void bindsPreferredPortAndOnlyLoopback() throws IOException {
        int free = freePort();
        proxy = LoopbackHttpProxy.start(free, HostPolicy.allowAll());
        assertEquals(free, proxy.port(), "首选端口空闲时直接绑定");
        assertTrue(proxy.localAddress().getAddress().isLoopbackAddress(),
                "仅监听回环地址(绝不绑 0.0.0.0)");
        assertTrue(proxy.isRunning());
    }

    @Test
    void fallsBackWithinManagedRangeWhenPreferredBusy() throws IOException {
        int busy = freePort();
        try (ServerSocket squatter = new ServerSocket(busy, 1,
                java.net.InetAddress.getLoopbackAddress())) {
            proxy = LoopbackHttpProxy.start(busy, HostPolicy.allowAll());
            assertNotEquals(busy, proxy.port(), "首选被占后必须换端口");
            assertTrue(proxy.port() >= LoopbackHttpProxy.MANAGED_RANGE_START
                    && proxy.port() <= LoopbackHttpProxy.MANAGED_RANGE_END,
                    "fallback 落在管理段内(绝不随机端口): " + proxy.port());
        }
    }

    @Test
    void invalidPreferredPortFallsBackToManagedRange() throws IOException {
        proxy = LoopbackHttpProxy.start(0, HostPolicy.allowAll());
        assertTrue(proxy.port() >= LoopbackHttpProxy.MANAGED_RANGE_START
                && proxy.port() <= LoopbackHttpProxy.MANAGED_RANGE_END,
                "端口 0 配置被拒绝,按管理段起探: " + proxy.port());
    }

    // ---- CONNECT 隧道 ----

    @Test
    void connectTunnelRelaysRawBytes() throws Exception {
        int echoPort = startEcho();
        proxy = LoopbackHttpProxy.start(freePort(),
                HostPolicy.of(true, false, List.of()));
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
            client.setSoTimeout(10_000);
            OutputStream out = client.getOutputStream();
            out.write(("CONNECT 127.0.0.1:" + echoPort + " HTTP/1.1\r\n"
                    + "Host: 127.0.0.1:" + echoPort + "\r\n\r\n")
                    .getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            String response = readUntil(client.getInputStream(), "\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 200"),
                    "隧道建立应答 200: " + response);
            byte[] payload = "tunnel-payload-测试".getBytes(StandardCharsets.UTF_8);
            out.write(payload);
            out.flush();
            byte[] echoed = client.getInputStream().readNBytes(payload.length);
            assertEquals(new String(payload, StandardCharsets.UTF_8),
                    new String(echoed, StandardCharsets.UTF_8), "隧道裸字节双向透传");
        }
    }

    @Test
    void javaHttpClientIssuesConnectThroughProxyToEndTarget() throws Exception {
        // HttpClient + https URL + ProxySelector → 客户端发 CONNECT、经隧道送达 TLS ClientHello。
        // 目标是回环 echo(不回 TLS),客户端必失败——但 echo 侧收到 0x16 开头的握手字节
        // 即证明「CONNECT 建隧 + 字节透传」端到端成立(代理对 TLS 载荷不可见=不做 MITM)。
        AtomicReference<byte[]> firstBytes = new AtomicReference<>();
        int echoPort = startCapturingEcho(firstBytes);
        proxy = LoopbackHttpProxy.start(freePort(), HostPolicy.of(true, false, List.of()));
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(
                        new InetSocketAddress("127.0.0.1", proxy.port())))
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://127.0.0.1:" + echoPort + "/")).build();
        Exception failure = null;
        try {
            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception expected) {
            failure = expected; // echo 不说 TLS,握手必失败——这正是断言前提
        }
        assertNotNull(failure, "目标不回 TLS,客户端握手应失败");
        byte[] captured = firstBytes.get();
        assertNotNull(captured, "echo 应收到经隧道送达的客户端首包");
        assertTrue(captured.length >= 5 && captured[0] == 0x16,
                "首字节 0x16=TLS ClientHello 经 CONNECT 隧道送达目标: "
                        + (captured.length == 0 ? "(空)" + "" : hexOf(captured)));
    }

    // ---- 明文 HTTP 转发 ----

    @Test
    void plainHttpAbsoluteUriForwarded() throws Exception {
        int originPort = startOrigin();
        proxy = LoopbackHttpProxy.start(freePort(), HostPolicy.of(true, false, List.of()));
        HttpClient client = HttpClient.newBuilder()
                .proxy(ProxySelector.of(new InetSocketAddress("127.0.0.1", proxy.port())))
                .build();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + originPort + "/path?q=1")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), "绝对 URI 代理请求转发成功");
        assertEquals("hello-proxy", response.body());
    }

    // ---- 阻断 ----

    @Test
    void connectBlockedLoopbackTargetByDefault() throws Exception {
        int echoPort = startEcho();
        proxy = LoopbackHttpProxy.start(freePort(), HostPolicy.of(false, false, List.of()));
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
            client.setSoTimeout(10_000);
            client.getOutputStream().write(("CONNECT 127.0.0.1:" + echoPort + " HTTP/1.1\r\n"
                    + "Host: x\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            String response = readUntil(client.getInputStream(), "\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 403"), "默认拦回环目标: " + response);
            assertTrue(response.toLowerCase().contains("x-proxy-error:"),
                    "阻断应答带 x-proxy-error 头(可观测而非静默): " + response);
            assertTrue(response.toLowerCase().contains("target-loopback"),
                    "原因指明回环拦截: " + response);
        }
    }

    @Test
    void connectBlockedByDenyHostListBeforeDns() throws Exception {
        proxy = LoopbackHttpProxy.start(freePort(),
                HostPolicy.of(true, false, List.of("EVIL.example", ".corp.internal")));
        try (Socket client = new Socket("127.0.0.1", proxy.port())) {
            client.setSoTimeout(10_000);
            client.getOutputStream().write(("CONNECT sub.evil.example:443 HTTP/1.1\r\n"
                    + "Host: x\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            String response = readUntil(client.getInputStream(), "\r\n\r\n");
            assertTrue(response.startsWith("HTTP/1.1 403"), "禁配后缀命中即拦(先于 DNS): " + response);
            assertTrue(response.toLowerCase().contains("host-denied"), response);
        }
    }

    // ---- HostPolicy 纯逻辑 ----

    @Test
    void hostPolicyClassifiesAddresses() {
        HostPolicy strict = HostPolicy.of(false, true, List.of());
        assertFalse(strict.resolve("127.0.0.1", 443).allowed(), "回环默认拦");
        assertFalse(strict.resolve("::1", 443).allowed(), "IPv6 回环默认拦");
        assertFalse(strict.resolve("0.0.0.0", 80).allowed(), "any-local 拦");
        assertFalse(strict.resolve("169.254.169.254", 80).allowed(), "link-local(含云元数据)拦");
        assertFalse(strict.resolve("192.168.1.1", 80).allowed(), "blockPrivate 开启时私网拦");
        assertTrue(strict.resolve("8.8.8.8", 443).allowed(), "公网目标放行");
        assertTrue(strict.resolve("1.1.1.1", 443).targets().size() == 1
                && strict.resolve("1.1.1.1", 443).targets().get(0).getPort() == 443,
                "放行结果带回解析地址(检查的地址=连接的地址)");

        HostPolicy loose = HostPolicy.of(true, false, List.of());
        assertTrue(loose.resolve("127.0.0.1", 8080).allowed(), "allowLoopbackTargets 放行回环");
        assertTrue(loose.resolve("192.168.1.1", 80).allowed(),
                "默认不拦私网(online 内网制品服务器合法场景,防可用性回退)");
    }

    @Test
    void rewritePlainRequestStripsHopHeadersAndAddsHost() throws Exception {
        URI uri = URI.create("http://example.com:8080/a/b?q=%20x");
        String rewritten = LoopbackHttpProxy.rewritePlainRequest(
                "GET http://example.com:8080/a/b?q=%20x HTTP/1.1\r\n"
                        + "Proxy-Connection: keep-alive\r\n"
                        + "User-Agent: test\r\n"
                        + "Accept: */*\r\n\r\n",
                "GET", uri);
        assertTrue(rewritten.startsWith("GET /a/b?q=%20x HTTP/1.1\r\n"),
                "请求行改写为 origin-form: " + rewritten);
        assertFalse(rewritten.contains("Proxy-Connection"), "逐跳头不转发");
        assertTrue(rewritten.contains("Host: example.com:8080\r\n"), "Host 缺失时补齐");
        assertTrue(rewritten.endsWith("Connection: close\r\n\r\n"),
                "补 Connection: close(不做响应分帧,泵到 EOF 即合法关闭语义)");
    }

    // ---- helpers ----

    /** 回声服务：收啥回啥（隧道透传断言用）。 */
    private int startEcho() throws IOException {
        echo = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!echo.isClosed()) {
                try (Socket s = echo.accept()) {
                    s.setTcpNoDelay(true);
                    relayBytes(s, s);
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return echo.getLocalPort();
    }

    /** 捕获首包的回声服务（HttpClient CONNECT 端到端断言用）。 */
    private int startCapturingEcho(AtomicReference<byte[]> firstBytes) throws IOException {
        echo = new ServerSocket(0, 16, java.net.InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!echo.isClosed()) {
                try (Socket s = echo.accept()) {
                    s.setTcpNoDelay(true);
                    byte[] buffer = new byte[4096];
                    int n = s.getInputStream().read(buffer);
                    if (n > 0) {
                        firstBytes.set(java.util.Arrays.copyOf(buffer, n));
                        s.getOutputStream().write(buffer, 0, n);
                        s.getOutputStream().flush();
                    }
                } catch (IOException closed) {
                    return;
                }
            }
        });
        return echo.getLocalPort();
    }

    private static void relayBytes(Socket a, Socket b) {
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = a.getInputStream().read(buffer)) != -1) {
                b.getOutputStream().write(buffer, 0, n);
                b.getOutputStream().flush();
            }
        } catch (IOException ignored) {
            // 对端关闭
        }
    }

    /** 明文 origin（绝对 URI 转发断言用）。 */
    private int startOrigin() throws IOException {
        origin = HttpServer.create(new InetSocketAddress(
                java.net.InetAddress.getLoopbackAddress(), 0), 0);
        AtomicInteger hits = new AtomicInteger();
        origin.createContext("/", exchange -> {
            hits.incrementAndGet();
            byte[] body = "hello-proxy".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        origin.start();
        return origin.getAddress().getPort();
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String readUntil(InputStream in, String marker) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            if (sb.toString().endsWith(marker)) {
                return sb.toString();
            }
        }
        return sb.toString();
    }

    private static String hexOf(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(bytes.length, 8); i++) {
            sb.append(String.format("%02x", bytes[i]));
        }
        return sb.toString();
    }
}
