package dev.everyagent.worker.web;

import dev.everyagent.worker.config.WorkerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * ShutdownController 认证行为测试(admin/identify 与 admin/shutdown 共用 authenticate):
 * 正确 key 通过;错误 key / 长度不等 key / 单字符差异 key / 缺失 key 一律 401 且不触发 close。
 * 常数时间本身无法单测,这里验证 MessageDigest.isEqual 与原 equals 的行为等价。
 */
class ShutdownControllerTest {

    private static final String KEY = "admin-key-0123456789abcdef-XYZ";

    private ConfigurableApplicationContext context;
    private ShutdownController controller;

    @BeforeEach
    void setUp() {
        WorkerProperties props = new WorkerProperties();
        WorkerProperties.HubConfig hub = new WorkerProperties.HubConfig();
        hub.setApiKey(KEY);
        props.setHubs(List.of(hub));
        context = mock(ConfigurableApplicationContext.class);
        controller = new ShutdownController(props, context);
    }

    @Test
    void identifyCorrectKeyReturnsWorkerIdentity() {
        var r = controller.identify(KEY);
        assertEquals(200, r.getStatusCode().value());
        assertEquals("worker", r.getBody().path("service").asString());
        verify(context, never()).close();
    }

    @Test
    void shutdownCorrectKeyClosesContext() {
        var r = controller.shutdown(KEY);
        assertEquals(200, r.getStatusCode().value());
        // 异步延迟 500ms 关闭,最多等 5s
        verify(context, timeout(5000)).close();
    }

    @Test
    void wrongKeyRejectedOnBothEndpoints() {
        assertEquals(401, controller.identify("wrong-key").getStatusCode().value());
        assertEquals(401, controller.shutdown("wrong-key").getStatusCode().value());
        verify(context, never()).close();
    }

    @Test
    void lengthMismatchKeyRejected() {
        assertEquals(401, controller.identify(KEY + "x").getStatusCode().value());
        assertEquals(401, controller.shutdown(KEY.substring(1)).getStatusCode().value());
        verify(context, never()).close();
    }

    @Test
    void singleCharDifferenceKeyRejected() {
        char flipped = KEY.charAt(0) == 'a' ? 'b' : 'a';
        assertEquals(401, controller.identify(flipped + KEY.substring(1)).getStatusCode().value());
        verify(context, never()).close();
    }

    @Test
    void missingKeyRejected() {
        assertEquals(401, controller.identify(null).getStatusCode().value());
        assertEquals(401, controller.shutdown(null).getStatusCode().value());
        verify(context, never()).close();
    }
}
