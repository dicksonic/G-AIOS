package com.dicksonic.gaios.llm.vision.impl;

import com.dicksonic.gaios.llm.vision.VisionRequest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QwenVLCore 重试/计量/请求形态测试：JDK 内置 HttpServer 手写 stub，脚本化应答。
 * 与 DeepSeekCoreRetryTest 同构；额外覆盖视觉特有路径：data URI 请求体 / 图片缺失不发起 HTTP。
 */
class QwenVLCoreTest {

    private static final String OK_BODY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"发现隐患：基坑边堆土过高"}}],
             "usage":{"prompt_tokens":1200,"completion_tokens":30,"total_tokens":1230}}""";
    private static final String BUSY_BODY = """
            {"error":{"message":"Service is too busy","type":"service_unavailable_error","param":null,"code":"service_unavailable_error"}}""";
    private static final String AUTH_BODY = """
            {"error":{"message":"Authentication Fails","type":"authentication_error","param":null,"code":"authentication_error"}}""";

    private record Reply(int status, String body) {
    }

    @TempDir
    Path tempDir;
    private Path testImage;
    private HttpServer server;
    private String baseUrl;
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger bodiesWithImage = new AtomicInteger(); // 请求体含 data URI 的次数
    private final List<Reply> script = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        testImage = tempDir.resolve("site-photo.jpg");
        Files.write(testImage, "fake-image-bytes".getBytes(StandardCharsets.UTF_8)); // stub 不校验图片内容
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            int n = hits.incrementAndGet();
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (reqBody.contains("data:image/jpeg;base64,")) {
                bodiesWithImage.incrementAndGet();
            }
            Reply r = script.get(Math.min(n, script.size()) - 1);
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

    private QwenVLCore newCore() {
        // baseBackoff=10ms：测试只验证重试次数与顺序，不等真实秒级退避
        return new QwenVLCore(baseUrl, "test-key", "qwen-vl-max-latest", 3, 10);
    }

    private VisionRequest req() {
        return new VisionRequest(testImage, "识别图中的安全隐患");
    }

    @Test
    void 瞬时故障_前两次503第三次成功() {
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(200, OK_BODY));
        QwenVLCore core = newCore();
        int[][] usage = new int[1][];
        core.setUsageListener((p, c, t) -> usage[0] = new int[]{p, c, t});

        String out = core.recognize(req());

        assertEquals("发现隐患：基坑边堆土过高", out);
        assertEquals(3, hits.get(), "应重试到第3次才成功");
        assertEquals(3, bodiesWithImage.get(), "每次出站请求都必须携带 base64 data URI 图片");
        assertArrayEquals(new int[]{1200, 30, 1230}, usage[0], "计量回调应在成功响应时触发一次");
    }

    @Test
    void 持续故障_重试耗尽返回error且不计量() {
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(503, BUSY_BODY));
        script.add(new Reply(503, BUSY_BODY));
        QwenVLCore core = newCore();
        AtomicInteger listenerCalls = new AtomicInteger();
        core.setUsageListener((p, c, t) -> listenerCalls.incrementAndGet());

        String out = core.recognize(req());

        assertEquals("error", out, "契约：recognize 永不抛异常，失败返回 error");
        assertEquals(3, hits.get());
        assertEquals(0, listenerCalls.get(), "失败响应无 usage，监听器不应触发");
    }

    @Test
    void 认证错误_4xx不重试() {
        script.add(new Reply(401, AUTH_BODY));
        script.add(new Reply(200, OK_BODY));

        String out = newCore().recognize(req());

        assertEquals("error", out);
        assertEquals(1, hits.get(), "4xx 重试无意义，应只调用一次");
    }

    @Test
    void 错误体走200_白名单错误码仍可重试() {
        script.add(new Reply(200, BUSY_BODY));
        script.add(new Reply(200, OK_BODY));

        String out = newCore().recognize(req());

        assertEquals("发现隐患：基坑边堆土过高", out);
        assertEquals(2, hits.get());
    }

    @Test
    void 监听器抛异常_不影响主流程() {
        script.add(new Reply(200, OK_BODY));
        QwenVLCore core = newCore();
        core.setUsageListener((p, c, t) -> {
            throw new RuntimeException("模拟计量落库失败");
        });

        String out = core.recognize(req());

        assertEquals("发现隐患：基坑边堆土过高", out, "计量旁路异常不得打挂主流程");
    }

    @Test
    void 图片不存在_返回error且不发起HTTP() {
        VisionRequest bad = new VisionRequest(tempDir.resolve("not-exists.jpg"), "识别隐患");

        String out = newCore().recognize(bad);

        assertEquals("error", out);
        assertEquals(0, hits.get(), "图片缺失应在构建请求阶段失败，不消耗配额");
    }
}