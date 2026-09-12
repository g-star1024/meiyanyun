package com.meiyun.ai.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiFeatureBinding;
import com.meiyun.ai.domain.AiFeatureBindingRepository;
import com.meiyun.ai.domain.AiFeatureRole;
import com.meiyun.ai.domain.AiFeatureRole.PK;
import com.meiyun.ai.domain.AiFeatureRoleRepository;
import com.meiyun.ai.domain.AiModelRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class FeatureService {

    private final AiFeatureBindingRepository bindingRepo;
    private final AiFeatureRoleRepository roleRepo;
    private final AiModelRepository modelRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public FeatureService(AiFeatureBindingRepository bindingRepo,
                          AiFeatureRoleRepository roleRepo,
                          AiModelRepository modelRepo,
                          AuditRecorder audit) {
        this.bindingRepo = bindingRepo;
        this.roleRepo = roleRepo;
        this.modelRepo = modelRepo;
        this.audit = audit;
    }

    public record BindingCmd(Long modelId, String storeScope, String storeCodes,
                             String promptTemplate, String paramOverrides,
                             Boolean enabled, Boolean requireApproval) {
    }

    public record BindingView(String featureCode, String featureName, Long modelId,
                              String modelCode, String modelDisplayName,
                              String storeScope, String storeCodes,
                              String promptTemplate, String paramOverrides,
                              boolean enabled, boolean requireApproval,
                              String updatedBy, java.time.OffsetDateTime updatedAt,
                              Map<String, Boolean> roles) {
    }

    public record SaveResult(boolean changed, String featureCode) {
    }

    @PostConstruct
    @Transactional
    public void seedCatalog() {
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            if (bindingRepo.findByFeatureCode(f.code()).isEmpty()) {
                AiFeatureBinding b = new AiFeatureBinding();
                b.setFeatureCode(f.code());
                b.setFeatureName(f.name());
                b.setEnabled(false);
                bindingRepo.save(b);
            }
        }
        for (FeatureCatalog.Feature f : FeatureCatalog.FEATURES) {
            Map<String, Boolean> current = roleMap(f.code());
            List<AiFeatureRole> toSave = new ArrayList<>();
            for (String role : FeatureCatalog.MATRIX_ROLES) {
                if (!current.containsKey(role)) {
                    boolean on = "SUPER_ADMIN".equals(role);
                    toSave.add(new AiFeatureRole(f.code(), role, on, null));
                }
            }
            if (!toSave.isEmpty()) {
                roleRepo.saveAll(toSave);
            }
        }
    }

    @Transactional(readOnly = true)
    public List<BindingView> list() {
        Map<Long, String> modelCodes = new LinkedHashMap<>();
        Map<Long, String> modelNames = new LinkedHashMap<>();
        modelRepo.findAll().forEach(m -> {
            modelCodes.put(m.getModelId(), m.getModelCode());
            modelNames.put(m.getModelId(), m.getDisplayName());
        });
        return bindingRepo.findAllByOrderByBindingIdAsc().stream()
                .map(b -> toView(b, modelCodes, modelNames))
                .toList();
    }

    @Transactional(readOnly = true)
    public BindingView get(String featureCode) {
        AiFeatureBinding b = requireFeature(featureCode);
        return toView(b, Map.of(), Map.of());
    }

    /** 功能绑定 upsert：六功能由目录播种，仅可配置不可新增/删除。 */
    @Transactional
    public SaveResult saveBinding(String featureCode, BindingCmd cmd, String actor) {
        AiFeatureBinding b = requireFeature(featureCode);
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        if (cmd.modelId() != null && modelRepo.findById(cmd.modelId()).isEmpty()) {
            throw badRequest("所选模型不存在（id=" + cmd.modelId() + "）");
        }
        String scope = cmd.storeScope() == null || cmd.storeScope().isBlank()
                ? "ALL" : cmd.storeScope().trim().toUpperCase();
        if (!"ALL".equals(scope) && !"SPECIFIED".equals(scope)) {
            throw badRequest("门店范围仅支持 ALL 或 SPECIFIED");
        }
        boolean changed = false;
        if (!Objects.equals(b.getModelId(), cmd.modelId())) {
            b.setModelId(cmd.modelId());
            changed = true;
        }
        if (!str(b.getStoreScope()).equals(scope)) {
            b.setStoreScope(scope);
            changed = true;
        }
        if (!str(b.getStoreCodes()).equals(str(cmd.storeCodes()))) {
            b.setStoreCodes(blankToNull(cmd.storeCodes()));
            changed = true;
        }
        if (!str(b.getPromptTemplate()).equals(str(cmd.promptTemplate()))) {
            b.setPromptTemplate(blankToNull(cmd.promptTemplate()));
            changed = true;
        }
        if (!str(b.getParamOverrides()).equals(str(cmd.paramOverrides()))) {
            b.setParamOverrides(blankToNull(cmd.paramOverrides()));
            changed = true;
        }
        boolean enabled = Boolean.TRUE.equals(cmd.enabled());
        if (Boolean.TRUE.equals(b.getEnabled()) != enabled) {
            b.setEnabled(enabled);
            changed = true;
        }
        boolean requireApproval = Boolean.TRUE.equals(cmd.requireApproval());
        if (Boolean.TRUE.equals(b.getRequireApproval()) != requireApproval) {
            b.setRequireApproval(requireApproval);
            changed = true;
        }
        if (!changed) {
            return new SaveResult(false, featureCode);
        }
        b.setUpdatedBy(actor);
        bindingRepo.save(b);
        audit.record("AI_FEATURE", "FEAT-" + featureCode, actor, "BIND",
                payload(Map.of("featureCode", featureCode,
                        "modelId", b.getModelId() == null ? "" : b.getModelId(),
                        "enabled", enabled,
                        "requireApproval", requireApproval,
                        "storeScope", scope)));
        return new SaveResult(true, featureCode);
    }

    /** 功能×角色灰度矩阵整表保存（替换该功能五个角色行）。注意：非 T1 RBAC，仅 AI 灰度。 */
    @Transactional
    public SaveResult saveRoles(String featureCode, Map<String, Boolean> roles, String actor) {
        requireFeature(featureCode);
        if (roles == null || roles.isEmpty()) {
            throw badRequest("角色矩阵不能为空");
        }
        int enabledCount = 0;
        List<AiFeatureRole> toSave = new ArrayList<>();
        for (String role : FeatureCatalog.MATRIX_ROLES) {
            boolean on = Boolean.TRUE.equals(roles.get(role));
            if (on) {
                enabledCount++;
            }
            toSave.add(new AiFeatureRole(featureCode, role, on, null));
        }
        roleRepo.saveAll(toSave);
        audit.record("AI_FEATURE", "FEAT-" + featureCode, actor, "ROLE_MATRIX",
                payload(Map.of("featureCode", featureCode, "enabledRoles", enabledCount)));
        return new SaveResult(true, featureCode);
    }

    private Map<String, Boolean> roleMap(String featureCode) {
        Map<String, Boolean> map = new LinkedHashMap<>();
        for (String role : FeatureCatalog.MATRIX_ROLES) {
            map.put(role, false);
        }
        roleRepo.findByFeatureCode(featureCode)
                .forEach(r -> map.put(r.getRoleCode(), Boolean.TRUE.equals(r.getEnabled())));
        return map;
    }

    private BindingView toView(AiFeatureBinding b, Map<Long, String> modelCodes, Map<Long, String> modelNames) {
        Long mid = b.getModelId();
        return new BindingView(
                b.getFeatureCode(),
                b.getFeatureName(),
                mid,
                mid == null ? null : modelCodes.getOrDefault(mid, null),
                mid == null ? null : modelNames.getOrDefault(mid, null),
                b.getStoreScope(),
                b.getStoreCodes(),
                b.getPromptTemplate(),
                b.getParamOverrides(),
                Boolean.TRUE.equals(b.getEnabled()),
                Boolean.TRUE.equals(b.getRequireApproval()),
                b.getUpdatedBy(),
                b.getUpdatedAt(),
                roleMap(b.getFeatureCode()));
    }

    private AiFeatureBinding requireFeature(String code) {
        return bindingRepo.findByFeatureCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "AI 功能不存在（" + code + "）"));
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
