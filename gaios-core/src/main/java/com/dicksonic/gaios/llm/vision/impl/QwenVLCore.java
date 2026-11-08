package com.dicksonic.gaios.llm.vision.impl;

import com.dicksonic.gaios.llm.UsageListener;
import com.dicksonic.gaios.llm.vision.VisionCore;
import com.dicksonic.gaios.llm.vision.VisionRequest;
import com.dicksonic.gaios.util.AppConfigUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Objects;

/**
 * 通义 VL 视觉核心（DashScope OpenAI 兼容端点，图片以 base64 data URI 内联）。
 * 与 DeepSeekCore 同构：退避重试白名单 / UsageListener 计量旁路 / 契约"永不抛异常，失败返回 error"。
 * 与 DeepSeekCore 的重复代码（isRetryable 等）有意保留：两份实现独立演进，
 * 抽公共 HTTP 层列入 D8，届时以真实差异为据。
 */
public class QwenVLCore implements VisionCore {
    private static final Logger log = LogManager.getLogger(QwenVLCore.class);

    // 取值依据：同 DeepSeekCore——标定实测供应商抖动为秒级瞬时拥塞，首次+2次退避足够覆盖；
    // 失效条件：出现分钟级故障时退避无效，需熔断或备用 provider（D8 议）
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BASE_BACKOFF_MS = 1000; // 第 n 次重试前睡 base << (n-1)，即 1s/2s

    // 取值依据：VL 带图理解生成慢于纯文本，预估 P95 在 20~40s 量级，120s 留 3 倍以上余量；
    // 失效条件：视频多帧批量识别或更高分辨率档位时需上调（M2-2 真机校准此值）
    private static final int REQUEST_TIMEOUT_SECONDS = 120;

    // 取值依据：DashScope 单图上限 10MB；base64 内联膨胀约 1.33 倍后请求体约 13.3MB，在网关默认限制内；
    // 失效条件：接业务系统原图（巡检相机原图常见超 10MB）需先在 web 侧压缩/缩图
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static final String DEFAULT_BASE_URL =
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions";
    private static final String DEFAULT_MODEL = "qwen-vl-max-latest";

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // 计量旁路：web 侧挂回调把真实 token 写进 call_logs（api_type=vision）。volatile 保证跨线程可见
    private volatile UsageListener usageListener;

    public QwenVLCore() {
        this(AppConfigUtil.getOrDefault("vision.base.url", DEFAULT_BASE_URL),
                AppConfigUtil.get("vision.api.key"),
                AppConfigUtil.getOrDefault("vision.model", DEFAULT_MODEL),
                DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF_MS);
    }

    // 全参构造：测试注入本地 stub 地址和毫秒级退避；包可见，生产只走无参构造读配置
    QwenVLCore(String baseUrl, String apiKey, String model, int maxAttempts, long baseBackoffMs) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    public void setUsageListener(UsageListener listener) {
        this.usageListener = listener;
    }

