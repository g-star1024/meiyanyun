package com.meiyun.ai.feature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.domain.AiFeatureBinding;
import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiFeatureRole;
import com.meiyun.ai.domain.AiFeatureRoleRepository;
import com.meiyun.ai.domain.AiInvokeLog;
import com.meiyun.ai.domain.AiInvokeLogRepository;
import com.meiyun.ai.domain.AiModel;
import com.meiyun.ai.domain.AiModelRepository;
import com.meiyun.ai.domain.AiProvider;
import com.meiyun.ai.domain.AiProviderRepository;
import com.meiyun.ai.llm.LlmClient;
import com.meiyun.ai.model.ApiKeyRef;
import com.meiyun.ai.quota.QuotaService;
import com.meiyun.ai.security.SensitiveWordService;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 功能真实调用闭环：登录 + 角色灰度矩阵 + 门店灰度 + 功能/模型/供应商启用校验
 * → 按绑定模板组装提示词 → LlmClient.chat 真实出站 → 成功/失败均沉淀 ai_invoke_log（含 token、耗时、费用分）。
 * 多供应商 failover：功能绑定可配置备用模型链，主模型 5xx/超时/拒绝即按链自动切换，每段日志记实际 provider/model。
 * 鉴权独立于管理端 aiAdmin:*：业务用户能否调用由 ai_feature_role 矩阵决定。
 */
@Service
public class FeatureInvokeService {

    private static final int INPUT_MAX = 4000;
    private static final int SNIPPET_MAX = 500;

    private final AiFeatureBindingRepository bindingRepo;
    private final AiFeatureRoleRepository roleRepo;
    private final AiModelRepository modelRepo;
    private final AiProviderRepository providerRepo;
    private final AiInvokeLogRepository logRepo;
    private final ApiKeyRef keyRef;
    private final LlmClient llm;
    private final SensitiveWordService sensitiveWordService;
    private final QuotaService quotaService;
    private final ObjectMapper json = new ObjectMapper();

    public FeatureInvokeService(AiFeatureBindingRepository bindingRepo,
                                AiFeatureRoleRepository roleRepo,
                                AiModelRepository modelRepo,
                                AiProviderRepository providerRepo,
                                AiInvokeLogRepository logRepo,
                                ApiKeyRef keyRef,
                                LlmClient llm,
                                SensitiveWordService sensitiveWordService,
                                QuotaService quotaService) {
        this.bindingRepo = bindingRepo;
        this.roleRepo = roleRepo;
        this.modelRepo = modelRepo;
        this.providerRepo = providerRepo;
        this.logRepo = logRepo;
        this.keyRef = keyRef;
        this.llm = llm;
        this.sensitiveWordService = sensitiveWordService;
        this.quotaService = quotaService;
    }

    public record InvokeCmd(String input, String storeCode) {
    }

    public record InvokeView(boolean success, String featureCode, String featureName,
                             String providerCode, String modelCode, String content,
                             Integer promptTokens, Integer completionTokens, Integer totalTokens,
                             Long latencyMs, Long costFen, String errorCode, Long logId) {
    }

    // 刻意不加方法级事务：真实出站失败时需保证失败日志独立提交，
    // 不随随后抛出的 502 响应异常一起回滚（前置校验失败不落日志，直接抛出即可）。
    public InvokeView invoke(String featureCode, InvokeCmd cmd) {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        if (cmd == null || cmd.input() == null || cmd.input().isBlank()) {
            throw badRequest("调用内容 input 不能为空");
        }
        String input = cmd.input().trim();
        if (input.length() > INPUT_MAX) {
            throw badRequest("调用内容过长，单次不超过 " + INPUT_MAX + " 字");
        }

        AiFeatureBinding binding = bindingRepo.findByFeatureCode(featureCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "AI 功能不存在（" + featureCode + "）"));
        String featureName = FeatureCatalog.nameOf(featureCode);

