package com.meiyun.ai.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.approval.ApprovalService;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiT4Model;
import com.meiyun.ai.domain.AiT4ModelRepository;
import com.meiyun.ai.domain.AiT4ModelVersion;
import com.meiyun.ai.domain.AiT4ModelVersionRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * T4 模型仓库：登记 / 版本管理 / 发布审批 / 回滚 / 废弃。
 *
 * 红线与诚实口径：
 * 1. 发布仅走审批中心 T4_MODEL 单（非 READY 禁发）；审批中心 POST /api/ai/approvals 需
 *    aiAdmin:edit，而 T4 用户仅持 model:release，故由本服务服务端直连 ApprovalService 代为建单，
 *    前端零权限绕行；
 * 2. 审批通过联动（其余 PUBLISHED 回退 READY + 目标版本发布 + 模型 status/currentVersion 联动）
 *    在 ApprovalService.applyLinkage 的 T4_MODEL 分支，语义逐字对齐前端 mock releaseModel；
 * 3. metrics 以 JSON 文本存 TEXT 列（ai-service 无 JSONB 映射先例），读出即解析，
 *    解析失败归一为 {}，不伪造训练指标。
 */
@Service
public class T4ModelService {

    private static final int NAME_MAX = 120;
    private static final int CODE_MAX = 40;
    private static final int VERSION_MAX = 40;
    private static final int DESC_MAX = 500;
    private static final int OWNER_MAX = 64;
    private static final int DEPT_MAX = 64;
    private static final int TAGS_MAX = 500;
    private static final int SCHEMA_MAX = 4000;
    private static final int REMARK_MAX = 500;
    private static final Set<String> MODEL_TYPES =
            Set.of("CLASSIFICATION", "REGRESSION", "NLP", "CV", "RECOMMEND", "GENERATIVE");
    /** 上传版本允许的起始状态；PUBLISHED/DEPRECATED 仅经审批发布/废弃链路到达（红线纵深） */
    private static final Set<String> VERSION_START_STATUS = Set.of("DRAFT", "TRAINING", "READY");

    private final AiT4ModelRepository modelRepo;
    private final AiT4ModelVersionRepository versionRepo;
    private final ApprovalService approvalService;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public T4ModelService(AiT4ModelRepository modelRepo,
                          AiT4ModelVersionRepository versionRepo,
                          ApprovalService approvalService,
                          AuditRecorder audit) {
        this.modelRepo = modelRepo;
        this.versionRepo = versionRepo;
        this.approvalService = approvalService;
        this.audit = audit;
    }

    public record VersionView(String version, Map<String, Double> metrics, String status,
                              OffsetDateTime trainedAt, OffsetDateTime publishedAt,
                              String approvedBy, String remark) {
    }

