package com.meiyun.ai.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiT4Feature;
import com.meiyun.ai.domain.AiT4FeatureLineage;
import com.meiyun.ai.domain.AiT4FeatureLineageRepository;
import com.meiyun.ai.domain.AiT4FeatureRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * T4 特征平台：特征注册 / 发布 / 下线 / 在线服务开关 + 血缘 DAG。
 *
 * 诚实口径：
 * 1. callCount30d 为种子快照，无真实采集探针；注册固定从 0 起（逐字 mock registerFeature）；
 * 2. 血缘 MODEL 节点 code（mdl-churn/mdl-skin/mdl-rec）与卡1 模型仓库业务码一致，禁改名；
 * 3. 注册同步血缘逐字对齐 mock：插 FEATURE 节点 + src-{source} 源节点（不存在才插）+ 源→特征边；
 * 4. 发布闸门：mock 对非 REGISTERED 静默返回，后端收窄为中文 400（照卡1 非 READY 禁发先例）；
 * 5. 新特征置顶展示由前端适配层负责（后端按 feature_id 升序直返，照卡2 先例）。
 */
@Service
public class T4FeatureService {

    private static final int CODE_MAX = 40;
    private static final int NAME_MAX = 64;
    private static final int GROUP_MAX = 64;
    private static final int DESC_MAX = 500;
    private static final int SOURCE_MAX = 60;
    private static final int OWNER_MAX = 64;
    private static final int TTL_MAX = 32;
    private static final int FRESHNESS_MAX = 32;
    private static final Set<String> FEATURE_TYPES = Set.of("ONLINE", "OFFLINE");
    private static final Set<String> VALUE_TYPES = Set.of("INT", "FLOAT", "STRING", "VECTOR", "BOOL");
    private static final Map<String, String> STATUS_LABEL = Map.of(
            "DRAFT", "草稿", "REGISTERED", "已注册", "PUBLISHED", "已发布", "DEPRECATED", "已下线");
    private static final Map<String, String> VALUE_TYPE_LABEL = Map.of(
            "INT", "整数", "FLOAT", "浮点", "STRING", "字符串", "VECTOR", "向量", "BOOL", "布尔");

    private final AiT4FeatureRepository featureRepo;
    private final AiT4FeatureLineageRepository lineageRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public T4FeatureService(AiT4FeatureRepository featureRepo,
                            AiT4FeatureLineageRepository lineageRepo,
                            AuditRecorder audit) {
        this.featureRepo = featureRepo;
        this.lineageRepo = lineageRepo;
        this.audit = audit;
    }

