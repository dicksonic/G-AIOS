package com.dicksonic.gaios.llm.impl;

import com.dicksonic.gaios.llm.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DeepSeekCore 重试/计量测试：JDK 内置 HttpServer 手写 stub，脚本化应答（按调用次序出餐）。
 * 覆盖：瞬时 5xx 重试成功 / 重试耗尽 / 4xx 不重试 / 200包错误体白名单重试 / 监听器异常不打挂主流程。
 */
class DeepSeekCoreRetryTest {

    private static final String OK_BODY = """
            {"choices":[{"index":0,"**REMOVED**":{"role":"assistant","content":"你好"}}],
             "usage":{"prompt_tokens":5,"completion_tokens":10,"total_tokens":15}}""";
    private static final String BUSY_BODY = """
            {"error":{"**REMOVED**":"Service is too busy","type":"service_unavailable_error","param":null,"code":"service_unavailable_error"}}""";
    private static final String AUTH_BODY = """
            {"error":{"**REMOVED**":"Authentication Fails","type":"authentication_error","param":null,"code":"authentication_error"}}""";

    private record Reply(int status, String body) {
    }

    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();
    private final List<Reply> script = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = hits.incrementAndGet();
            exchange.getRequestBody().readAllBytes(); // 消费请求体，避免连接复用残留
            Reply r = script.get(Math.min(n, script.size()) - 1); // 超出脚本则重播最后一条
            byte[] bytes = r.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(r.status(), bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private DeepSeekCore newCore() {
        // baseBackoff=10ms：测试只验证重试次数与顺序，不等真实秒级退避
        return new DeepSeekCore(baseUrl, "test-key", "deepseek-chat", 0.3f, 3, 10);
    }

    private static List<Message> msgs() {
        return List.of(new Message("user", "你好"));
    }

    @Test
    void 瞬时故障_前两次503第三次成功() {
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(200, OK_BODY));
        DeepSeekCore core = newCore();
        int[][] usage = new int[1][];
        core.setUsageListener((p, c, t) -> usage[0] = new int[]{p, c, t});

        String out = core.chat(msgs());

        assertEquals("你好", out);
        assertEquals(3, hits.get(), "应重试到第3次才成功");
        assertArrayEquals(new int[]{5, 10, 15}, usage[0], "计量回调应在成功响应时触发一次");
    }

    @Test
    void 持续故障_重试耗尽返回error且不计量() {
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(503, BUSY_BODY));
        DeepSeekCore core = newCore();
        AtomicInteger listenerCalls = new AtomicInteger();
        core.setUsageListener((p, c, t) -> listenerCalls.incrementAndGet());

        String out = core.chat(msgs());

        assertEquals("error", out, "契约：chat 永不抛异常，失败返回 error");
        assertEquals(3, hits.get());
        assertEquals(0, listenerCalls.get(), "失败响应无 usage，监听器不应触发");
    }

    @Test
    void 认证错误_4xx不重试() {
        script.add(new Reply(401, AUTH_BODY));
        script.add(new Reply(200, OK_BODY));
        DeepSeekCore core = newCore();

        String out = core.chat(msgs());

        assertEquals("error", out);
        assertEquals(1, hits.get(), "4xx 重试无意义，应只调用一次");
    }

    @Test
    void 错误体走200_白名单错误码仍可重试() {
        // 2026-11-07 实测：service_unavailable_error 以无 choices 的错误体返回
        script.add(new Reply(200, BUSY_BODY));
        script.add(new Reply(200, OK_BODY));
        DeepSeekCore core = newCore();

        String out = core.chat(msgs());

        assertEquals("你好", out);
        assertEquals(2, hits.get());
    }

    @Test
    void 监听器抛异常_不影响主流程() {
        script.add(new Reply(200, OK_BODY));
        DeepSeekCore core = newCore();
        core.setUsageListener((p, c, t) -> {
            throw new RuntimeException("模拟计量落库失败");
        });

        String out = core.chat(msgs());

        assertEquals("你好", out, "计量旁路异常不得打挂主流程");
    }
}