        if (!user.isSuper() && !roleAllowed(user, featureCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "当前角色未开通「" + featureName + "」灰度权限，请联系管理员在灰度矩阵中放开");
        }
        if (!Boolean.TRUE.equals(binding.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "功能「" + featureName + "」尚未启用，请先在功能绑定中启用");
        }
        if (binding.getModelId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "功能「" + featureName + "」尚未绑定模型，请先完成功能绑定");
        }
        String reqStore = cmd.storeCode() == null || cmd.storeCode().isBlank()
                ? user.storeCode() : cmd.storeCode().trim();
        if (reqStore != null && !storeInScope(binding, reqStore)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "门店 " + reqStore + " 不在功能「" + featureName + "」的灰度范围内");
        }

        sensitiveWordService.screen(input, featureCode, user, reqStore);

        String prompt = buildPrompt(binding.getPromptTemplate(), input);
        List<Map<String, String>> messages = new java.util.ArrayList<>();
        if (binding.getPromptTemplate() != null && !binding.getPromptTemplate().isBlank()) {
            messages.add(Map.of("role", "system", "content", binding.getPromptTemplate().trim()));
        }
        messages.add(Map.of("role", "user", "content", input));

        // 多供应商 failover：主模型 + 备用模型链按序尝试；任一段 5xx/超时/拒绝即切下一段，
        // 每段成功/失败均落 ai_invoke_log 记实际 provider/model，全链失败抛 502 并附链轨迹。
        List<Long> chain = new java.util.ArrayList<>();
        chain.add(binding.getModelId());
        for (Long bid : FeatureService.parseBackupChain(binding.getBackupModelIds())) {
            if (!bid.equals(binding.getModelId()) && !chain.contains(bid)) {
                chain.add(bid);
            }
        }
        List<String> trail = new java.util.ArrayList<>();
        long start = System.currentTimeMillis();
        for (Long modelId : chain) {
            Target target;
            try {
                target = resolveTarget(modelId);
            } catch (ResponseStatusException loadEx) {
                if (chain.size() == 1) {
                    throw loadEx;
                }
                String reason = loadEx.getReason() == null ? "模型不可用" : loadEx.getReason();
                trail.add("模型#" + modelId + " " + reason);
                writeFailLog(user, reqStore, featureCode, prompt, null, "#" + modelId, start,
                        "模型装载失败：" + reason);
                continue;
            }
            quotaService.check(featureCode, target.model().getModelCode());
            Double temperature = overrideDecimal(binding.getParamOverrides(), "temperature", target.model().getTemperature());
            Integer maxTokens = overrideInt(binding.getParamOverrides(), "maxTokens", target.model().getMaxTokens());
            try {
                LlmClient.ChatResult r = llm.chat(target.provider().getBaseUrl(), target.apiKey(), target.model().getModelCode(),
                        messages, temperature, maxTokens);
                long latency = System.currentTimeMillis() - start;
                long costFen = costFen(target.model(), r.promptTokens(), r.completionTokens());
                AiInvokeLog log = new AiInvokeLog();
                fillActor(log, user, reqStore);
                log.setFeatureCode(featureCode);
                log.setProviderCode(target.provider().getProviderCode());
                log.setModelCode(target.model().getModelCode());
                log.setPromptSnippet(snippet(prompt));
                log.setOutputSnippet(snippet(r.content()));
                log.setPromptTokens(r.promptTokens());
                log.setCompletionTokens(r.completionTokens());
                log.setTotalTokens(r.totalTokens());
                log.setLatencyMs(latency);
                log.setSuccess(true);
                log.setCostFen(costFen);
                logRepo.save(log);
                return new InvokeView(true, featureCode, featureName,
                        target.provider().getProviderCode(), target.model().getModelCode(), r.content(),
                        r.promptTokens(), r.completionTokens(), r.totalTokens(),
                        latency, costFen, null, log.getLogId());
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "调用失败" : e.getMessage();
                trail.add(target.model().getModelCode() + " " + msg);
                writeFailLog(user, reqStore, featureCode, prompt,
                        target.provider().getProviderCode(), target.model().getModelCode(), start, msg);
            }
        }
        if (chain.size() == 1) {
            String only = trail.isEmpty() ? "调用失败" : trail.get(0);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "大模型调用失败：" + only);
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "主模型及备用链全部调用失败：" + String.join("；", trail));
    }

    /** 装载单个调用目标：模型存在/启用/CHAT 能力 + 供应商存在/启用 + 密钥解引用。 */
    private Target resolveTarget(Long modelId) {
        AiModel model = modelRepo.findById(modelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "绑定模型不存在（id=" + modelId + "），请重新绑定"));
        if (!Boolean.TRUE.equals(model.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "绑定模型「" + model.getDisplayName() + "」已停用，请更换模型或启用后再试");
        }
        if (!hasChat(model)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "绑定模型「" + model.getDisplayName() + "」不具备对话（CHAT）能力");
        }
        AiProvider provider = providerRepo.findById(model.getProviderId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                        "模型所属供应商不存在，请重新绑定"));
        if (!Boolean.TRUE.equals(provider.getEnabled())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "供应商「" + provider.getProviderName() + "」已停用");
        }
        return new Target(model, provider, keyRef.decrypt(provider));
    }

    private record Target(AiModel model, AiProvider provider, String apiKey) {
    }

    private void writeFailLog(LoginUser user, String reqStore, String featureCode, String prompt,
                              String providerCode, String modelCode, long start, String msg) {
        long latency = System.currentTimeMillis() - start;
        AiInvokeLog log = new AiInvokeLog();
        fillActor(log, user, reqStore);
        log.setFeatureCode(featureCode);
        log.setProviderCode(providerCode);
        log.setModelCode(modelCode);
        log.setPromptSnippet(snippet(prompt));
        log.setLatencyMs(latency);
        log.setSuccess(false);
        log.setErrorCode(msg.length() > 512 ? msg.substring(0, 512) : msg);
        log.setCostFen(0L);
        logRepo.save(log);
    }

    private boolean roleAllowed(LoginUser user, String featureCode) {
        for (AiFeatureRole r : roleRepo.findByFeatureCode(featureCode)) {
            if (Boolean.TRUE.equals(r.getEnabled()) && user.roles() != null
                    && user.roles().contains(r.getRoleCode())) {
                return true;
            }
        }
        return false;
    }

    private boolean storeInScope(AiFeatureBinding binding, String storeCode) {
        if (!"SPECIFIED".equalsIgnoreCase(binding.getStoreScope())) {
            return true;
        }
        if (binding.getStoreCodes() == null || binding.getStoreCodes().isBlank()) {
            return false;
        }
        return Arrays.stream(binding.getStoreCodes().split("[,，\\s]+"))
                .map(String::trim).filter(s -> !s.isEmpty())
                .anyMatch(storeCode::equals);
    }

    private boolean hasChat(AiModel model) {
        return Arrays.stream(model.getCapabilities().split("[,，]"))
                .map(String::trim).map(String::toUpperCase)
                .anyMatch("CHAT"::equals);
    }

    private String buildPrompt(String template, String input) {
        if (template == null || template.isBlank()) {
            return input;
        }
        String t = template.trim();
        if (t.contains("{input}")) {
            return t.replace("{input}", input);
        }
        return t + "\n\n" + input;
    }

    private Double overrideDecimal(String overrides, String key, BigDecimal fallback) {
        JsonNode node = overrideNode(overrides, key);
        if (node != null && node.isNumber()) {
            return node.asDouble();
        }
        return fallback == null ? null : fallback.doubleValue();
    }

    private Integer overrideInt(String overrides, String key, Integer fallback) {
        JsonNode node = overrideNode(overrides, key);
        if (node != null && node.isNumber()) {
            return node.asInt();
        }
        return fallback;
    }

    private JsonNode overrideNode(String overrides, String key) {
        if (overrides == null || overrides.isBlank()) {
            return null;
        }
        try {
            JsonNode root = json.readTree(overrides);
            JsonNode node = root.path(key);
            return node.isMissingNode() ? null : node;
        } catch (Exception e) {
            throw badRequest("功能参数 paramOverrides 不是合法 JSON，请检查功能绑定配置");
        }
    }

    /** 价格口径：input_price/output_price 为「元/百万 token」；费用折算到「分」，四舍五入。 */
    private long costFen(AiModel m, int promptTokens, int completionTokens) {
        BigDecimal yuan = BigDecimal.ZERO;
        if (m.getInputPrice() != null) {
            yuan = yuan.add(m.getInputPrice().multiply(BigDecimal.valueOf(promptTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP));
        }
        if (m.getOutputPrice() != null) {
            yuan = yuan.add(m.getOutputPrice().multiply(BigDecimal.valueOf(completionTokens))
                    .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP));
        }
        return yuan.multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP).longValue();
    }

    private void fillActor(AiInvokeLog log, LoginUser user, String storeCode) {
        log.setStaffId(user.staffId());
        log.setStaffName(user.staffName());
        log.setStoreCode(storeCode);
    }

    private String snippet(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > SNIPPET_MAX ? s.substring(0, SNIPPET_MAX) : s;
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }
}
