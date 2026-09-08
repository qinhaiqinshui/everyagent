package dev.everyagent.hub;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * 测试用最小 WS 客户端(JDK HttpClient,与 worker 同款技术)。
 */
final class WsTestClient implements AutoCloseable {

    record Frame(String text, boolean closed) {
    }

    static final class Collector implements WebSocket.Listener {
        final BlockingQueue<Frame> frames = new LinkedBlockingQueue<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        private final StringBuilder partial = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                frames.add(new Frame(partial.toString(), false));
                partial.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            frames.add(new Frame(null, true));
            closed.complete(null);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }

    final WebSocket ws;
    final Collector collector;

    private WsTestClient(WebSocket ws, Collector collector) {
        this.ws = ws;
        this.collector = collector;
    }

    static WsTestClient connect(URI uri) {
        HttpClient client = HttpClient.newHttpClient();
        Collector collector = new Collector();
        WebSocket webSocket = client.newWebSocketBuilder().buildAsync(uri, collector).join();
        return new WsTestClient(webSocket, collector);
    }

    void send(String text) {
        ws.sendText(text, true).join();
    }

    /** 等到出现满足条件的帧文本;超时抛 AssertionError。 */
    String await(Predicate<String> condition, String what) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            Frame f = collector.frames.poll();
            if (f != null) {
                if (f.closed()) {
                    throw new AssertionError("connection closed while waiting for: " + what);
                }
                if (condition.test(f.text())) {
                    return f.text();
                }
                continue; // 不匹配的帧继续排空
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        throw new AssertionError("timeout waiting for: " + what);
    }

    String awaitEvent(String eventName) {
        return await(t -> t.contains("\"event\":\"" + eventName + "\""), eventName);
    }

    void awaitClosed() {
        collector.closed.orTimeout(5, TimeUnit.SECONDS).join();
    }

    @Override
    public void close() {
        ws.abort();
    }
}
