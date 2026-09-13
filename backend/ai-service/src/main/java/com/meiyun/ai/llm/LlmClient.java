package com.meiyun.ai.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Chat Completions 兼容薄适配层（零重型 AI SDK）。
 * 适用于火山方舟（ARK）等任何 OpenAI 兼容端点：POST {base_url}/chat/completions。
 * 出站超时独立配置（连通/生成可能较慢），不复用服务间 5s RestTemplate。
 */
@Component
public class LlmClient {

    public record ChatResult(String content, int promptTokens, int completionTokens, int totalTokens) {
    }

    private final int connectTimeout;
    private final int readTimeout;
    private final ObjectMapper mapper = new ObjectMapper();

    public LlmClient(@Value("${meiyun.ai.llm-connect-timeout:10000}") int connectTimeout,
                     @Value("${meiyun.ai.llm-read-timeout:60000}") int readTimeout) {
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
    }

    /** 最小一次对话，用于连通性测试与后续无状态生成。 */
    public ChatResult chat(String baseUrl, String apiKey, String model,
                           List<Map<String, String>> messages,
                           Double temperature, Integer maxTokens) {
        String url = normalizeBaseUrl(baseUrl) + "/chat/completions";
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        if (temperature != null) {
            body.put("temperature", temperature);
        }
        if (maxTokens != null) {
            body.put("max_tokens", maxTokens);
        }
        try {
            byte[] rawBytes = client().post()
                    .uri(url)
                    .headers(h -> {
                        h.setContentType(MediaType.APPLICATION_JSON);
                        h.setBearerAuth(apiKey);
                    })
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            if (rawBytes == null || rawBytes.length == 0) {
                throw new IllegalStateException("供应商返回为空");
            }
            String raw = new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8);
            JsonNode resp;
            try {
                resp = mapper.readTree(raw);
            } catch (Exception parseEx) {
                throw new IllegalStateException("供应商返回不是合法 JSON："
                        + (raw.length() > 200 ? raw.substring(0, 200) : raw));
            }
            if (resp == null || !resp.hasNonNull("choices")) {
                throw new IllegalStateException("供应商返回缺少 choices 字段");
            }
            JsonNode message = resp.path("choices").path(0).path("message");
            String content = message.path("content").asText("");
            JsonNode usage = resp.path("usage");
            return new ChatResult(
                    content,
                    usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0));
        } catch (RestClientResponseException e) {
            String detail = parseError(e.getResponseBodyAsString());
            throw new IllegalStateException("供应商拒绝请求（HTTP " + e.getStatusCode().value() + "）：" + detail);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            if (isReadTimeout(e)) {
                throw new IllegalStateException(
                        "供应商响应超时（超过 " + readTimeout + "ms 读取时限，长文案生成可能较慢，可稍后重试或缩短主题）", e);
            }
            throw new IllegalStateException("调用供应商失败：" + e.getMessage(), e);
        }
    }

    /** JDK HttpURLConnection 的读超时会被包装在 RestClientException 成因链里（SocketTimeoutException: Read timed out）。 */
    private static boolean isReadTimeout(Throwable e) {
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof java.net.SocketTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** 连通性测试：一次最短往返 ping。 */
    public ChatResult ping(String baseUrl, String apiKey, String model) {
        return chat(baseUrl, apiKey, model,
                List.of(Map.of("role", "user", "content", "ping")), 0.0, 8);
    }

    private RestClient client() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) Duration.ofMillis(connectTimeout).toMillis());
        factory.setReadTimeout((int) Duration.ofMillis(readTimeout).toMillis());
        // 部分供应商网关把 JSON 响应头返回为 application/octet-stream，默认转换器链会在
        // 内容协商阶段抛 UnknownContentTypeException；这里显式让字节转换器兼容该类型，先收字节再自行解析。
        var byteConverter = new org.springframework.http.converter.ByteArrayHttpMessageConverter();
        byteConverter.setSupportedMediaTypes(java.util.List.of(
                MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_JSON,
                MediaType.TEXT_PLAIN, MediaType.ALL));
        return RestClient.builder()
                .requestFactory(factory)
                .messageConverters(converters -> converters.add(0, byteConverter))
                .build();
    }

    private static String normalizeBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("供应商请求地址不能为空");
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String parseError(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return "无错误详情";
        }
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            JsonNode node = mapper.readTree(responseBody);
            JsonNode msg = node.path("error").path("message");
            return msg.isMissingNode() ? responseBody : msg.asText();
        } catch (Exception ignored) {
            return responseBody.length() > 200 ? responseBody.substring(0, 200) : responseBody;
        }
    }
}
