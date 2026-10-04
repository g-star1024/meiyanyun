package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 棒⑧卡3 电子签厂商回调接入（公开端点·免登录与验签 fail-closed 强绑定绝无鉴权真空）：
 * 厂商签署事件 → POST /api/txn/esign/callback（application.yml public-paths 显式条目）。
 *
 * <p><b>安全模型（复刻域⑤外部渠道回传范式，fail-closed）：</b>
 * <ul>
 *   <li>{@code meiyun.esign.dev-no-auth} 默认 <b>false</b>：必须携带 X-ESign-Ts / X-ESign-Nonce /
 *       X-ESign-Sig，签名 HMAC-SHA256(secret, "ESIGN\n{ts}\n{nonce}\n{rawBody}")，十六进制小写；
 *       常量时间比较；时间戳偏差 ±{@value #TS_WINDOW_SECONDS}s 拒绝（防重放）；nonce ≤64 字符。</li>
 *   <li>dev 联调显式置 true 才免签（启动告警日志，生产严禁）；接入位未启用或密钥未配置一律 503 拒绝。</li>
 *   <li>密钥取用：配置目录 ESIGN_SECRET 库启用值 → env 兜底（ESIGN_DIRECT 开关关闭时回调面整体关闭）。</li>
 *   <li>限流：单 IP 每分钟 {@value #RATE_LIMIT} 次（单实例内存固定窗口；多实例部署须切共享实现——
 *       meiyun-common RateLimiter 抽象就绪，本服务暂未装配实现 bean）。</li>
 *   <li>回调体 ≤{@value #BODY_MAX_LEN} 字节；flowId 与库内记录一致性核验防串号；
 *       SIGNED 重复回调幂等 dedup 早返回，不重复落库/审计。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/txn/esign")
public class ESignCallbackController {

    private static final Logger log = LoggerFactory.getLogger(ESignCallbackController.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int TS_WINDOW_SECONDS = 300;
    private static final int RATE_LIMIT = 60;
    private static final int NONCE_MAX_LEN = 64;
    private static final int BODY_MAX_LEN = 64 * 1024;

    private final ContractService contractService;
    private final IntegrationConfigClient integrationConfig;

    /** dev 免签开关：默认 false（fail-closed），仅种子/联调环境显式置 true。 */
    @Value("${meiyun.esign.dev-no-auth:false}")
    private boolean devNoAuth;

    /** env 兜底：配置窗口启用值优先，本组仅在配置窗未启用时生效。 */
    @Value("${meiyun.esign.direct-enabled:false}")
    private boolean envEnabled;
    @Value("${meiyun.esign.secret:}")
    private String envSecret;

    /** 单实例内存固定窗口（key=客户端 IP，[0]=窗口起始毫秒 [1]=已用配额）。 */
    private final ConcurrentHashMap<String, long[]> rateWindows = new ConcurrentHashMap<>();

    public ESignCallbackController(ContractService contractService, IntegrationConfigClient integrationConfig) {
        this.contractService = contractService;
        this.integrationConfig = integrationConfig;
    }

    @PostConstruct
    void warnOnDevMode() {
        if (devNoAuth) {
            log.warn("【安全告警】电子签回调 meiyun.esign.dev-no-auth=true 处于免签模式，仅限联调环境，生产必须置 false");
        }
    }

    @PostMapping("/callback")
    public ResponseEntity<Map<String, Object>> callback(
            @RequestHeader(value = "X-ESign-Ts", required = false) String ts,
            @RequestHeader(value = "X-ESign-Nonce", required = false) String nonce,
            @RequestHeader(value = "X-ESign-Sig", required = false) String sig,
            @RequestBody(required = false) String rawBody,
            HttpServletRequest request) {
        if (!tryAcquire(clientIp(request))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "回调频率超限，请稍后重试");
        }
        if (rawBody == null || rawBody.isBlank()
                || rawBody.getBytes(StandardCharsets.UTF_8).length > BODY_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调体为空或超出 64KB 上限");
        }
        if (!devNoAuth) {
            verifySignature(ts, nonce, sig, rawBody);
        }
        JsonNode body = parseBody(rawBody);
        String contractNo = textOf(body, "contractNo", 24);
        String flowId = textOf(body, "flowId", 64);
        String event = textOf(body, "event", 16);
        if (contractNo == null || flowId == null || event == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调体缺必填字段（contractNo/flowId/event）");
        }
        String signerName = textOf(body, "signerName", 32);
        String signature = textOf(body, "signature", 0);
        ContractService.ESignCallbackOutcome outcome =
                contractService.applySignCallback(contractNo, flowId, event, signerName, signature);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("received", true);
        out.put("dedup", outcome.dedup());
        out.put("contractNo", contractNo);
        out.put("esignStatus", outcome.contract().getEsignStatus());
        return ResponseEntity.ok(out);
    }

    /** 三头验签：缺一 401／nonce 超长 401／时间戳偏差 ±300s 401／密钥未配置 503／签名不等 401。 */
    private void verifySignature(String ts, String nonce, String sig, String rawBody) {
        if (ts == null || ts.isBlank() || nonce == null || nonce.isBlank() || sig == null || sig.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "签名头缺失（X-ESign-Ts/Nonce/Sig）");
        }
        if (nonce.trim().length() > NONCE_MAX_LEN) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "nonce 超长（上限 64 字符）");
        }
        long tsSeconds;
        try {
            long parsed = Long.parseLong(ts.trim());
            tsSeconds = parsed > 1_000_000_000_000L ? parsed / 1000 : parsed;
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "时间戳非法");
        }
        if (Math.abs(Instant.now().getEpochSecond() - tsSeconds) > TS_WINDOW_SECONDS) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "时间戳超出防重放窗口（±300s）");
        }
        String secret = secretOf();
        if (secret == null || secret.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "电子签接入位未启用或密钥未配置，回调暂不可用");
        }
        String expected = hmacSha256Hex(secret, "ESIGN\n" + ts.trim() + "\n" + nonce.trim() + "\n" + rawBody);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                sig.trim().toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "签名验签失败");
        }
    }

    /** 密钥：ESIGN_DIRECT 开关启用才开放回调面；ESIGN_SECRET 库启用值 → env 兜底（fail-closed）。 */
    private String secretOf() {
        if (!integrationConfig.resolveSwitch("ESIGN_DIRECT", envEnabled)) {
            return null;
        }
        return integrationConfig.resolveSecret("ESIGN_SECRET", envSecret);
    }

    private boolean tryAcquire(String key) {
        long now = System.currentTimeMillis();
        long[] slot = rateWindows.compute(key, (k, cur) -> {
            if (cur == null || now - cur[0] >= 60_000) {
                return new long[]{now, 1};
            }
            cur[1]++;
            return cur;
        });
        return slot[1] <= RATE_LIMIT;
    }

    private static String clientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private JsonNode parseBody(String rawBody) {
        try {
            return JSON.readTree(rawBody);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回调体非合法 JSON");
        }
    }

    /** 文本字段提取：缺失/空白返回 null；maxLen>0 时超长 400；maxLen=0 不限长（TEXT 快照）。 */
    private static String textOf(JsonNode body, String field, int maxLen) {
        JsonNode node = body.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (maxLen > 0 && trimmed.length() > maxLen) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " 超长（上限 " + maxLen + " 字符）");
        }
        return trimmed;
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 不可用", e);
        }
    }
}
