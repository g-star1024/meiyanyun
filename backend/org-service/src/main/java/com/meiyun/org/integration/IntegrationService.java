package com.meiyun.org.integration;

import com.meiyun.org.audit.AuditRecorder;
import com.meiyun.org.integration.security.IntegrationSecretCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 外部依赖配置窗口领域服务（P5-B57 卡2）：
 * 管理面视图装配（明文/密文绝不下发，只给 hasSecret+掩码）、upsert（密钥留空/含掩码不覆盖）、
 * 连接测试（URL 探测 / SECRET 格式校验 / SWITCH 无测试）、服务间快照装配（解密，仅内部端点调用）。
 *
 * <p>安全口径：审计只记 code/kind/enabled/字段变更标志，绝不记明文密钥与完整 URL（含查询串）。
 */
@Service
public class IntegrationService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationService.class);

    /** HMAC 共享密钥字符集：可见 ASCII，长度 ≥16（不做假握手，真实联调走 marketing /sample）。 */
    private static final Pattern SECRET_PATTERN = Pattern.compile("^[!-~]{16,}$");

    private final ExternalIntegrationRepository repo;
    private final IntegrationSecretCipher cipher;
    private final RestTemplate restTemplate;
    private final AuditRecorder audit;

    public IntegrationService(ExternalIntegrationRepository repo,
                              IntegrationSecretCipher cipher,
                              RestTemplate restTemplate,
                              AuditRecorder audit) {
        this.repo = repo;
        this.cipher = cipher;
        this.restTemplate = restTemplate;
        this.audit = audit;
    }

    // ==================== 管理面视图 ====================

    /** 目录全量视图：无密文无明文，密钥只给 hasSecret+掩码。 */
    @Transactional(readOnly = true)
    public List<IntegrationView> listViews() {
        return repo.findAllByOrderByCategoryAscIdAsc().stream()
                .map(this::toView)
                .toList();
    }

    private IntegrationView toView(ExternalIntegration e) {
        boolean hasSecret = e.getSecretCipher() != null && !e.getSecretCipher().isBlank();
        return new IntegrationView(
                e.getIntegrationCode(), e.getCategory(), e.getIntegrationName(), e.getValueKind(),
                e.getBaseUrl(), hasSecret, e.getSecretMask(), e.getBoolValue(), e.isEnabled(),
                e.getRemark(), e.getLastTestAt(), e.getLastTestOk(), e.getLastTestMsg());
    }

    // ==================== upsert ====================

    /**
     * 目录行 upsert（不新建/删除接入点）：
     * URL 类校验 http(s) 且非 https 须二次确认；SECRET 留空/含掩码不改；SWITCH 只认 boolValue+enabled。
     */
    @Transactional
    public IntegrationView upsert(String code, UpsertRequest req, String actor) {
        IntegrationCatalog catalog = IntegrationCatalog.fromCode(code)
                .orElseThrow(() -> new IllegalArgumentException("非法接入点编码: " + code));
        ExternalIntegration e = repo.findByIntegrationCode(catalog.name())
                .orElseThrow(() -> new IllegalStateException("配置目录未初始化（V34 迁移未执行）: " + code));
        if (!catalog.getValueKind().equals(e.getValueKind())) {
            throw new IllegalArgumentException("接入点值类型不匹配: " + code);
        }

        boolean urlChanged = false;
        boolean secretChanged = false;
        switch (catalog.getValueKind()) {
            case "URL" -> {
                String url = req.baseUrl() == null ? "" : req.baseUrl().trim();
                if (url.isEmpty()) {
                    e.setBaseUrl(null);
                    urlChanged = true;
                } else {
                    assertHttpUrl(url, Boolean.TRUE.equals(req.insecureHttpConfirmed()));
                    e.setBaseUrl(url);
                    urlChanged = true;
                }
                if (Boolean.TRUE.equals(req.enabled()) && e.getBaseUrl() == null) {
                    throw new IllegalArgumentException("启用前必须填写网关地址: " + code);
                }
            }
            case "SECRET" -> {
                String secret = req.secret();
                if (secret != null && !secret.isBlank() && !secret.contains("****")) {
                    String trimmed = secret.trim();
                    e.setSecretCipher(cipher.encrypt(trimmed));
                    e.setSecretMask(IntegrationSecretCipher.mask(trimmed));
                    secretChanged = true;
                }
                if (Boolean.TRUE.equals(req.enabled())
                        && (e.getSecretCipher() == null || e.getSecretCipher().isBlank())) {
                    throw new IllegalArgumentException("启用前必须录入签名密钥: " + code);
                }
            }
            case "SWITCH" -> {
                if (req.boolValue() != null) {
                    e.setBoolValue(req.boolValue());
                }
            }
            default -> throw new IllegalArgumentException("不支持的值类型: " + catalog.getValueKind());
        }

        if (req.enabled() != null) {
            e.setEnabled(req.enabled());
        }
        OffsetDateTime now = OffsetDateTime.now();
        e.setUpdatedAt(now);
        e.setUpdatedBy(actor);
        ExternalIntegration saved = repo.save(e);

        if ("SWITCH".equals(catalog.getValueKind()) && saved.isEnabled() && Boolean.TRUE.equals(saved.getBoolValue())) {
            log.warn("【安全告警】广告回传免签开关被开启 code={} actor={}：仅限联调环境，生产开启视同安全事故", code, actor);
        }

        String payload = "{\"code\":\"" + code + "\",\"kind\":\"" + catalog.getValueKind()
                + "\",\"enabled\":" + saved.isEnabled()
                + ",\"urlChanged\":" + urlChanged
                + ",\"secretChanged\":" + secretChanged + "}";
        audit.record("INTEGRATION", code, actor, "integration.update", payload);
        return toView(saved);
    }

    private void assertHttpUrl(String url, boolean insecureConfirmed) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception ex) {
            throw new IllegalArgumentException("网关地址不是合法 URL: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("网关地址必须是合法的 http(s) URL");
        }
        if ("http".equalsIgnoreCase(scheme) && !insecureConfirmed) {
            throw new IllegalArgumentException("明文 HTTP 网关仅限内网联调，须勾选不安全确认后才能保存");
        }
    }

    // ==================== 测试连接 ====================

    /** 分类测试：URL 探测 / SECRET 格式校验（不做假握手）/ SWITCH 无测试；回写 last_test_* 并审计。 */
    @Transactional
    public TestResult test(String code, String actor) {
        IntegrationCatalog catalog = IntegrationCatalog.fromCode(code)
                .orElseThrow(() -> new IllegalArgumentException("非法接入点编码: " + code));
        ExternalIntegration e = repo.findByIntegrationCode(catalog.name())
                .orElseThrow(() -> new IllegalStateException("配置目录未初始化（V34 迁移未执行）: " + code));

        TestResult result = switch (catalog.getValueKind()) {
            case "URL" -> probeUrl(e);
            case "SECRET" -> validateSecret(e);
            default -> new TestResult(true,
                    "开关类配置无连接测试；当前状态：" + (e.isEnabled() && Boolean.TRUE.equals(e.getBoolValue())
                            ? "已开启（请确认仅在联调环境）" : "已关闭"));
        };

        e.setLastTestAt(OffsetDateTime.now());
        e.setLastTestOk(result.ok());
        e.setLastTestMsg(result.message().length() > 250 ? result.message().substring(0, 250) : result.message());
        repo.save(e);
        audit.record("INTEGRATION", code, actor, "integration.test",
                "{\"code\":\"" + code + "\",\"ok\":" + result.ok() + "}");
        return result;
    }

    private TestResult probeUrl(ExternalIntegration e) {
        String url = e.getBaseUrl();
        if (url == null || url.isBlank()) {
            return new TestResult(false, "尚未配置网关地址");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("test", true);
            body.put("scene", "integration-probe");
            body.put("ts", System.currentTimeMillis());
            restTemplate.postForEntity(url, new HttpEntity<>(body, headers), String.class);
            return new TestResult(true, "网关探测成功（HTTP 2xx）");
        } catch (HttpClientErrorException ex) {
            return new TestResult(false, "网关确定性拒绝(" + ex.getStatusCode().value() + ")，对应投递将判 DEAD 不重试");
        } catch (HttpServerErrorException ex) {
            return new TestResult(false, "网关服务异常(" + ex.getStatusCode().value() + ")，请稍后重试");
        } catch (Exception ex) {
            return new TestResult(false, "网关不可达/超时：" + ex.getMessage());
        }
    }

    private TestResult validateSecret(ExternalIntegration e) {
        String plain;
        try {
            plain = cipher.decrypt(e.getSecretCipher());
        } catch (Exception ex) {
            return new TestResult(false, "密钥解密失败（主密钥可能已变更），请重新录入");
        }
        if (plain == null || plain.isBlank()) {
            return new TestResult(false, "尚未录入签名密钥");
        }
        if (!SECRET_PATTERN.matcher(plain).matches()) {
            return new TestResult(false, "密钥格式不合规：须为可见字符且长度不少于 16 位");
        }
        return new TestResult(true, "密钥格式已校验；请用渠道联调样例（/sample）发起真实回调完成端到端验证");
    }

    // ==================== 服务间快照 ====================

    /**
     * 内部快照（含明文 secret，仅 InternalIntegrationController 系统身份调用）：
     * 全量 7 行（含禁用项，消费方自行按 enabled 决策）；单行解密失败不影响其他行。
     */
    @Transactional(readOnly = true)
    public List<SnapshotItem> snapshot() {
        return repo.findAllByOrderByCategoryAscIdAsc().stream()
                .map(this::toSnapshot)
                .toList();
    }

    private SnapshotItem toSnapshot(ExternalIntegration e) {
        String secret = null;
        if ("SECRET".equals(e.getValueKind()) && e.getSecretCipher() != null && !e.getSecretCipher().isBlank()) {
            try {
                secret = cipher.decrypt(e.getSecretCipher());
            } catch (Exception ex) {
                log.warn("集成密钥快照解密失败，该行按无密钥下发 code={}: {}", e.getIntegrationCode(), ex.getMessage());
            }
        }
        return new SnapshotItem(e.getIntegrationCode(), e.isEnabled(), e.getBaseUrl(),
                secret, e.getBoolValue(), e.getUpdatedAt());
    }

    // ==================== 读模型/入参 ====================

    /** 管理面列表视图：密钥只回 hasSecret+掩码，无密文无明文。 */
    public record IntegrationView(String code, String category, String name, String valueKind,
                                  String baseUrl, boolean hasSecret, String secretMask,
                                  Boolean boolValue, boolean enabled, String remark,
                                  OffsetDateTime lastTestAt, Boolean lastTestOk, String lastTestMsg) {
    }

    /** upsert 入参：SECRET 留空/含 **** 表示不修改原密钥；非 https 的 URL 须 insecureHttpConfirmed=true。 */
    public record UpsertRequest(String baseUrl, String secret, Boolean boolValue,
                                Boolean enabled, Boolean insecureHttpConfirmed) {
    }

    public record TestResult(boolean ok, String message) {
    }

    /** 服务间快照读模型（含明文 secret，不出内网）。 */
    public record SnapshotItem(String code, boolean enabled, String baseUrl,
                               String secret, Boolean boolValue, OffsetDateTime updatedAt) {
    }
}
