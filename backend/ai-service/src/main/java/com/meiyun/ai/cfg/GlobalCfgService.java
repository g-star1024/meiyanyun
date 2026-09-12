package com.meiyun.ai.cfg;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiGlobalCfg;
import com.meiyun.ai.domain.AiGlobalCfgRepository;
import com.meiyun.ai.domain.AiModel;
import com.meiyun.ai.domain.AiModelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
public class GlobalCfgService {

    private static final List<Integer> RETENTION_CHOICES = List.of(6, 12, 24, 36);

    private final AiGlobalCfgRepository cfgRepo;
    private final AiModelRepository modelRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public GlobalCfgService(AiGlobalCfgRepository cfgRepo,
                            AiModelRepository modelRepo,
                            AuditRecorder audit) {
        this.cfgRepo = cfgRepo;
        this.modelRepo = modelRepo;
        this.audit = audit;
    }

    public record ModelOption(Long modelId, String label) {
    }

    public record CfgView(Long defaultModelId, int grayScale, int retentionMonths,
                          boolean sensitiveCheck, boolean explainability, boolean autoAudit,
                          String updatedBy, java.time.OffsetDateTime updatedAt,
                          List<ModelOption> modelOptions) {
    }

    public record CfgCmd(Long defaultModelId, Integer grayScale, Integer retentionMonths,
                         Boolean sensitiveCheck, Boolean explainability, Boolean autoAudit) {
    }

    public record SaveResult(boolean changed) {
    }

    @Transactional(readOnly = true)
    public CfgView get() {
        AiGlobalCfg cfg = requireCfg();
        return toView(cfg, modelOptions());
    }

    @Transactional
    public SaveResult save(CfgCmd cmd, String actor) {
        if (cmd == null) {
            throw badRequest("请求体不能为空");
        }
        AiGlobalCfg cfg = requireCfg();
        if (cmd.defaultModelId() != null && modelRepo.findById(cmd.defaultModelId()).isEmpty()) {
            throw badRequest("默认模型不存在（id=" + cmd.defaultModelId() + "）");
        }
        int gray = cmd.grayScale() == null ? cfg.getGrayScale() : cmd.grayScale();
        if (gray < 0 || gray > 100) {
            throw badRequest("灰度比例需在 0~100 之间");
        }
        int retention = cmd.retentionMonths() == null ? cfg.getRetentionMonths() : cmd.retentionMonths();
        if (!RETENTION_CHOICES.contains(retention)) {
            throw badRequest("训练数据保留期仅支持 6/12/24/36 个月");
        }
        boolean changed = false;
        if (!Objects.equals(cfg.getDefaultModelId(), cmd.defaultModelId())) {
            cfg.setDefaultModelId(cmd.defaultModelId());
            changed = true;
        }
        if (cfg.getGrayScale() != gray) {
            cfg.setGrayScale(gray);
            changed = true;
        }
        if (cfg.getRetentionMonths() != retention) {
            cfg.setRetentionMonths(retention);
            changed = true;
        }
        if (cmd.sensitiveCheck() != null
                && Boolean.TRUE.equals(cfg.getSensitiveCheck()) != cmd.sensitiveCheck()) {
            cfg.setSensitiveCheck(cmd.sensitiveCheck());
            changed = true;
        }
        if (cmd.explainability() != null
                && Boolean.TRUE.equals(cfg.getExplainability()) != cmd.explainability()) {
            cfg.setExplainability(cmd.explainability());
            changed = true;
        }
        if (cmd.autoAudit() != null
                && Boolean.TRUE.equals(cfg.getAutoAudit()) != cmd.autoAudit()) {
            cfg.setAutoAudit(cmd.autoAudit());
            changed = true;
        }
        if (!changed) {
            return new SaveResult(false);
        }
        cfg.setUpdatedBy(actor);
        cfgRepo.save(cfg);
        audit.record("AI_CFG", "CFG-1", actor, "UPDATE",
                payload(Map.of("defaultModelId", cfg.getDefaultModelId() == null ? "" : cfg.getDefaultModelId(),
                        "grayScale", gray,
                        "retentionMonths", retention,
                        "sensitiveCheck", cfg.getSensitiveCheck(),
                        "explainability", cfg.getExplainability(),
                        "autoAudit", cfg.getAutoAudit())));
        return new SaveResult(true);
    }

    private List<ModelOption> modelOptions() {
        return modelRepo.findAllByOrderByPriorityAscModelIdDesc().stream()
                .map(m -> new ModelOption(m.getModelId(), modelLabel(m)))
                .toList();
    }

    private String modelLabel(AiModel m) {
        String name = m.getDisplayName() == null || m.getDisplayName().isBlank()
                ? m.getModelCode() : m.getDisplayName();
        return name + "（" + m.getModelCode() + "）";
    }

    private CfgView toView(AiGlobalCfg c, List<ModelOption> options) {
        return new CfgView(
                c.getDefaultModelId(),
                c.getGrayScale(),
                c.getRetentionMonths(),
                Boolean.TRUE.equals(c.getSensitiveCheck()),
                Boolean.TRUE.equals(c.getExplainability()),
                Boolean.TRUE.equals(c.getAutoAudit()),
                c.getUpdatedBy(),
                c.getUpdatedAt(),
                options);
    }

    private AiGlobalCfg requireCfg() {
        return cfgRepo.findById(1).orElseGet(() -> {
            AiGlobalCfg c = new AiGlobalCfg();
            return cfgRepo.save(c);
        });
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
