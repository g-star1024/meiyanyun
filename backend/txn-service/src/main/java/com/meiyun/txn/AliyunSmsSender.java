package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 阿里云短信直连发送器（棒⑧卡1）：dysmsapi POP 签名 V1.0（HMAC-SHA1）手写实现，
 * 零重 SDK 依赖，复用现有 RestTemplate。
 *
 * <p>签名口径：公共参数（Action=SendSms/Version=2017-05-25/Format=JSON/RegionId=cn-hangzhou
 * /SignatureMethod=HMAC-SHA1/SignatureVersion=1.0/SignatureNonce/Timestamp）+ 业务参数
 * （PhoneNumbers/SignName/TemplateCode/TemplateParam）按 key 字典序排序，percentEncode 后
 * & 连接为 CanonicalizedQueryString；StringToSign = GET&%2F&percentEncode(CanonicalizedQueryString)；
 * Signature = Base64(HMAC-SHA1(StringToSign, accessKeySecret + "&"))。
 *
 * <p>结果判定：Code=OK → 成功；isv.* / 签名模板类确定性拒绝 → DEAD（重试无意义）；
 * 其余（isp.* 运营商侧/网络异常/未知）→ FAILED（可进重试队列）。
 */
@Component
public class AliyunSmsSender {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter TS_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC);

    private final RestTemplate restTemplate;

    public AliyunSmsSender(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 发送短信。
     *
     * @param endpoint        dysmsapi 端点（默认 dysmsapi.aliyuncs.com）
     * @param accessKeyId     阿里云 AK
     * @param accessKeySecret 阿里云 SK
     * @param phone           收件手机号
     * @param signName        短信签名
     * @param templateCode    模板 CODE
     * @param templateParam   模板参数 JSON（变量名须与控制台模板一致）
     */
    public SmsSendResult send(String endpoint, String accessKeyId, String accessKeySecret,
                              String phone, String signName, String templateCode, String templateParam) {
        try {
            Map<String, String> params = new TreeMap<>();
            params.put("AccessKeyId", accessKeyId);
            params.put("Action", "SendSms");
            params.put("Format", "JSON");
            params.put("RegionId", "cn-hangzhou");
            params.put("SignatureMethod", "HMAC-SHA1");
            params.put("SignatureNonce", UUID.randomUUID().toString());
            params.put("SignatureVersion", "1.0");
            params.put("Timestamp", TS_FORMAT.format(Instant.now()));
            params.put("Version", "2017-05-25");
            params.put("PhoneNumbers", phone);
            params.put("SignName", signName);
            params.put("TemplateCode", templateCode);
            params.put("TemplateParam", templateParam);

            StringBuilder canonical = new StringBuilder();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (canonical.length() > 0) {
                    canonical.append('&');
                }
                canonical.append(percentEncode(e.getKey())).append('=').append(percentEncode(e.getValue()));
            }
            String stringToSign = "GET&" + percentEncode("/") + "&" + percentEncode(canonical.toString());

            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec((accessKeySecret + "&").getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            String signature = Base64.getEncoder()
                    .encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));

            String host = (endpoint == null || endpoint.isBlank()) ? "dysmsapi.aliyuncs.com" : endpoint.trim();
            String url = "https://" + host + "/?Signature=" + percentEncode(signature)
                    + "&" + canonical;
            String resp = restTemplate.getForObject(URI.create(url), String.class);
            if (resp == null || resp.isBlank()) {
                return SmsSendResult.failed("阿里云短信返回空响应");
            }
            JsonNode node = JSON.readTree(resp);
            String code = node.path("Code").asText("");
            String message = node.path("Message").asText(code);
            if ("OK".equalsIgnoreCase(code)) {
                return SmsSendResult.sent(node.path("BizId").asText(null));
            }
            if (code.startsWith("isv.") || "SignatureDoesNotMatch".equals(code)
                    || "InvalidAccessKeyId".equals(code) || "Forbidden".equals(code)) {
                return SmsSendResult.dead("阿里云短信确定性拒绝(" + code + "): " + message);
            }
            return SmsSendResult.failed("阿里云短信发送失败(" + code + "): " + message);
        } catch (Exception e) {
            return SmsSendResult.failed("阿里云短信调用异常: " + e.getMessage());
        }
    }

    /** POP 规范 percentEncode：URLEncoder 后 +→%20、*→%2A、%7E→~。 */
    private static String percentEncode(String value) {
        if (value == null) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    /** 发送结果三态：SENT（成功）/ FAILED（可重试）/ DEAD（确定性拒绝，不再重试）。 */
    public record SmsSendResult(String status, String bizId, String error) {

        static SmsSendResult sent(String bizId) {
            return new SmsSendResult("SENT", bizId, null);
        }

        static SmsSendResult failed(String error) {
            return new SmsSendResult("FAILED", null, error);
        }

        static SmsSendResult dead(String error) {
            return new SmsSendResult("DEAD", null, error);
        }
    }
}
