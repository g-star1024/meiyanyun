package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.common.ratelimit.RateLimiter;
import com.meiyun.security.RequirePerm;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 外部广告渠道回传接入（域⑤ 外部渠道 A）。
 *
 * <p>各平台回传事件 → POST /api/marketing/channels/{channelCode}/callback（channelCode ∈ DOUYIN/RED/MEITUAN）。
 *
 * <p><b>安全模型（fail-closed）：</b>
 * <ul>
 *   <li>{@code meiyun.channel.dev-no-auth} 默认 <b>false</b>：必须携带 X-Channel-Ts / X-Channel-Nonce /
 *       X-Channel-Sig，签名 HMAC-SHA256(secret, "{channelCode}\n{ts}\n{nonce}\n{rawBody}")，十六进制小写；
 *       常量时间比较；时间戳偏差 ±{@value #TS_WINDOW_SECONDS}s 拒绝（防重放）；渠道不在白名单 401（不暴露渠道存在性）。</li>
 *   <li>dev 联调显式置 true 才免签（启动告警日志，生产严禁）；密钥未配置一律拒绝。</li>
 *   <li>限流：单 IP 每分钟 {@value #RATE_LIMIT} 次（RateLimiter 抽象，DB 默认 / Redis 生产切换）。</li>
 *   <li>业务幂等：(channel_code, biz_ref) 唯一，同号重复回调返回既有记录（dedup=true），不重复落库。</li>
 *   <li>回调体内 customerId 走客户域硬校验：存在才匹配 PROCESSED；不存在/客户域不可用按外部错误 4xx 拒绝，
 *       不落半匹配脏数据；无 customerId 落 RECEIVED 待后续归因。</li>
 * </ul>
 *
 * <p>本期仅做「接收 + 落库 + 联调 + 列表」。转化归因 / 券核销回写属下游 Backlog。
 */
@RestController
@RequestMapping("/api/marketing/channels")
public class ExternalChannelController {

    private static final Logger log = LoggerFactory.getLogger(ExternalChannelController.class);

    /** 合法渠道白名单。 */
    private static final Map<String, String> CHANNEL_SECRET_KEYS = Map.of(
            "DOUYIN", "douyin",
            "RED", "red",
            "MEITUAN", "meituan");
    private static final List<String> EVENT_TYPES = List.of("CLICK", "LEAD", "CONVERSION");

    private static final int TS_WINDOW_SECONDS = 300;
    private static final int RATE_LIMIT = 60;
    private static final int NONCE_MAX_LEN = 64;
    private static final int BODY_MAX_LEN = 64 * 1024;

    private final ChannelReturnbackRepository repo;
    private final RateLimiter rateLimiter;
    private final CustomerDirectoryClient customerDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** dev 免签开关：默认 false（fail-closed），仅种子/联调环境显式置 true。 */
    @Value("${meiyun.channel.dev-no-auth:false}")
    private boolean devNoAuth;

    @Value("${meiyun.channel.secret.douyin:}")
    private String secretDouyin;
    @Value("${meiyun.channel.secret.red:}")
    private String secretRed;
    @Value("${meiyun.channel.secret.meituan:}")
    private String secretMeituan;

    public ExternalChannelController(ChannelReturnbackRepository repo, RateLimiter rateLimiter,
                                     CustomerDirectoryClient customerDirectory) {
        this.repo = repo;
        this.rateLimiter = rateLimiter;
        this.customerDirectory = customerDirectory;
    }

    @PostConstruct
    void warnOnDevMode() {
        if (devNoAuth) {
            log.warn("【安全告警】外部渠道回传 meiyun.channel.dev-no-auth=true 处于免签模式，仅限联调环境，生产必须置 false");
        }
    }

    @PostMapping("/{channelCode}/callback")
    public Map<String, Object> callback(@PathVariable String channelCode,
                                        @RequestHeader(value = "X-Channel-Ts", required = false) String ts,
                                        @RequestHeader(value = "X-Channel-Nonce", required = false) String nonce,
                                        @RequestHeader(value = "X-Channel-Sig", required = false) String sig,
                                        @RequestBody String rawBody,
                                        HttpServletRequest request) {
        String code = channelCode == null ? "" : channelCode.trim().toUpperCase();
        String clientIp = clientIp(request);
        if (!rateLimiter.tryAcquire("channel-cb:" + clientIp, RATE_LIMIT, 60)) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "回传请求过于频繁，请稍后再试");
        }
        if (code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道编码不可为空");
        }
        if (!CHANNEL_SECRET_KEYS.containsKey(code)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回传鉴权失败");
        }
        if (rawBody == null || rawBody.isBlank() || rawBody.length() > BODY_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调体不可为空且长度需 ≤ 64KB");
        }

        if (!devNoAuth) {
            verifySignature(code, ts, nonce, sig, rawBody);
        }

        Map<String, Object> body = parseBody(rawBody);
        String eventType = body.get("eventType") == null ? "CLICK"
                : body.get("eventType").toString().trim().toUpperCase();
        if (!EVENT_TYPES.contains(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "事件类型不合法：仅支持 CLICK / LEAD / CONVERSION");
        }
        String bizRef = body.get("bizRef") == null ? null : body.get("bizRef").toString().trim();
        if (bizRef != null && bizRef.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "bizRef 长度不可超过 64");
        }

        if (bizRef != null) {
            Optional<ChannelReturnback> dup = repo.findByChannelCodeAndBizRef(code, bizRef);
            if (dup.isPresent()) {
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("received", true);
                out.put("dedup", true);
                out.put("id", dup.get().getId());
                out.put("status", dup.get().getStatus());
                return out;
            }
        }

        ChannelReturnback r = new ChannelReturnback();
        r.setChannelCode(code);
        r.setEventType(eventType);
        r.setExternalUserId(body.get("externalUserId") == null ? null : body.get("externalUserId").toString());
        r.setBizRef(bizRef);
        r.setSigNonce(nonce);
        r.setPayload(rawBody);

        Object cid = body.get("customerId");
        String customerId = cid == null || cid.toString().isBlank() ? null : cid.toString().trim();
        if (customerId != null) {
            CustomerDirectoryClient.CustomerDirectory dir = customerDirectory.requireCustomer(customerId);
            r.setMatchedCustomerId(dir.customerId());
            r.setStatus("PROCESSED");
        } else {
            r.setStatus("RECEIVED");
        }

        ChannelReturnback saved = saveIdempotent(r);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", true);
        out.put("id", saved.getId());
        out.put("status", saved.getStatus());
        return out;
    }

    /** 列出某渠道收到的回传（监控/对账）。 */
    @GetMapping("/{channelCode}/callbacks")
    @RequirePerm("marketing:view")
    public List<ChannelReturnback> listByChannel(@PathVariable String channelCode) {
        return repo.findByChannelCodeOrderByReceivedAtDesc(channelCode.trim().toUpperCase());
    }

    /** 列出全部回传（按状态）。 */
    @GetMapping("/callbacks")
    @RequirePerm("marketing:view")
    public List<ChannelReturnback> listAll(@RequestParam(required = false) String status) {
        if (status == null || status.isBlank()) {
            return repo.findAll();
        }
        return repo.findByStatusOrderByReceivedAtDesc(status.trim().toUpperCase());
    }

    /** 返回某渠道的联调样例（curl + JSON + 签名算法说明），仅登录有营销视图权限可看。 */
    @GetMapping("/{channelCode}/sample")
    @RequirePerm("marketing:view")
    public Map<String, Object> sample(@PathVariable String channelCode) {
        String code = channelCode == null ? "" : channelCode.trim().toUpperCase();
        if (!CHANNEL_SECRET_KEYS.containsKey(code)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渠道编码不合法：" + code);
        }
        Map<String, Object> sampleBody = new LinkedHashMap<>();
        sampleBody.put("eventType", "CONVERSION");
        sampleBody.put("externalUserId", "ext_user_001");
        sampleBody.put("bizRef", "ORDER-20260909-0001");
        sampleBody.put("customerId", "C001");
        String json;
        try {
            json = objectMapper.writeValueAsString(sampleBody);
        } catch (JsonProcessingException e) {
            json = "{}";
        }
        String curl = String.format(
                "curl -X POST http://localhost:8088/api/marketing/channels/%s/callback "
                        + "-H 'Content-Type: application/json' -d '%s'", code, json);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channelCode", code);
        out.put("sampleBody", sampleBody);
        out.put("sampleCurl", curl);
        out.put("devNoAuth", devNoAuth);
        out.put("signAlgorithm", "HMAC-SHA256 hex(secret, channelCode + '\\n' + ts + '\\n' + nonce + '\\n' + rawBody)");
        out.put("signHeaders", "X-Channel-Ts / X-Channel-Nonce / X-Channel-Sig（时间戳偏差 ±300s）");
        out.put("note", devNoAuth
                ? "当前 dev-no-auth=true 免签，仅限联调"
                : "dev-no-auth=false：需配置渠道密钥并携带三个签名头");
        return out;
    }

    // ==================== 内部 ====================

    private void verifySignature(String code, String ts, String nonce, String sig, String rawBody) {
        if (ts == null || ts.isBlank() || nonce == null || nonce.isBlank() || sig == null || sig.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回传鉴权失败：缺少签名头");
        }
        if (nonce.length() > NONCE_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回传鉴权失败");
        }
        long tsMillis;
        try {
            tsMillis = Long.parseLong(ts.trim());
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回传鉴权失败");
        }
        long skew = Math.abs(Instant.now().toEpochMilli() - (tsMillis < 10_000_000_000L ? tsMillis * 1000 : tsMillis));
        if (skew > Duration.ofSeconds(TS_WINDOW_SECONDS).toMillis()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回传时间戳超出允许窗口");
        }
        String secret = secretOf(code);
        if (secret == null || secret.isBlank()) {
            log.error("渠道 {} 签名密钥未配置（meiyun.channel.secret.*），拒绝回传", code);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "渠道密钥未配置，回传暂不可用");
        }
        String expected = hmacSha256Hex(secret, code + "\n" + ts.trim() + "\n" + nonce.trim() + "\n" + rawBody);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), sig.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            log.warn("渠道回传签名校验失败 channel={} nonce={}", code, nonce);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "回传鉴权失败：签名错误");
        }
    }

    private String secretOf(String code) {
        return switch (code) {
            case "DOUYIN" -> secretDouyin;
            case "RED" -> secretRed;
            case "MEITUAN" -> secretMeituan;
            default -> null;
        };
    }

    private String hmacSha256Hex(String secret, String content) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] bytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "签名计算异常");
        }
    }

    private Map<String, Object> parseBody(String rawBody) {
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = objectMapper.readValue(rawBody, Map.class);
            return body;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调体不是合法 JSON");
        }
    }

    private ChannelReturnback saveIdempotent(ChannelReturnback r) {
        try {
            return repo.save(r);
        } catch (DataIntegrityViolationException dup) {
            if (r.getBizRef() != null) {
                return repo.findByChannelCodeAndBizRef(r.getChannelCode(), r.getBizRef())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "回传落库冲突，请稍后重试"));
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT, "回传落库冲突，请稍后重试");
        }
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
