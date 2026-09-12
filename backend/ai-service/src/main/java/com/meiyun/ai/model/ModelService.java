package com.meiyun.ai.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiGlobalCfg;
import com.meiyun.ai.domain.AiGlobalCfgRepository;
import com.meiyun.ai.domain.AiModel;
import com.meiyun.ai.domain.AiModelRepository;
import com.meiyun.ai.domain.AiProvider;
import com.meiyun.ai.domain.AiProviderRepository;
import com.meiyun.ai.llm.LlmClient;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ModelService {

    private static final Set<String> CAPABILITIES = Set.of("CHAT", "EMBEDDING", "VISION");

    private final AiModelRepository modelRepo;
    private final AiProviderRepository providerRepo;
    private final AiFeatureBindingRepository bindingRepo;
    private final AiGlobalCfgRepository cfgRepo;
    private final ApiKeyRef keyRef;
    private final LlmClient llm;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public ModelService(AiModelRepository modelRepo,
                        AiProviderRepository providerRepo,
                        AiFeatureBindingRepository bindingRepo,
                        AiGlobalCfgRepository cfgRepo,
                        ApiKeyRef keyRef,
                        LlmClient llm,
                        AuditRecorder audit) {
        this.modelRepo = modelRepo;
        this.providerRepo = providerRepo;
        this.bindingRepo = bindingRepo;
        this.cfgRepo = cfgRepo;
        this.keyRef = keyRef;
        this.llm = llm;
        this.audit = audit;
    }

    public record ModelCmd(Long providerId, String modelCode, String displayName, String capabilities,
                           Integer contextWindow, BigDecimal temperature, BigDecimal topP, Integer maxTokens,
                           Integer priority, Boolean enabled, BigDecimal inputPrice, BigDecimal outputPrice) {
    }

    public record ModelView(Long modelId, Long providerId, String providerCode, String providerName,
                            String modelCode, String displayName, String capabilities, Integer contextWindow,
                            BigDecimal temperature, BigDecimal topP, Integer maxTokens, Integer priority,
                            boolean enabled, String connStatus, String connMessage, OffsetDateTime connCheckedAt,
                            BigDecimal inputPrice, BigDecimal outputPrice, OffsetDateTime updatedAt) {
    }

    public record SaveResult(boolean changed, Long modelId) {
    }

    public record TestResult(boolean success, String connStatus, String message, String replySnippet,
                             Integer latencyMs, Integer totalTokens, OffsetDateTime checkedAt) {
    }

    @Transactional(readOnly = true)
    public List<ModelView> list(Long providerId) {
        List<AiModel> models = providerId == null
                ? modelRepo.findAllByOrderByPriorityAscModelIdDesc()
                : modelRepo.findByProviderIdOrderByModelIdDesc(providerId);
        Map<Long, AiProvider> providers = new LinkedHashMap<>();
        providerRepo.findAll().forEach(p -> providers.put(p.getProviderId(), p));
        return models.stream().map(m -> toView(m, providers.get(m.getProviderId()))).toList();
    }

    @Transactional(readOnly = true)
    public ModelView get(Long id) {
        AiModel m = requireModel(id);
        return toView(m, providerRepo.findById(m.getProviderId()).orElse(null));
    }

    @Transactional
    public SaveResult create(ModelCmd cmd, String actor) {
        validate(cmd);
        requireProvider(cmd.providerId());
        if (modelRepo.findByProviderIdAndModelCode(cmd.providerId(), cmd.modelCode().trim()).isPresent()) {
            throw badRequest("该供应商下模型 ID 已存在：" + cmd.modelCode());
        }
        AiModel m = new AiModel();
        m.setProviderId(cmd.providerId());
        applyFields(m, cmd);
        m.setConnStatus("UNKNOWN");
        m.setUpdatedBy(actor);
        AiModel saved = modelRepo.save(m);
        audit.record("AI_MODEL", "MDL-" + saved.getModelId(), actor, "CREATE",
                payload(Map.of("modelCode", saved.getModelCode(),
                        "displayName", saved.getDisplayName(),
                        "providerId", saved.getProviderId(),
                        "enabled", saved.getEnabled())));
        return new SaveResult(true, saved.getModelId());
    }

    @Transactional
    public SaveResult update(Long id, ModelCmd cmd, String actor) {
        validate(cmd);
        AiModel m = requireModel(id);
        boolean changed = false;
        if (!str(m.getModelCode()).equals(cmd.modelCode().trim())) {
            if (modelRepo.findByProviderIdAndModelCode(m.getProviderId(), cmd.modelCode().trim()).isPresent()) {
                throw badRequest("该供应商下模型 ID 已存在：" + cmd.modelCode());
            }
            m.setModelCode(cmd.modelCode().trim());
            changed = true;
        }
        if (!str(m.getDisplayName()).equals(cmd.displayName().trim())) {
            m.setDisplayName(cmd.displayName().trim());
            changed = true;
        }
        if (!str(m.getCapabilities()).equals(normalizeCapabilities(cmd.capabilities()))) {
            m.setCapabilities(normalizeCapabilities(cmd.capabilities()));
            changed = true;
        }
        if (!eq(m.getContextWindow(), cmd.contextWindow())) {
            m.setContextWindow(cmd.contextWindow());
            changed = true;
        }
        if (!eq(m.getTemperature(), cmd.temperature())) {
            m.setTemperature(cmd.temperature());
            changed = true;
        }
        if (!eq(m.getTopP(), cmd.topP())) {
            m.setTopP(cmd.topP());
            changed = true;
        }
        if (!eq(m.getMaxTokens(), cmd.maxTokens())) {
            m.setMaxTokens(cmd.maxTokens());
            changed = true;
        }
        if (!eq(m.getPriority(), cmd.priority() == null ? 100 : cmd.priority())) {
            m.setPriority(cmd.priority() == null ? 100 : cmd.priority());
            changed = true;
        }
        if (!eq(m.getInputPrice(), cmd.inputPrice())) {
            m.setInputPrice(cmd.inputPrice());
            changed = true;
        }
        if (!eq(m.getOutputPrice(), cmd.outputPrice())) {
            m.setOutputPrice(cmd.outputPrice());
            changed = true;
        }
        boolean targetEnabled = cmd.enabled() != null && cmd.enabled();
        if (Boolean.TRUE.equals(m.getEnabled()) != targetEnabled) {
            m.setEnabled(targetEnabled);
            changed = true;
        }
        if (!changed) {
            return new SaveResult(false, id);
        }
        m.setUpdatedBy(actor);
        modelRepo.save(m);
        audit.record("AI_MODEL", "MDL-" + id, actor, "UPDATE",
                payload(Map.of("modelCode", m.getModelCode(),
                        "enabled", m.getEnabled())));
        return new SaveResult(true, id);
    }

    @Transactional
    public SaveResult delete(Long id, String actor) {
        AiModel m = requireModel(id);
        if (bindingRepo.existsByModelId(id)) {
            throw badRequest("该模型已被 AI 功能绑定引用，请先调整功能绑定后再删除：" + m.getModelCode());
        }
        AiGlobalCfg cfg = cfgRepo.findById(1).orElse(null);
        if (cfg != null && id.equals(cfg.getDefaultModelId())) {
            throw badRequest("该模型是全局默认模型，请先在 AI 配置中更换默认模型后再删除：" + m.getModelCode());
        }
        modelRepo.delete(m);
        audit.record("AI_MODEL", "MDL-" + id, actor, "DELETE",
                payload(Map.of("modelCode", m.getModelCode())));
        return new SaveResult(true, id);
    }

    /** 真实连通性测试：拿供应商解密 Key 打一次 OpenAI 兼容 /chat/completions，结果回写模型状态。 */
    @Transactional
    public TestResult testConnection(Long id, String actor) {
        AiModel m = requireModel(id);
        AiProvider p = requireProvider(m.getProviderId());
        String apiKey = keyRef.decrypt(p);
        long start = System.currentTimeMillis();
        try {
            LlmClient.ChatResult r = llm.ping(p.getBaseUrl(), apiKey, m.getModelCode());
            long latency = System.currentTimeMillis() - start;
            m.setConnStatus("SUCCESS");
            String msg = "连通正常，模型返回 " + r.totalTokens() + " tokens";
            m.setConnMessage(msg);
            m.setConnCheckedAt(OffsetDateTime.now());
            modelRepo.save(m);
            audit.record("AI_MODEL", "MDL-" + id, actor, "TEST_CONN",
                    payload(Map.of("result", "SUCCESS", "latencyMs", latency, "totalTokens", r.totalTokens())));
            String snippet = r.content() == null ? "" :
                    (r.content().length() > 80 ? r.content().substring(0, 80) : r.content());
            return new TestResult(true, "SUCCESS", msg, snippet, (int) latency, r.totalTokens(), m.getConnCheckedAt());
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            m.setConnStatus("FAIL");
            String msg = e.getMessage() == null ? "连接失败" : e.getMessage();
            m.setConnMessage(msg.length() > 500 ? msg.substring(0, 500) : msg);
            m.setConnCheckedAt(OffsetDateTime.now());
            modelRepo.save(m);
            audit.record("AI_MODEL", "MDL-" + id, actor, "TEST_CONN",
                    payload(Map.of("result", "FAIL", "latencyMs", latency, "error", m.getConnMessage())));
            return new TestResult(false, "FAIL", m.getConnMessage(), null, (int) latency, 0, m.getConnCheckedAt());
        }
    }

    private void validate(ModelCmd cmd) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        if (cmd.providerId() == null) {
            throw badRequest("必须选择供应商");
        }
        if (cmd.modelCode() == null || cmd.modelCode().isBlank()) {
            throw badRequest("模型 ID 不能为空");
        }
        if (cmd.displayName() == null || cmd.displayName().isBlank()) {
            throw badRequest("模型展示名称不能为空");
        }
        normalizeCapabilities(cmd.capabilities());
        if (cmd.temperature() != null
                && (cmd.temperature().signum() < 0 || cmd.temperature().compareTo(new BigDecimal("2")) > 0)) {
            throw badRequest("temperature 需在 0~2 之间");
        }
        if (cmd.topP() != null
                && (cmd.topP().signum() < 0 || cmd.topP().compareTo(BigDecimal.ONE) > 0)) {
            throw badRequest("top_p 需在 0~1 之间");
        }
    }

    private void applyFields(AiModel m, ModelCmd cmd) {
        m.setModelCode(cmd.modelCode().trim());
        m.setDisplayName(cmd.displayName().trim());
        m.setCapabilities(normalizeCapabilities(cmd.capabilities()));
        m.setContextWindow(cmd.contextWindow());
        m.setTemperature(cmd.temperature());
        m.setTopP(cmd.topP());
        m.setMaxTokens(cmd.maxTokens());
        m.setPriority(cmd.priority() == null ? 100 : cmd.priority());
        m.setEnabled(cmd.enabled() != null && cmd.enabled());
        m.setInputPrice(cmd.inputPrice());
        m.setOutputPrice(cmd.outputPrice());
    }

    private String normalizeCapabilities(String raw) {
        if (raw == null || raw.isBlank()) {
            return "CHAT";
        }
        List<String> parts = Arrays.stream(raw.split("[,，]"))
                .map(String::trim).filter(s -> !s.isEmpty()).map(String::toUpperCase).distinct().toList();
        if (parts.isEmpty()) {
            return "CHAT";
        }
        for (String c : parts) {
            if (!CAPABILITIES.contains(c)) {
                throw badRequest("能力仅支持 CHAT/EMBEDDING/VISION，非法值：" + c);
            }
        }
        return String.join(",", parts);
    }

    private ModelView toView(AiModel m, AiProvider p) {
        return new ModelView(
                m.getModelId(),
                m.getProviderId(),
                p == null ? null : p.getProviderCode(),
                p == null ? null : p.getProviderName(),
                m.getModelCode(),
                m.getDisplayName(),
                m.getCapabilities(),
                m.getContextWindow(),
                m.getTemperature(),
                m.getTopP(),
                m.getMaxTokens(),
                m.getPriority(),
                Boolean.TRUE.equals(m.getEnabled()),
                m.getConnStatus(),
                m.getConnMessage(),
                m.getConnCheckedAt(),
                m.getInputPrice(),
                m.getOutputPrice(),
                m.getUpdatedAt());
    }

    private AiModel requireModel(Long id) {
        return modelRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "模型不存在（id=" + id + "）"));
    }

    private AiProvider requireProvider(Long id) {
        return providerRepo.findById(id)
                .orElseThrow(() -> badRequest("供应商不存在（id=" + id + "）"));
    }

    private static boolean eq(Object a, Object b) {
        return a == null ? b == null : a.equals(b);
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
