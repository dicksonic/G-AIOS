package com.dicksonic.gaios;

import com.dicksonic.gaios.util.AppConfigUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.ObjectInputFilter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;

public class DeepSeekCore implements LLMCore {
    private static final Logger log = LogManager.getLogger(DeepSeekCore.class);

    private static final String URL = AppConfigUtil.get("deepseek.base.url");
    private final String apiKey = AppConfigUtil.get("deepseek.api.key");
    private final String model = AppConfigUtil.get("deepseek.model");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String chat(String systemPrompt, String userMessage) {
        if (Objects.isNull(apiKey)){
            throw new IllegalStateException("缺少环境变量 DEEPSEEK_API_KEY");
        }
        log.info("调用 DeepSeek，systemPrompt 长度={}", systemPrompt.length());
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", model);
            body.set("messages", mapper.createArrayNode()
                    .add(mapper.createObjectNode().put("role", "system").put("content", systemPrompt))
                    .add(mapper.createObjectNode().put("role", "user").put("content", userMessage)));
            log.debug("请求体：{}", body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            log.error(e.getMessage(),e);
            return "error";
        }
    }
}