    @Override
    public String recognize(VisionRequest request) {
        Objects.requireNonNull(request, "request 不能为空");
        final HttpRequest req;
        try {
            req = buildRequest(request);
        } catch (Exception e) {
            // 图片缺失/超大/读取失败都走这里：失败原文照打，哨兵返回
            log.error("[QwenVL] 请求体构建失败: {}", e.getMessage(), e);
            return "error";
        }
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode root = tryParse(resp.body());
                if (resp.statusCode() / 100 == 2 && root != null && root.has("choices")) {
                    JsonNode usage = root.path("usage");
                    int prompt = usage.path("prompt_tokens").asInt();
                    int completion = usage.path("completion_tokens").asInt();
                    int total = usage.path("total_tokens").asInt();
                    log.info("[QwenVL] token消耗: prompt={} completion={} total={}", prompt, completion, total);
                    fireUsage(prompt, completion, total);
                    return root.path("choices").get(0).path("message").path("content").asText();
                }
                if (attempt < maxAttempts && isRetryable(resp.statusCode(), root)) {
                    long sleepMs = baseBackoffMs << (attempt - 1);
                    log.warn("[QwenVL] 第{}/{}次调用失败(可重试), status={}, body={}, {}ms后重试",
                            attempt, maxAttempts, resp.statusCode(), resp.body(), sleepMs);
                    Thread.sleep(sleepMs);
                    continue;
                }
                log.error("[QwenVL] 调用失败不再重试, 第{}/{}次, status={}, body={}",
                        attempt, maxAttempts, resp.statusCode(), resp.body());
                return "error";
            } catch (IOException e) {
                if (attempt < maxAttempts) {
                    long sleepMs = baseBackoffMs << (attempt - 1);
                    log.warn("[QwenVL] 第{}/{}次调用IO异常(可重试): {}, {}ms后重试",
                            attempt, maxAttempts, e.getMessage(), sleepMs);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "error";
                    }
                    continue;
                }
                log.error("[QwenVL] 调用IO异常不再重试, 第{}/{}次: {}", attempt, maxAttempts, e.getMessage(), e);
                return "error";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[QwenVL] 调用被中断: {}", e.getMessage(), e);
                return "error";
            } catch (Exception e) {
                log.error("[QwenVL] 调用异常: {}", e.getMessage(), e);
                return "error";
            }
        }
        return "error";
    }

    private HttpRequest buildRequest(VisionRequest request) throws IOException {
        Path image = request.image();
        if (!Files.exists(image)) {
            throw new IOException("图片不存在: " + image.toAbsolutePath());
        }
        long size = Files.size(image);
        if (size > MAX_IMAGE_BYTES) {
            throw new IOException("图片超过 10MB 上限(" + size + " bytes): " + image.getFileName());
        }
        String dataUri = "data:" + mimeOf(image) + ";base64,"
                + Base64.getEncoder().encodeToString(Files.readAllBytes(image));

        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        ArrayNode content = mapper.createArrayNode();
        content.add(mapper.createObjectNode().put("type", "text").put("text", request.prompt()));
        ObjectNode imageItem = mapper.createObjectNode();
        imageItem.put("type", "image_url");
        imageItem.putObject("image_url").put("url", dataUri);
        content.add(imageItem);
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.set("content", content);
        ArrayNode messages = mapper.createArrayNode();
        messages.add(userMsg);
        body.set("messages", messages);
        // 请求体含 base64 全图，日志只打元信息不打体（防盗刷磁盘 + 防图片进日志）
        log.debug("[QwenVL] 请求: model={}, image={}, size={}bytes", model, image.getFileName(), size);
        return HttpRequest.newBuilder(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private static String mimeOf(Path image) {
        String name = image.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".png")) {
            return "image/png";
        }
        if (name.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/jpeg"; // jpg/jpeg 及未知扩展按 jpeg，DashScope 按内容嗅探
    }

    // 与 DeepSeekCore.isRetryable 同构（2026-11-07 实测白名单），重复有意保留，D8 抽公共层
    private static boolean isRetryable(int statusCode, JsonNode root) {
        if (statusCode >= 500 || statusCode == 429) {
            return true;
        }
        if (root == null) {
            return statusCode / 100 == 2; // 2xx 但体不可解析（空体/网关 HTML 页），按瞬时异常重试
        }
        if (root.has("error")) {
            String code = root.path("error").path("code").asText("");
            return "internal_error".equals(code) || "service_unavailable_error".equals(code);
        }
        return false;
    }

    private JsonNode tryParse(String body) {
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null; // 空体/网关 HTML 错误页不可解析，交给状态码判断
        }
    }

    private void fireUsage(int prompt, int completion, int total) {
        UsageListener l = usageListener;
        if (l == null) {
            return;
        }
        try {
            l.onUsage(prompt, completion, total);
        } catch (Exception e) {
            // 监听器是计量旁路，绝不允许打挂主流程（同 D7 spill 原则）
            log.warn("[QwenVL] usage 监听器执行失败，已忽略: {}", e.getMessage(), e);
        }
    }
}