    public record FeatureView(String code, String name, String group, String type, String valueType,
                              String description, String source, String status, String owner,
                              boolean onlineServing, String ttl, long callCount30d, String freshness,
                              String version, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record LineageNodeView(String id, String name, String type) {
    }

    public record LineageEdgeView(String from, String to) {
    }

    public record LineageView(List<LineageNodeView> nodes, List<LineageEdgeView> edges) {
    }

    /** 特征总览（前端 load 一次拉取：features + lineage 双契约） */
    public record FeatureOverview(List<FeatureView> features, LineageView lineage) {
    }

    public record RegisterCmd(String name, String group, String type, String valueType,
                              String description, String source, String owner,
                              Boolean onlineServing, String ttl, String freshness) {
    }

    public record ServingCmd(Boolean online) {
    }

    @Transactional(readOnly = true)
    public FeatureOverview overview() {
        List<FeatureView> features = featureRepo.findAllByOrderByFeatureIdAsc().stream()
                .map(this::toView).toList();
        List<LineageNodeView> nodes = lineageRepo.findByKindOrderByLineageIdAsc("NODE").stream()
                .map(n -> new LineageNodeView(n.getNodeId(), n.getNodeName(), n.getNodeType())).toList();
        List<LineageEdgeView> edges = lineageRepo.findByKindOrderByLineageIdAsc("EDGE").stream()
                .map(e -> new LineageEdgeView(e.getFromNode(), e.getToNode())).toList();
        return new FeatureOverview(features, new LineageView(nodes, edges));
    }

    // 刻意不加方法级事务：save 由仓储自身事务提交，随后 findByCode 全新读回时间戳（照卡1 register 先例）。
    public FeatureView register(RegisterCmd cmd, String actor) {
        String name = normalizeName(cmd == null ? null : cmd.name());
        if (featureRepo.existsByName(name)) {
            throw badRequest("特征名称已存在（" + name + "）");
        }
        String group = normalizeGroup(cmd == null ? null : cmd.group());
        String type = normalizeType(cmd == null ? null : cmd.type());
        String valueType = normalizeValueType(cmd == null ? null : cmd.valueType());
        String description = normalizeDesc(cmd == null ? null : cmd.description());
        String source = normalizeSource(cmd == null ? null : cmd.source());
        String owner = normalizeOwner(cmd == null ? null : cmd.owner());
        boolean onlineServing = cmd != null && Boolean.TRUE.equals(cmd.onlineServing());
        String ttl = normalizeTtl(cmd == null ? null : cmd.ttl());
        String freshness = normalizeFreshness(cmd == null ? null : cmd.freshness());

        AiT4Feature f = new AiT4Feature();
        f.setCode(nextCode());
        f.setName(name);
        f.setFeatureGroup(group);
        f.setType(type);
        f.setValueType(valueType);
        f.setDescription(description);
        f.setSource(source);
        f.setStatus("REGISTERED");
        f.setOwner(owner);
        f.setOnlineServing(onlineServing);
        f.setTtl(ttl);
        f.setCallCount30d(0L);
        f.setFreshness(freshness);
        f.setVersion("1.0.0");
        AiT4Feature saved = featureRepo.save(f);

        // 同步血缘（逐字 mock registerFeature）：FEATURE 节点 + src-{source} 源节点幂等 + 源→特征边
        saveNode(saved.getCode(), saved.getName(), "FEATURE");
        String srcId = "src-" + source;
        if (!lineageRepo.existsByKindAndNodeId("NODE", srcId)) {
            saveNode(srcId, source, "SOURCE");
        }
        if (!lineageRepo.existsByKindAndFromNodeAndToNode("EDGE", srcId, saved.getCode())) {
            saveEdge(srcId, saved.getCode());
        }

        audit.record("AI_T4_FEATURE", saved.getCode(), actor, "REGISTER",
                payload(Map.of("name", name, "group", group, "valueType", valueType)));
        return featureRepo.findByCode(saved.getCode()).map(this::toView)
                .orElseGet(() -> toView(saved));
    }

    /** 发布：仅 REGISTERED → PUBLISHED（mock 静默返回，后端收窄中文 400，照卡1 非 READY 禁发先例） */
    @Transactional
    public FeatureView publish(String code, String actor) {
        AiT4Feature f = mustGet(code);
        if (!"REGISTERED".equals(f.getStatus())) {
            throw badRequest("仅「已注册」状态的特征可发布（当前：" + statusLabel(f.getStatus()) + "）");
        }
        f.setStatus("PUBLISHED");
        featureRepo.save(f);
        audit.record("AI_T4_FEATURE", f.getCode(), actor, "PUBLISH", payload(Map.of("name", f.getName())));
        return toView(f);
    }

    /** 下线：任意态 → DEPRECATED 并强制关闭在线服务（逐字 mock deprecateFeature） */
    @Transactional
    public FeatureView deprecate(String code, String actor) {
        AiT4Feature f = mustGet(code);
        f.setStatus("DEPRECATED");
        f.setOnlineServing(false);
        featureRepo.save(f);
        audit.record("AI_T4_FEATURE", f.getCode(), actor, "DEPRECATE", payload(Map.of("name", f.getName())));
        return toView(f);
    }

    /** 在线服务开关：仅翻 onlineServing（逐字 mock toggleOnline） */
    @Transactional
    public FeatureView updateServing(String code, ServingCmd cmd, String actor) {
        AiT4Feature f = mustGet(code);
        if (cmd == null || cmd.online() == null) {
            throw badRequest("在线服务开关不能为空");
        }
        f.setOnlineServing(cmd.online());
        featureRepo.save(f);
        audit.record("AI_T4_FEATURE", f.getCode(), actor, "TOGGLE_SERVING",
                payload(Map.of("name", f.getName(), "online", cmd.online())));
        return toView(f);
    }

    private void saveNode(String nodeId, String nodeName, String nodeType) {
        AiT4FeatureLineage n = new AiT4FeatureLineage();
        n.setKind("NODE");
        n.setNodeId(nodeId);
        n.setNodeName(nodeName);
        n.setNodeType(nodeType);
        lineageRepo.save(n);
    }

    private void saveEdge(String fromNode, String toNode) {
        AiT4FeatureLineage e = new AiT4FeatureLineage();
        e.setKind("EDGE");
        e.setFromNode(fromNode);
        e.setToNode(toNode);
        lineageRepo.save(e);
    }

    private AiT4Feature mustGet(String code) {
        String c = code == null ? "" : code.trim();
        return featureRepo.findByCode(c)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "特征不存在（code=" + c + "）"));
    }

    /** 业务码分配：feat- 前缀 + 时间戳 36 进制 + 1 随机位，uk 冲突重试（照 mdl-/quota- 先例，8 次足够） */
    private String nextCode() {
        for (int i = 0; i < 8; i++) {
            String c = "feat-" + Long.toString(System.currentTimeMillis(), 36)
                    + Integer.toString(ThreadLocalRandom.current().nextInt(36), 36);
            if (c.length() <= CODE_MAX && !featureRepo.existsByCode(c)) {
                return c;
            }
        }
        throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "特征编码分配失败，请重试");
    }

    // ---------- 视图 ----------

    private FeatureView toView(AiT4Feature f) {
        return new FeatureView(f.getCode(), f.getName(), f.getFeatureGroup(), f.getType(),
                f.getValueType(), f.getDescription(), f.getSource(), f.getStatus(), f.getOwner(),
                Boolean.TRUE.equals(f.getOnlineServing()), f.getTtl(),
                f.getCallCount30d() == null ? 0L : f.getCallCount30d(),
                f.getFreshness(), f.getVersion(), f.getCreatedAt(), f.getUpdatedAt());
    }

    // ---------- 校验 ----------

    private String normalizeName(String name) {
        if (name == null || name.isBlank()) {
            throw badRequest("特征名称不能为空");
        }
        String t = name.trim();
        if (t.length() > NAME_MAX) {
            throw badRequest("特征名称不能超过 " + NAME_MAX + " 字");
        }
        return t;
    }

    private String normalizeGroup(String group) {
        if (group == null || group.isBlank()) {
            throw badRequest("特征分组不能为空");
        }
        String t = group.trim();
        if (t.length() > GROUP_MAX) {
            throw badRequest("特征分组不能超过 " + GROUP_MAX + " 字");
        }
        return t;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw badRequest("特征类型不能为空");
        }
        String t = type.trim().toUpperCase();
        if (!FEATURE_TYPES.contains(t)) {
            throw badRequest("特征类型仅支持 ONLINE（在线）/ OFFLINE（离线）");
        }
        return t;
    }

    private String normalizeValueType(String valueType) {
        if (valueType == null || valueType.isBlank()) {
            throw badRequest("值类型不能为空");
        }
        String t = valueType.trim().toUpperCase();
        if (!VALUE_TYPES.contains(t)) {
            throw badRequest("值类型仅支持 INT（整数）/ FLOAT（浮点）/ STRING（字符串）/ VECTOR（向量）/ BOOL（布尔）");
        }
        return t;
    }

    private String normalizeDesc(String description) {
        if (description == null) {
            return "";
        }
        String t = description.trim();
        if (t.length() > DESC_MAX) {
            throw badRequest("特征描述不能超过 " + DESC_MAX + " 字");
        }
        return t;
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            throw badRequest("数据源不能为空");
        }
        String t = source.trim();
        if (t.length() > SOURCE_MAX) {
            throw badRequest("数据源不能超过 " + SOURCE_MAX + " 字");
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

    private String normalizeTtl(String ttl) {
        if (ttl == null) {
            return null;
        }
        String t = ttl.trim();
        if (t.length() > TTL_MAX) {
            throw badRequest("TTL 不能超过 " + TTL_MAX + " 字");
        }
        return t.isBlank() ? null : t;
    }

    private String normalizeFreshness(String freshness) {
        if (freshness == null || freshness.isBlank()) {
            throw badRequest("新鲜度不能为空");
        }
        String t = freshness.trim();
        if (t.length() > FRESHNESS_MAX) {
            throw badRequest("新鲜度不能超过 " + FRESHNESS_MAX + " 字");
        }
        return t;
    }

    private static String statusLabel(String status) {
        return STATUS_LABEL.getOrDefault(status, status);
    }

    static String valueTypeLabel(String valueType) {
        return VALUE_TYPE_LABEL.getOrDefault(valueType, valueType);
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
