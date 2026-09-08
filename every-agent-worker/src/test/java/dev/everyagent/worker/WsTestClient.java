package dev.everyagent.worker;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/** 测试用最小 WS 客户端(与 hub 模块测试同款技术)。 */
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
    private final java.util.List<String> seen = new java.util.concurrent.CopyOnWriteArrayList<>();

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

    String await(Predicate<String> condition, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        // 先查历史(sub 前错过的帧不会因消费顺序丢失)
        for (String h : seen) {
            if (condition.test(h)) {
                return h;
            }
        }
        while (System.currentTimeMillis() < deadline) {
            Frame f = collector.frames.poll();
            if (f != null) {
                if (f.closed()) {
                    throw new AssertionError("连接关闭,等待: " + what);
                }
                seen.add(f.text());
                if (condition.test(f.text())) {
                    return f.text();
                }
                continue;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        throw new AssertionError("超时,等待: " + what);
    }

    String awaitEvent(String eventName) {
        return await(t -> t.contains("\"event\":\"" + eventName + "\""), eventName);
    }

    /**
     * 只等新帧(不查历史):同任务多轮运行的历史 done 帧、其他测试滞留的迟到帧
     * 都不会误匹配——再运行/续跑场景的终态等待必须用本方法且谓词含 taskId。
     */
    String awaitNew(Predicate<String> condition, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Frame f = collector.frames.poll();
            if (f != null) {
                if (f.closed()) {
                    throw new AssertionError("连接关闭,等待: " + what);
                }
                seen.add(f.text());
                if (condition.test(f.text())) {
                    return f.text();
                }
                continue;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        throw new AssertionError("超时,等待: " + what);
    }

    /**
     * 等待历史中出现第 n 条包含 needle 的帧。presence 快照会先注入一条 online,
     * 验证「真实重连」须按计数等第二条,单帧谓词的 await 区分不了两者。
     */
    void awaitCount(int n, String needle, String what) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Frame f = collector.frames.poll();
            if (f != null) {
                if (f.closed()) {
                    throw new AssertionError("连接关闭,等待: " + what);
                }
                seen.add(f.text());
            }
            if (countSeen(needle) >= n) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted");
            }
        }
        throw new AssertionError("超时,等待: " + what + "(期望 " + n + " 条,实际 " + countSeen(needle) + ")");
    }

    private int countSeen(String needle) {
        int count = 0;
        for (String h : seen) {
            if (h.contains(needle)) {
                count += 1;
            }
        }
        return count;
    }

    /** 等待 ms 内不再出现包含 needle 的帧,返回是否始终未出现(否定断言用)。 */
    boolean absentAfter(String needle, long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Frame f = collector.frames.poll();
            if (f != null && f.text() != null && f.text().contains(needle)) {
                return false;
            }
            if (f == null) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return !historyContains(needle);
                }
            }
        }
        return !historyContains(needle);
    }

    /** 已见帧历史里是否包含 needle(await 会把路过的帧都记入历史)。 */
    boolean historyContains(String needle) {
        for (String h : seen) {
            if (h.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    void drain(long ms) {
        long deadline = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < deadline) {
            Frame f = collector.frames.poll();
            if (f == null) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() {
        ws.abort();
    }
}
