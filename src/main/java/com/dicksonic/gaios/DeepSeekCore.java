package com.dicksonic.gaios;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class DeepSeekCore implements LLMCore {
    private static final String URL = "https://api.deepseek.com/chat/completions";
    private final String apiKey = System.getenv("DEEPSEEK_API_KEY");
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String chat(String systemPrompt, String userMessage) {
        if (apiKey == null) throw new IllegalStateException("缺少环境变量 DEEPSEEK_API_KEY");
        try {
            String body = mapper.writeValueAsString(mapper.createObjectNode()
                    .put("model", "deepseek-chat")
                    .put("response_format", mapper.createObjectNode().put("type", "json_object"))
                    .set("messages", mapper.createArrayNode()
                            .add(mapper.createObjectNode().put("role", "system").put("content", systemPrompt))
                            .add(mapper.createObjectNode().put("role", "user").put("content", userMessage))));

            HttpRequest req = HttpRequest.newBuilder(URI.create(URL))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(60))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = mapper.readTree(resp.body());
            return root.path("choices").get(0).path("message").path("content").asText();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}