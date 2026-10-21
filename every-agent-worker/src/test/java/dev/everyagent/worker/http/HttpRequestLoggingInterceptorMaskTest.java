package dev.everyagent.worker.http;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审计 L-3 回归:DEBUG 请求体日志不得原文携带密钥形态(经 {@code SecretPatterns.maskSecrets}
 * 形态掩码)——对话上下文可能含用户粘贴/历史回显的密钥。拦截器是纯日志副作用(架构 §5.2):
 * 请求头/请求体本身零改动,仅日志字符串被掩码。
 */
class HttpRequestLoggingInterceptorMaskTest {

    private final Logger logger =
            (Logger) LoggerFactory.getLogger(HttpRequestLoggingInterceptor.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void debugBodyLogMasksCredentialShapes() throws IOException {
        Level original = logger.getLevel();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
        try {
            String anthropicKey = "sk-ant-sid01-" + "A".repeat(58) + "6280";
            String ghp = "ghp_" + "c1d2e3f4a5".repeat(4);
            String body = "{\"model\":\"claude-x\",\"messages\":[{\"role\":\"user\","
                    + "\"content\":\"部署用这把 key: " + anthropicKey + " 和 " + ghp + "\"}]}";
            Request request = new Request.Builder()
                    .url("https://api.example.com/v1/chat")
                    .header("Authorization", "Bearer " + "t".repeat(30))
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            Interceptor.Chain chain = mock(Interceptor.Chain.class);
            when(chain.request()).thenReturn(request);
            when(chain.proceed(request)).thenReturn(new Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK").build());

            HttpRequestLoggingInterceptor.SHARED.intercept(chain);

            String logged = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining("\n"));
            assertTrue(logged.contains("==== HTTP REQUEST"), "DEBUG 出口确实触发: " + logged);
            assertTrue(logged.contains("sk-****"), "sk- 形态保留格式前缀、本体打码: " + logged);
            assertTrue(logged.contains("ghp_****"), "ghp_ 形态保留格式前缀、本体打码: " + logged);
            assertFalse(logged.contains(anthropicKey), "密钥原文不得落 DEBUG 日志");
            assertFalse(logged.contains(ghp), "ghp 原文不得落 DEBUG 日志");
            assertTrue(logged.contains("Bearer ***"), "既有 Authorization 头脱敏不回退");
            assertFalse(logged.contains("tttt"), "请求头里的 token 原文不得落日志");
        } finally {
            logger.setLevel(original);
        }
    }
}
