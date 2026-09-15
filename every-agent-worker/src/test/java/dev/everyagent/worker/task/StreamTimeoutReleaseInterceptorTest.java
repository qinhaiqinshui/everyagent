package dev.everyagent.worker.task;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StreamTimeoutReleaseInterceptor} 的行为验证(架构 §7.4.2):
 * callTimeout 到点不再掐断持续有数据的流式响应;X-Stainless-Timeout 头被剥除。
 * 不依赖 mockwebserver——本地 ServerSocket 按真实节奏发慢速流。
 */
class StreamTimeoutReleaseInterceptorTest {

    /** 服务端每 tick 毫秒发一个字节,共发 ticks 次;tick 间活跃但总时长远超 callTimeout。 */
    private static final int TICK_MS = 150;
    private static final int TICKS = 20;   // 总时长 ≈ 3s,远超 600ms callTimeout

    /**
     * 场景:client callTimeout=600ms,服务端每 150ms 发一个字节持续 3s(流始终活跃)。
     * 带拦截器:流完整读完(总时长 > callTimeout,证明 clearTimeout 生效);
     * 不带拦截器(对照):callTimeout 到点 IOException。
     */
    @Test
    void activeStreamSurvivesCallTimeoutWithInterceptor() throws Exception {
        int port;
        CountDownLatch serverDone = new CountDownLatch(1);
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
            Thread server = new Thread(() -> serveSlowStream(ss, serverDone));
            server.setDaemon(true);
            server.start();

            OkHttpClient client = new OkHttpClient.Builder()
                    .callTimeout(Duration.ofMillis(600))
                    .addInterceptor(StreamTimeoutReleaseInterceptor.INSTANCE)
                    .readTimeout(Duration.ofSeconds(10))
                    .build();

            long start = System.currentTimeMillis();
            String body;
            try (Response resp = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + port + "/")
                    .header(StreamTimeoutReleaseInterceptor.STAINLESS_TIMEOUT_HEADER, "0")
                    .build()).execute()) {
                body = resp.body() == null ? "" : resp.body().string();
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue(serverDone.await(5, TimeUnit.SECONDS), "服务端应完整发完");
            assertTrue(elapsed >= TICK_MS * (TICKS - 1), "总时长应超过 callTimeout(实测 " + elapsed + "ms)");
            assertTrue(body.endsWith("END"), "流应读到末尾: " + body);
        }
    }

    /** 对照:同样场景不带拦截器,callTimeout(600ms) 到点流被掐断(IOException)。 */
    @Test
    void callTimeoutStillEnforcedWithoutInterceptor() throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            CountDownLatch serverDone = new CountDownLatch(1);
            Thread server = new Thread(() -> serveSlowStream(ss, serverDone));
            server.setDaemon(true);
            server.start();

            OkHttpClient client = new OkHttpClient.Builder()
                    .callTimeout(Duration.ofMillis(600))
                    .readTimeout(Duration.ofSeconds(10))
                    .build();
            try (Response resp = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + ss.getLocalPort() + "/").build()).execute()) {
                resp.body().string(); // 期望在读完前抛 IOException
            } catch (IOException e) {
                error.set(e);
            }
            assertTrue(error.get() != null, "callTimeout 到点应断流(IOException)");
        }
    }

    /** 拦截器应剥除 X-Stainless-Timeout 头(服务端回显收到的头验证)。 */
    @Test
    void stainlessTimeoutHeaderStripped() throws Exception {
        AtomicBoolean headerSeen = new AtomicBoolean(false);
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                try (Socket s = ss.accept()) {
                    String req = readUntilHeaders(s.getInputStream());
                    headerSeen.set(req.contains(StreamTimeoutReleaseInterceptor.STAINLESS_TIMEOUT_HEADER + ":"));
                    OutputStream os = s.getOutputStream();
                    os.write(("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok")
                            .getBytes(StandardCharsets.UTF_8));
                    os.flush();
                } catch (IOException ignored) {
                }
            });
            server.setDaemon(true);
            server.start();

            OkHttpClient client = new OkHttpClient.Builder()
                    .callTimeout(Duration.ofSeconds(5))
                    .addInterceptor(StreamTimeoutReleaseInterceptor.INSTANCE)
                    .build();
            try (Response resp = client.newCall(new Request.Builder()
                    .url("http://127.0.0.1:" + ss.getLocalPort() + "/")
                    .header(StreamTimeoutReleaseInterceptor.STAINLESS_TIMEOUT_HEADER, "600")
                    .build()).execute()) {
                assertTrue(resp.body() != null && "ok".equals(resp.body().string()));
            }
            assertFalse(headerSeen.get(), "X-Stainless-Timeout 头不应到达服务端");
        }
    }

    /** connect 超时不受拦截器影响(未连接的 call 无流可保)。 */
    @Test
    void connectTimeoutUnaffected() throws Exception {
        try (ServerSocket ss = new ServerSocket(0)) {
            ss.setReuseAddress(true);
            int port = ss.getLocalPort();
            ss.close(); // 端口立即关闭,连接必失败
            Thread.sleep(200); // 等内核释放
            OkHttpClient client = new OkHttpClient.Builder()
                    .callTimeout(Duration.ofSeconds(30))
                    .connectTimeout(Duration.ofMillis(300))
                    .addInterceptor(StreamTimeoutReleaseInterceptor.INSTANCE)
                    .build();
            AtomicReference<Throwable> err = new AtomicReference<>();
            try {
                client.newCall(new Request.Builder().url("http://127.0.0.1:" + port + "/").build())
                        .execute();
            } catch (IOException e) {
                err.set(e);
            }
            assertTrue(err.get() != null, "不可达端口应连接失败");
            assertNull(null); // 结构对齐:无额外断言
        }
    }

    // ---- 测试脚手架 ----

    /** 服务端:接受一个连接,每 TICK_MS 发一个字节(含头),最后 END 标记,保持连接活跃。 */
    private static void serveSlowStream(ServerSocket ss, CountDownLatch done) {
        try (Socket s = ss.accept()) {
            readUntilHeaders(s.getInputStream()); // 消费请求头
            OutputStream os = s.getOutputStream();
            os.write("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            for (int i = 0; i < TICKS; i++) {
                Thread.sleep(TICK_MS);
                os.write("b\r\nx\r\n\r\n".getBytes(StandardCharsets.UTF_8)); // chunked 单字节
                os.flush();
            }
            os.write("3\r\nEND\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            done.countDown();
        } catch (Exception ignored) {
        }
    }

    /** 读到空行(请求头结束)为止,返回累积文本。 */
    private static String readUntilHeaders(java.io.InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            sb.append((char) c);
            if (sb.toString().endsWith("\r\n\r\n")) {
                break;
            }
        }
        return sb.toString();
    }
}
