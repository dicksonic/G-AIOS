package com.dicksonic.gaios.llm.impl;

import com.dicksonic.gaios.llm.LLMCore;
import com.dicksonic.gaios.llm.Message;
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
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class DeepSeekCore implements LLMCore {
    private static final Logger log = LogManager.getLogger(DeepSeekCore.class);

    // 取值依据：2026-11-07 标定实测 DeepSeek 抖动为秒级瞬时拥塞（相邻 1~2s 的调用即恢复），
    // 首次+2次退避重试足够覆盖；失效条件：出现分钟级故障时退避无效，需熔断或备用 provider（D8 议）
    private static final int DEFAULT_MAX_ATTEMPTS = 3;
    private static final long DEFAULT_BASE_BACKOFF_MS = 1000; // 第 n 次重试前睡 base << (n-1)，即 1s/2s

    // 取值依据：ReAct 单轮生成实测 P95 < 5s，60s 留了 10 倍以上余量；
    // 失效条件：改流式输出或 M3 长报告生成时需上调
    private static final int REQUEST_TIMEOUT_SECONDS = 60;

    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxAttempts;
    private final long baseBackoffMs;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    // 计量旁路：web 侧挂回调把真实 token 写进 call_logs。volatile 保证跨线程可见
    private volatile UsageListener usageListener;

    public interface UsageListener extends com.dicksonic.gaios.llm.UsageListener{
        void onUsage(int promptTokens, int completionTokens, int totalTokens);
    }

    public DeepSeekCore() {
        this(AppConfigUtil.get("deepseek.base.url"),
                AppConfigUtil.get("deepseek.api.key"),
                AppConfigUtil.get("deepseek.model"),
                AppConfigUtil.getFloat("deepseek.temperature", 1.0f),
                DEFAULT_MAX_ATTEMPTS, DEFAULT_BASE_BACKOFF_MS);
    }

    // 全参构造：测试注入本地 stub 地址和毫秒级退避；包可见，生产只走无参构造读配置
    DeepSeekCore(String baseUrl, String apiKey, String model, float temperature,
                 int maxAttempts, long baseBackoffMs) {
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        this.maxAttempts = maxAttempts;
        this.baseBackoffMs = baseBackoffMs;
    }

    public void setUsageListener(UsageListener listener) {
        this.usageListener = listener;
    }

    @Override
    public String chat(List<Message> messages) {
        if (Objects.isNull(apiKey)) {
            throw new IllegalStateException("缺少环境变量 DEEPSEEK_API_KEY");
        }
        final HttpRequest req;
        try {
            req = buildRequest(messages);
        } catch (Exception e) {
            log.error("[DeepSeek] 请求体构建失败: {}", e.getMessage(), e);
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
                    // 每次调用记录 token 消耗——这是"审计回放中心"的第一个数据字段
                    log.info("token消耗: prompt={} completion={} total={}", prompt, completion, total);
                    fireUsage(prompt, completion, total);
                    return root.path("choices").get(0).path("message").path("content").asText();
                }
                if (attempt < maxAttempts && isRetryable(resp.statusCode(), root)) {
                    long sleepMs = baseBackoffMs << (attempt - 1);
                    // 失败原文照打，便于事后对 request_id
                    log.warn("[DeepSeek] 第{}/{}次调用失败(可重试), status={}, body={}, {}ms后重试",
                            attempt, maxAttempts, resp.statusCode(), resp.body(), sleepMs);
                    Thread.sleep(sleepMs);
                    continue;
                }
                // 终态失败：业务性失败不刷堆栈（不是代码 bug），原文 + 状态码就是证据
                log.error("[DeepSeek] 调用失败不再重试, 第{}/{}次, status={}, body={}",
                        attempt, maxAttempts, resp.statusCode(), resp.body());
                return "error";
            } catch (IOException e) {
                // 含 HttpTimeoutException / 连接重置，属可重试类
                if (attempt < maxAttempts) {
                    long sleepMs = baseBackoffMs << (attempt - 1);
                    log.warn("[DeepSeek] 第{}/{}次调用IO异常(可重试): {}, {}ms后重试",
                            attempt, maxAttempts, e.getMessage(), sleepMs);
                    try {
                        Thread.sleep(sleepMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return "error";
                    }
                    continue;
                }
                log.error("[DeepSeek] 调用IO异常不再重试, 第{}/{}次: {}", attempt, maxAttempts, e.getMessage(), e);
                return "error";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("[DeepSeek] 调用被中断: {}", e.getMessage(), e);
                return "error";
            } catch (Exception e) {
                log.error("[DeepSeek] 调用异常: {}", e.getMessage(), e);
                return "error";
            }
        }
        return "error";
    }

    private HttpRequest buildRequest(List<Message> messages) throws Exception {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        body.put("temperature", temperature);
        ArrayNode arr = mapper.createArrayNode();
        for (Message m : messages) {
            arr.add(mapper.createObjectNode().put("role", m.role()).put("content", m.content()));
        }
        body.set("messages", arr);
        log.debug("请求体：{}", body);
        return HttpRequest.newBuilder(URI.create(baseUrl))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                .build();
    }

    private static boolean isRetryable(int statusCode, JsonNode root) {
        if (statusCode >= 500 || statusCode == 429) {
            return true;
        }
        // 2xx 但体不可解析（空体/HTML 网关页）：2026-11-06 实测空体"未知错误"，属瞬时异常
        if (root == null) {
            return statusCode / 100 == 2;
        }
        // 200 包错误体：只白名单重试瞬时错误码（2026-11-07 实测 service_unavailable_error 走此路径）；
        // 认证/欠费/参数错重试无意义，直接终态
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
            log.warn("[DeepSeek] usage 监听器执行失败，已忽略: {}", e.getMessage(), e);
        }
    }
}