    public record ModelView(String code, String name, String type, String description, String owner,
                            String department, List<String> tags, List<VersionView> versions,
                            String currentVersion, String status, String inputSchema, String outputSchema,
                            long callCount30d, int avgLatencyMs, double errorRate,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record RegisterCmd(String name, String type, String description, String owner,
                              String department, List<String> tags, String inputSchema, String outputSchema) {
    }

    public record VersionCmd(String version, Map<String, Double> metrics, String status, String remark) {
    }

    public record ReleaseCmd(String version) {
    }

    public record RollbackCmd(String version) {
    }

    public record ReleaseResult(Long approvalId, String message) {
    }

    /** 模型仓库列表全量直返（登记量级小，前端 models 为数组契约，不分页）；版本按 modelId 分组嵌套。 */
    @Transactional(readOnly = true)
    public List<ModelView> list() {
        List<AiT4Model> models = modelRepo.findAllByOrderByModelIdAsc();
        if (models.isEmpty()) {
            return List.of();
        }
        List<Long> ids = models.stream().map(AiT4Model::getModelId).toList();
        Map<Long, List<AiT4ModelVersion>> grouped = versionRepo.findByModelIdInOrderByVersionIdAsc(ids)
                .stream().collect(Collectors.groupingBy(AiT4ModelVersion::getModelId,
                        LinkedHashMap::new, Collectors.toList()));
        return models.stream()
                .map(m -> toView(m, grouped.getOrDefault(m.getModelId(), List.of())))
                .toList();
    }

    // 刻意不加方法级事务：save 由仓储自身事务提交，随后 findByCode 全新读回时间戳。
    public ModelView register(RegisterCmd cmd, String actor) {
        String name = normalizeName(cmd == null ? null : cmd.name());
        String type = normalizeType(cmd == null ? null : cmd.type());
        String owner = normalizeOwner(cmd == null ? null : cmd.owner());
        String department = normalizeDepartment(cmd == null ? null : cmd.department());
        String description = normalizeDesc(cmd == null ? null : cmd.description());
        String tags = joinTags(cmd == null ? null : cmd.tags());
        String inputSchema = normalizeSchema(cmd == null ? null : cmd.inputSchema(), "输入 Schema");
        String outputSchema = normalizeSchema(cmd == null ? null : cmd.outputSchema(), "输出 Schema");

        AiT4Model m = new AiT4Model();
        m.setCode(nextCode());
        m.setName(name);
        m.setType(type);
        m.setDescription(description);
        m.setOwner(owner);
        m.setDepartment(department);
        m.setTags(tags);
        m.setStatus("DRAFT");
        m.setInputSchema(inputSchema);
        m.setOutputSchema(outputSchema);
        m.setCallCount30d(0L);
        m.setAvgLatencyMs(0);
        m.setErrorRate(0.0);
        AiT4Model saved = modelRepo.save(m);
        audit.record("AI_T4_MODEL", saved.getCode(), actor, "REGISTER",
                payload(Map.of("name", name, "type", type, "owner", owner)));
        return modelRepo.findByCode(saved.getCode()).map(x -> toView(x, List.of()))
                .orElseGet(() -> toView(saved, List.of()));
    }

    /** 新增版本：模型状态联动逐字对齐 mock（READY 拉起 DRAFT→READY；TRAINING 强制模型回训练中）。 */
    @Transactional
    public ModelView addVersion(String code, VersionCmd cmd, String actor) {
        AiT4Model m = mustGet(code);
        String ver = normalizeVersion(cmd == null ? null : cmd.version());
        if (versionRepo.existsByModelIdAndVersion(m.getModelId(), ver)) {
            throw badRequest("版本号已存在（" + ver + "）");
        }
        String st = normalizeVersionStatus(cmd == null ? null : cmd.status());
        String metrics = writeMetrics(cmd == null ? null : cmd.metrics());
        String remark = normalizeRemark(cmd == null ? null : cmd.remark());

        AiT4ModelVersion v = new AiT4ModelVersion();
        v.setModelId(m.getModelId());
        v.setVersion(ver);
        v.setMetrics(metrics);
        v.setStatus(st);
        v.setRemark(remark);
        versionRepo.save(v);
        if ("READY".equals(st) && "DRAFT".equals(m.getStatus())) {
            m.setStatus("READY");
        }
        if ("TRAINING".equals(st)) {
            m.setStatus("TRAINING");
        }
        modelRepo.save(m);
        audit.record("AI_T4_MODEL", code, actor, "ADD_VERSION",
                payload(Map.of("version", ver, "status", st)));
        return toView(m, versionRepo.findByModelIdOrderByVersionIdAsc(m.getModelId()));
    }

    /**
     * 发布申请：红线「非 READY 禁发」以 mock 逐字文案拒绝；
     * 随后服务端直连审批中心登记 T4_MODEL 待审批单（重复 PENDING 由审批中心既有校验拦截）。
     */
    @Transactional
    public ReleaseResult requestRelease(String code, ReleaseCmd cmd, String actor) {
        AiT4Model m = mustGet(code);
        String ver = normalizeVersion(cmd == null ? null : cmd.version());
        AiT4ModelVersion v = versionRepo.findByModelIdAndVersion(m.getModelId(), ver)
                .orElseThrow(() -> badRequest("版本不存在（" + ver + "）"));
        if (!"READY".equals(v.getStatus())) {
            throw badRequest("仅 READY 状态的版本可发布（红线：非 DRAFT 不能发布）");
        }
        ApprovalService.ApprovalView ap = approvalService.apply(
                new ApprovalService.ApplyCmd("T4_MODEL", v.getVersionId(),
                        "T4 模型发布申请：「" + m.getName() + "」v" + ver + "（" + code + "）"),
                actor);
        audit.record("AI_T4_MODEL", code, actor, "RELEASE_REQUEST",
                payload(Map.of("version", ver, "approvalId", ap.approvalId())));
        return new ReleaseResult(ap.approvalId(), "模型发布需经 T3-01 审批流程");
    }

    /** 回滚：目标版本直接置顶发布（运营应急通道），语义逐字对齐 mock rollbackModel。 */
    @Transactional
    public ModelView rollback(String code, RollbackCmd cmd, String actor) {
        AiT4Model m = mustGet(code);
        String ver = normalizeVersion(cmd == null ? null : cmd.version());
        AiT4ModelVersion v = versionRepo.findByModelIdAndVersion(m.getModelId(), ver)
                .orElseThrow(() -> badRequest("版本不存在（" + ver + "）"));
        demotePublished(m.getModelId());
        v.setStatus("PUBLISHED");
        v.setPublishedAt(OffsetDateTime.now());
        v.setApprovedBy(actor);
        versionRepo.save(v);
        m.setStatus("PUBLISHED");
        m.setCurrentVersion(ver);
        modelRepo.save(m);
        audit.record("AI_T4_MODEL", code, actor, "ROLLBACK", payload(Map.of("version", ver)));
        return toView(m, versionRepo.findByModelIdOrderByVersionIdAsc(m.getModelId()));
    }

    /** 废弃：模型整体下线，PUBLISHED 版本同步废弃，语义逐字对齐 mock deprecateModel。 */
    @Transactional
    public ModelView deprecate(String code, String actor) {
        AiT4Model m = mustGet(code);
        m.setStatus("DEPRECATED");
        versionRepo.findByModelIdAndStatus(m.getModelId(), "PUBLISHED").forEach(x -> {
            x.setStatus("DEPRECATED");
            versionRepo.save(x);
        });
        modelRepo.save(m);
        audit.record("AI_T4_MODEL", code, actor, "DEPRECATE", payload(Map.of("code", code)));
        return toView(m, versionRepo.findByModelIdOrderByVersionIdAsc(m.getModelId()));
    }

    /** 同模型其余 PUBLISHED 版本回退 READY（回滚共用；审批发布联动在 ApprovalService 自持） */
    private void demotePublished(Long modelId) {
        versionRepo.findByModelIdAndStatus(modelId, "PUBLISHED").forEach(x -> {
            x.setStatus("READY");
            x.setPublishedAt(null);
            x.setApprovedBy(null);
            versionRepo.save(x);
        });
    }

    private AiT4Model mustGet(String code) {
        String c = code == null ? "" : code.trim();
        return modelRepo.findByCode(c)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "模型不存在（code=" + c + "）"));
    }

    /** 业务码分配：mdl- 前缀 + 时间戳 36 进制 + 1 随机位，uk 冲突重试（注册低频，8 次足够） */
    private String nextCode() {
        for (int i = 0; i < 8; i++) {
            String c = "mdl-" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString(ThreadLocalRandom.current().nextInt(36), 36);
            if (c.length() <= CODE_MAX && !modelRepo.existsByCode(c)) {
                return c;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "模型编码分配失败，请重试");
    }

    // ---------- 视图 ----------

    private ModelView toView(AiT4Model m, List<AiT4ModelVersion> versions) {
        return new ModelView(
                m.getCode(), m.getName(), m.getType(), m.getDescription(), m.getOwner(),
                m.getDepartment(), splitTags(m.getTags()),
                versions.stream().map(this::toVersionView).toList(),
                m.getCurrentVersion(), m.getStatus(), m.getInputSchema(), m.getOutputSchema(),
                m.getCallCount30d() == null ? 0L : m.getCallCount30d(),
                m.getAvgLatencyMs() == null ? 0 : m.getAvgLatencyMs(),
                m.getErrorRate() == null ? 0.0 : m.getErrorRate(),
                m.getCreatedAt(), m.getUpdatedAt());
    }

    private VersionView toVersionView(AiT4ModelVersion v) {
        return new VersionView(v.getVersion(), readMetrics(v.getMetrics()), v.getStatus(),
                v.getTrainedAt(), v.getPublishedAt(), v.getApprovedBy(), v.getRemark());
    }

    // ---------- 校验 ----------

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("模型名称不能为空");
        }
        String t = name.trim();
        if (t.length() > NAME_MAX) {
            throw badRequest("模型名称不能超过 " + NAME_MAX + " 字");
        }
        return t;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw badRequest("模型类型不能为空");
        }
        String t = type.trim().toUpperCase();
        if (!MODEL_TYPES.contains(t)) {
            throw badRequest("模型类型仅支持 CLASSIFICATION（分类）/ REGRESSION（回归）/ NLP（自然语言）"
                    + "/ CV（计算机视觉）/ RECOMMEND（推荐）/ GENERATIVE（生成式）");
        }
        return t;
    }

    private String normalizeOwner(String owner) {
        if (owner == null || owner.isBlank()) {
            throw badRequest("负责人不能为空");
        }
        String t = owner.trim();
        if (t.length() > OWNER_MAX) {
            throw badRequest("负责人不能超过 " + OWNER_MAX + " 字");
        }
        return t;
    }

    private String normalizeDepartment(String department) {
        if (department == null || department.isBlank()) {
            throw badRequest("所属部门不能为空");
        }
        String t = department.trim();
        if (t.length() > DEPT_MAX) {
            throw badRequest("所属部门不能超过 " + DEPT_MAX + " 字");
        }
        return t;
    }

    private String normalizeDesc(String description) {
        if (description == null) {
            return "";
        }
        String t = description.trim();
        if (t.length() > DESC_MAX) {
            throw badRequest("模型描述不能超过 " + DESC_MAX + " 字");
        }
        return t;
    }

    private String normalizeVersion(String version) {
        if (version == null || version.isBlank()) {
            throw badRequest("版本号不能为空");
        }
        String t = version.trim();
        if (t.length() > VERSION_MAX) {
            throw badRequest("版本号不能超过 " + VERSION_MAX + " 字");
        }
        return t;
    }

    private String normalizeVersionStatus(String status) {
        if (status == null || status.isBlank()) {
            return "READY";
        }
        String t = status.trim().toUpperCase();
        if ("PUBLISHED".equals(t) || "DEPRECATED".equals(t)) {
            throw badRequest("版本发布/废弃须走审批与废弃链路，禁止上传时直接置为 " + t);
        }
        if (!VERSION_START_STATUS.contains(t)) {
            throw badRequest("版本状态仅支持 DRAFT/TRAINING/READY");
        }
        return t;
    }

    private String normalizeSchema(String schema, String label) {
        if (schema == null || schema.isBlank()) {
            return "{}";
        }
        String t = schema.trim();
        if (t.length() > SCHEMA_MAX) {
            throw badRequest(label + "不能超过 " + SCHEMA_MAX + " 字");
        }
        return t;
    }

    private String normalizeRemark(String remark) {
        if (remark == null) {
            return null;
        }
        String t = remark.trim();
        if (t.length() > REMARK_MAX) {
            throw badRequest("版本备注不能超过 " + REMARK_MAX + " 字");
        }
        return t.isBlank() ? null : t;
    }

    private String joinTags(List<String> tags) {
        if (tags == null) {
            return null;
        }
        String t = tags.stream().filter(x -> x != null).map(String::trim)
                .filter(x -> !x.isEmpty()).distinct().collect(Collectors.joining(","));
        if (t.length() > TAGS_MAX) {
            throw badRequest("标签总长度不能超过 " + TAGS_MAX + " 字");
        }
        return t.isBlank() ? null : t;
    }

    private static List<String> splitTags(String tags) {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return Arrays.stream(tags.split("[,，]")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    private Map<String, Double> readMetrics(String text) {
        if (text == null || text.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(text, new TypeReference<Map<String, Double>>() {
            });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private String writeMetrics(Map<String, Double> metrics) {
        if (metrics == null || metrics.isEmpty()) {
            return "{}";
        }
        try {
            return json.writeValueAsString(metrics);
        } catch (Exception e) {
            return "{}";
        }
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
