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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

public class DeepSeekCore implements LLMCore {
    private static final Logger log = LogManager.getLogger(DeepSeekCore.class);

    private static final String URL = AppConfigUtil.get("deepseek.base.url");
    private final String apiKey = AppConfigUtil.get("deepseek.api.key");
    private final String model = AppConfigUtil.get("deepseek.model");
    private final float temperature = AppConfigUtil.getFloat("deepseek.temperature",1.0f);
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String chat(List<Message> messages) {
        if (Objects.isNull(apiKey)){
            throw new IllegalStateException("缺少环境变量 DEEPSEEK_API_KEY");
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.put("temperature",temperature);
            ArrayNode arr = mapper.createArrayNode();
            for (Message m : messages) {
                arr.add(mapper.createObjectNode().put("role", m.role()).put("content", m.content()));
            }
            body.set("messages", arr);
            log.debug("请求体：{}", body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            if (!root.has("choices")) {
                // API 返回错误对象（欠费/限流/key失效）：原文打日志，别让 NPE 掩盖真凶
                log.error("DeepSeek返回异常: {}", resp.body());
                throw new RuntimeException("DeepSeek API 错误: "
                        + root.path("error").path("message").asText("未知错误，见上方日志"));
            }
            // 每次调用记录 token 消耗——这是"审计回放中心"的第一个数据字段
            JsonNode usage = root.path("usage");
            log.info("token消耗: prompt={} completion={} total={}",
                    usage.path("prompt_tokens").asInt(),
                    usage.path("completion_tokens").asInt(),
                    usage.path("total_tokens").asInt());

            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error(e.getMessage(),e);
            return "error";
        }
    }
}