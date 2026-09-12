package com.meiyun.ai.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiModelRepository;
import com.meiyun.ai.domain.AiProvider;
import com.meiyun.ai.domain.AiProviderRepository;
import com.meiyun.ai.security.ApiKeyCipher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class ProviderService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{2,64}$");

    private final AiProviderRepository providerRepo;
    private final AiModelRepository modelRepo;
    private final ApiKeyCipher cipher;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public ProviderService(AiProviderRepository providerRepo,
                           AiModelRepository modelRepo,
                           ApiKeyCipher cipher,
                           AuditRecorder audit) {
        this.providerRepo = providerRepo;
        this.modelRepo = modelRepo;
        this.cipher = cipher;
        this.audit = audit;
    }

    public record ProviderCmd(String providerCode, String providerName, String baseUrl,
                              String apiKey, String protocol, Boolean enabled) {
    }

    public record ProviderView(Long providerId, String providerCode, String providerName,
                               String baseUrl, String apiKeyMask, boolean hasApiKey,
                               String protocol, boolean enabled, int modelCount,
                               OffsetDateTime updatedAt) {
    }

    public record SaveResult(boolean changed, Long providerId) {
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list() {
        return providerRepo.findAll().stream().map(this::toView).toList();
    }

    @Transactional(readOnly = true)
    public ProviderView get(Long id) {
        return toView(require(id));
    }

    @Transactional
    public SaveResult create(ProviderCmd cmd, String actor) {
        validate(cmd, true);
        if (providerRepo.existsByProviderCode(cmd.providerCode().trim())) {
            throw badRequest("供应商编码已存在：" + cmd.providerCode());
        }
        AiProvider p = new AiProvider();
        p.setProviderCode(cmd.providerCode().trim());
        applyFields(p, cmd);
        p.setUpdatedBy(actor);
        AiProvider saved = providerRepo.save(p);
        audit.record("AI_PROVIDER", "PRV-" + saved.getProviderId(), actor, "CREATE",
                payload(Map.of("providerCode", saved.getProviderCode(),
                        "providerName", saved.getProviderName(),
                        "enabled", saved.getEnabled(),
                        "apiKeyProvided", cmd.apiKey() != null && !cmd.apiKey().isBlank())));
        return new SaveResult(true, saved.getProviderId());
    }

    @Transactional
    public SaveResult update(Long id, ProviderCmd cmd, String actor) {
        validate(cmd, false);
        AiProvider p = require(id);
        boolean changed = false;
        if (!str(p.getProviderName()).equals(cmd.providerName().trim())) {
            p.setProviderName(cmd.providerName().trim());
            changed = true;
        }
        if (!str(p.getBaseUrl()).equals(normalizeUrl(cmd.baseUrl()))) {
            p.setBaseUrl(normalizeUrl(cmd.baseUrl()));
            changed = true;
        }
        String protocol = cmd.protocol() == null || cmd.protocol().isBlank() ? "OPENAI" : cmd.protocol().trim();
        if (!str(p.getProtocol()).equals(protocol)) {
            p.setProtocol(protocol);
            changed = true;
        }
        boolean targetEnabled = cmd.enabled() != null && cmd.enabled();
        if (Boolean.TRUE.equals(p.getEnabled()) != targetEnabled) {
            p.setEnabled(targetEnabled);
            changed = true;
        }
        // Key 留空 = 不修改；填入新值才覆盖（禁回显明文，前端只能提交掩码或新值）
        if (cmd.apiKey() != null && !cmd.apiKey().isBlank() && !cmd.apiKey().contains("*")) {
            p.setApiKeyCipher(cipher.encrypt(cmd.apiKey().trim()));
            p.setApiKeyMask(ApiKeyCipher.mask(cmd.apiKey().trim()));
            changed = true;
        }
        if (!changed) {
            return new SaveResult(false, id);
        }
        p.setUpdatedBy(actor);
        providerRepo.save(p);
        audit.record("AI_PROVIDER", "PRV-" + id, actor, "UPDATE",
                payload(Map.of("providerCode", p.getProviderCode(),
                        "enabled", p.getEnabled(),
                        "apiKeyRotated", cmd.apiKey() != null && !cmd.apiKey().isBlank() && !cmd.apiKey().contains("*"))));
        return new SaveResult(true, id);
    }

    @Transactional
    public SaveResult delete(Long id, String actor) {
        AiProvider p = require(id);
        long refs = modelRepo.findByProviderIdOrderByModelIdDesc(id).size();
        if (refs > 0) {
            throw badRequest("该供应商下仍有 " + refs + " 个模型，请先删除或迁移模型");
        }
        providerRepo.delete(p);
        audit.record("AI_PROVIDER", "PRV-" + id, actor, "DELETE",
                payload(Map.of("providerCode", p.getProviderCode())));
        return new SaveResult(true, id);
    }

    private void validate(ProviderCmd cmd, boolean creating) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        if (creating) {
            if (cmd.providerCode() == null || !CODE_PATTERN.matcher(cmd.providerCode().trim()).matches()) {
                throw badRequest("供应商编码需为 2~64 位英文、数字、中划线或下划线");
            }
        }
        if (cmd.providerName() == null || cmd.providerName().isBlank()) {
            throw badRequest("供应商名称不能为空");
        }
        if (cmd.baseUrl() == null || cmd.baseUrl().isBlank()) {
            throw badRequest("请求地址（base_url）不能为空");
        }
        String url = normalizeUrl(cmd.baseUrl());
        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            throw badRequest("请求地址需以 http:// 或 https:// 开头");
        }
        if (creating && (cmd.apiKey() == null || cmd.apiKey().isBlank())) {
            throw badRequest("新建供应商时必须填写 API 密钥");
        }
    }

    private void applyFields(AiProvider p, ProviderCmd cmd) {
        p.setProviderName(cmd.providerName().trim());
        p.setBaseUrl(normalizeUrl(cmd.baseUrl()));
        p.setProtocol(cmd.protocol() == null || cmd.protocol().isBlank() ? "OPENAI" : cmd.protocol().trim());
        p.setEnabled(cmd.enabled() != null && cmd.enabled());
        if (cmd.apiKey() != null && !cmd.apiKey().isBlank() && !cmd.apiKey().contains("*")) {
            String key = cmd.apiKey().trim();
            p.setApiKeyCipher(cipher.encrypt(key));
            p.setApiKeyMask(ApiKeyCipher.mask(key));
        }
    }

    private ProviderView toView(AiProvider p) {
        return new ProviderView(
                p.getProviderId(),
                p.getProviderCode(),
                p.getProviderName(),
                p.getBaseUrl(),
                p.getApiKeyMask(),
                p.getApiKeyCipher() != null,
                p.getProtocol(),
                Boolean.TRUE.equals(p.getEnabled()),
                modelRepo.findByProviderIdOrderByModelIdDesc(p.getProviderId()).size(),
                p.getUpdatedAt());
    }

    private AiProvider require(Long id) {
        return providerRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商不存在（id=" + id + "）"));
    }

    private static String normalizeUrl(String url) {
        String u = url.trim();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
