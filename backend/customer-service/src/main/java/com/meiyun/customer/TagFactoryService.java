package com.meiyun.customer;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * T2-B2 标签工厂服务（DESIGN-T2 T2-03，/api/customer/t2/tagfactory）。
 * 状态机（与前端 stores/t2TagFactory.ts 逐字对齐）：publish 仅 DRAFT/OFFLINE（SENSITIVE→PENDING_APPROVAL
 * 否则直发 PUBLISHED）；approve 仅 PENDING_APPROVAL；offline 仅 PUBLISHED；delete 禁 PUBLISHED
 * （409 透出 mock 原文「已发布标签不可删除，请先下线」）——其余违反一律 409 中文透出当前态
 * （幂等提交方据此识别已流转）。
 * doPublish：版本 v{n+1}.0 追加 versions jsonb；coverCount 确定性（base 按加工方式＋sql+id 哈希取模
 * spread，区间对齐 mock 随机区间 SQL 2000+/RULE 1500+/ML 3000+）；同事务 syncToM3 直写 customer_tag
 * （name 查重软跳过对齐 mock if(!existing)·窄映射 RULE→行为/SQL→消费/ML→价值·TG### max+1
 * synchronized 防重号复用 CustomerService.createTag 模式；mock 的 color/rule 两字段 customer_tag
 * 无对应列故弃用）。
 * 操作人取 DataScope.currentActor()（请求体不收 actor，防伪造）；owner 展示用登录人姓名（govern 先例）。
 */
@Service
public class TagFactoryService {

    private static final Set<String> TYPES = Set.of(
            TagFactoryDef.TYPE_SQL, TagFactoryDef.TYPE_RULE, TagFactoryDef.TYPE_ML);
    private static final Set<String> SENSITIVITIES = Set.of(
            TagFactoryDef.SENS_PUBLIC, TagFactoryDef.SENS_INTERNAL, TagFactoryDef.SENS_SENSITIVE);
    private static final Set<String> VALUE_TYPES = Set.of(
            TagFactoryDef.VALUE_ENUM, TagFactoryDef.VALUE_NUMBER,
            TagFactoryDef.VALUE_BOOLEAN, TagFactoryDef.VALUE_DATE);
    private static final Set<String> PUBLISHABLE = Set.of(
            TagFactoryDef.STATUS_DRAFT, TagFactoryDef.STATUS_OFFLINE);

    private final TagFactoryDefRepository defRepository;
    private final CustomerTagRepository customerTagRepository;
    private final AuditRecorder audit;
    private final ObjectMapper mapper;

    public TagFactoryService(TagFactoryDefRepository defRepository,
                             CustomerTagRepository customerTagRepository,
                             AuditRecorder audit, ObjectMapper mapper) {
        this.defRepository = defRepository;
        this.customerTagRepository = customerTagRepository;
        this.audit = audit;
        this.mapper = mapper;
    }

    /** 版本视图（jsonb 元素结构，字段名对齐前端 TagVersion）。 */
    public record VersionView(String version, String sql, String publishedAt,
                              String publishedBy, Integer coverCount) {}

    /** 消费方视图（jsonb 元素结构，字段名对齐前端 TagConsumer）。 */
    public record ConsumerView(String module, String scene, String usedAt) {}

    /** 标签视图（字段名对齐前端 FactoryTag 契约）。 */
    public record TagView(Long id, String code, String name, String category, String type,
                          String sensitivity, String valueType, String description, String sql,
                          String status, Integer coverCount, String refreshCron, String lastComputeAt,
                          List<VersionView> versions, List<ConsumerView> consumers, String owner,
                          List<String> tags, String createdAt, String updatedAt) {}

    /** 试算结果视图。 */
    public record PreviewView(Integer coverCount) {}

    /** 标签列表：id 升序（种子插入序=前端 mock 数组序）。 */
    @Transactional(readOnly = true)
    public List<TagView> listTags() {
        return defRepository.findAllByOrderByIdAsc().stream().map(this::toView).toList();
    }

    /** 新建标签：编码/名称必填且各自唯一（409 中文）；词表校验；缺省 status=DRAFT/owner=当前登录人姓名；落 CREATE 审计。 */
    @Transactional
    public TagView createTag(String code, String name, String category, String type, String sensitivity,
                             String valueType, String description, String sql, String refreshCron,
                             List<String> tags) {
        if (code == null || code.isBlank()) {
            throw new BadReq("标签编码必填");
        }
        if (name == null || name.isBlank()) {
            throw new BadReq("标签名称必填");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new BadReq("加工方式不合法，仅支持 SQL/RULE/ML");
        }
        if (valueType == null || !VALUE_TYPES.contains(valueType)) {
            throw new BadReq("值类型不合法，仅支持 ENUM/NUMBER/BOOLEAN/DATE");
        }
        if (sensitivity != null && !SENSITIVITIES.contains(sensitivity)) {
            throw new BadReq("敏感等级不合法，仅支持 PUBLIC/INTERNAL/SENSITIVE");
        }
        if (defRepository.existsByCode(code.trim())) {
            throw new Conflict("标签编码已存在：" + code.trim());
        }
        if (defRepository.existsByName(name.trim())) {
            throw new Conflict("标签名称已存在：" + name.trim());
        }
        String actor = DataScope.currentActor();
        TagFactoryDef t = new TagFactoryDef();
        t.setCode(code.trim());
        t.setName(name.trim());
        t.setCategory(category == null ? "" : category.trim());
        t.setType(type);
        t.setSensitivity(sensitivity == null ? TagFactoryDef.SENS_PUBLIC : sensitivity);
        t.setValueType(valueType);
        t.setDescription(description == null ? "" : description.trim());
        t.setSql(sql == null ? "" : sql.trim());
        t.setStatus(TagFactoryDef.STATUS_DRAFT);
        t.setCoverCount(0);
        t.setRefreshCron(refreshCron == null ? "" : refreshCron.trim());
        t.setLastComputeAt(null);
        t.setVersions("[]");
        t.setConsumers("[]");
        t.setTags(writeJson(tags == null ? List.<String>of() : tags));
        t.setOwner(displayName(actor));
        TagFactoryDef saved = defRepository.save(t);
        audit.record("TAG_FACTORY", "FTAG-" + saved.getId(), actor, "CREATE",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"type\":\"" + saved.getType() + "\"}");
        return toView(saved);
    }

    /** 编辑标签：patch 语义（对齐 mock Object.assign 七字段 pick），非空字段才覆盖；改名排除自身查重 409；落 UPDATE 审计。 */
    @Transactional
    public TagView updateTag(Long id, String name, String description, String sql, String sensitivity,
                             String refreshCron, String category, List<String> tags) {
        TagFactoryDef t = mustGet(id);
        if (name != null && !name.isBlank()) {
            String n = name.trim();
            if (!n.equals(t.getName()) && defRepository.existsByName(n)) {
                throw new Conflict("标签名称已存在：" + n);
            }
            t.setName(n);
        }
        if (description != null) {
            t.setDescription(description.trim());
        }
        if (sql != null) {
            t.setSql(sql.trim());
        }
        if (sensitivity != null) {
            if (!SENSITIVITIES.contains(sensitivity)) {
                throw new BadReq("敏感等级不合法，仅支持 PUBLIC/INTERNAL/SENSITIVE");
            }
            t.setSensitivity(sensitivity);
        }
        if (refreshCron != null) {
            t.setRefreshCron(refreshCron.trim());
        }
        if (category != null) {
            t.setCategory(category.trim());
        }
        if (tags != null) {
            t.setTags(writeJson(tags));
        }
        t.setUpdatedAt(OffsetDateTime.now());
        String actor = DataScope.currentActor();
        TagFactoryDef saved = defRepository.save(t);
        audit.record("TAG_FACTORY", "FTAG-" + saved.getId(), actor, "UPDATE",
                "{\"name\":\"" + esc(saved.getName()) + "\"}");
        return toView(saved);
    }

    /** 试算：确定性覆盖人数（同输入同输出，区间对齐 mock 随机区间）。 */
    @Transactional(readOnly = true)
    public PreviewView previewCompute(Long id) {
        return new PreviewView(deterministicCover(mustGet(id)));
    }

    /** 发布：仅 DRAFT/OFFLINE；SENSITIVE→PENDING_APPROVAL（落 SUBMIT_APPROVAL 审计）否则 doPublish 直发（落 PUBLISH 审计）。synchronized 护 TG### 取号。 */
    @Transactional
    public synchronized TagView publishTag(Long id) {
        TagFactoryDef t = mustGet(id);
        mustIn(t, PUBLISHABLE);
        String actor = DataScope.currentActor();
        if (TagFactoryDef.SENS_SENSITIVE.equals(t.getSensitivity())) {
            t.setStatus(TagFactoryDef.STATUS_PENDING);
            t.setUpdatedAt(OffsetDateTime.now());
            TagFactoryDef saved = defRepository.save(t);
            audit.record("TAG_FACTORY", "FTAG-" + saved.getId(), actor, "SUBMIT_APPROVAL",
                    "{\"name\":\"" + esc(saved.getName()) + "\"}");
            return toView(saved);
        }
        doPublish(t, displayName(actor));
        TagFactoryDef saved = defRepository.save(t);
        audit.record("TAG_FACTORY", "FTAG-" + saved.getId(), actor, "PUBLISH",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"coverCount\":" + saved.getCoverCount() + "}");
        return toView(saved);
    }

    /** 审批通过发布：仅 PENDING_APPROVAL；doPublish 同事务（落 APPROVE 审计）。synchronized 护 TG### 取号。 */
    @Transactional
    public synchronized TagView approvePublish(Long id) {
        TagFactoryDef t = mustGet(id);
        mustIn(t, Set.of(TagFactoryDef.STATUS_PENDING));
        String actor = DataScope.currentActor();
        doPublish(t, displayName(actor));
        TagFactoryDef saved = defRepository.save(t);
        audit.record("TAG_FACTORY", "FTAG-" + saved.getId(), actor, "APPROVE",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"coverCount\":" + saved.getCoverCount() + "}");
        return toView(saved);
    }

    /** 下线：仅 PUBLISHED→OFFLINE；落 OFFLINE 审计。 */
    @Transactional
    public TagView offlineTag(Long id) {
        TagFactoryDef t = mustGet(id);
        mustIn(t, Set.of(TagFactoryDef.STATUS_PUBLISHED));
        t.setStatus(TagFactoryDef.STATUS_OFFLINE);
        t.setUpdatedAt(OffsetDateTime.now());
        String actor = DataScope.currentActor();
        TagFactoryDef saved = defRepository.save(t);
        audit.record("TAG_FACTORY", "FTAG-" + saved.getId(), actor, "OFFLINE",
                "{\"name\":\"" + esc(saved.getName()) + "\"}");
        return toView(saved);
    }

    /** 删除：PUBLISHED 409 透出 mock 原文；落 DELETE 审计。 */
    @Transactional
    public void deleteTag(Long id) {
        TagFactoryDef t = mustGet(id);
        if (TagFactoryDef.STATUS_PUBLISHED.equals(t.getStatus())) {
            throw new Conflict("已发布标签不可删除，请先下线");
        }
        String actor = DataScope.currentActor();
        defRepository.delete(t);
        audit.record("TAG_FACTORY", "FTAG-" + id, actor, "DELETE",
                "{\"name\":\"" + esc(t.getName()) + "\",\"code\":\"" + esc(t.getCode()) + "\"}");
    }

    /** 发布主流程：版本 v{n+1}.0 追加＋确定性 coverCount＋lastComputeAt＋同事务 syncToM3（对齐 mock doPublish）。 */
    private void doPublish(TagFactoryDef t, String publisherName) {
        OffsetDateTime now = OffsetDateTime.now();
        List<VersionView> versions = parseVersions(t.getVersions());
        String version = "v" + (versions.size() + 1) + ".0";
        int cover = deterministicCover(t);
        versions.add(new VersionView(version, t.getSql(), now.toString(), publisherName, cover));
        t.setVersions(writeJson(versions));
        t.setStatus(TagFactoryDef.STATUS_PUBLISHED);
        t.setCoverCount(cover);
        t.setLastComputeAt(now);
        t.setUpdatedAt(now);
        syncToM3(t);
    }

    /** 同步标签到 M3-06 标签体系（customer_tag 窄表）：名称查重存在即跳过（对齐 mock if(!existing)）；窄映射 RULE→行为/SQL→消费/ML→价值。 */
    private void syncToM3(TagFactoryDef t) {
        if (customerTagRepository.existsByTagName(t.getName())) {
            return;
        }
        CustomerTag ct = new CustomerTag();
        ct.setTagId(nextTgId());
        ct.setTagName(t.getName());
        ct.setCategory(narrowCategory(t.getType()));
        customerTagRepository.save(ct);
    }

    /** 类型→customer_tag 五分类窄映射（customer_tag 仅 消费/肤质/行为/价值/医疗 五值）。 */
    static String narrowCategory(String type) {
        if (TagFactoryDef.TYPE_RULE.equals(type)) {
            return "行为";
        }
        return TagFactoryDef.TYPE_SQL.equals(type) ? "消费" : "价值";
    }

    /** TG### 库内 max+1（定长 3 位序号；复用 CustomerService.nextTagId 模式，服务层调用方 synchronized 防重号）。 */
    private String nextTgId() {
        String max = customerTagRepository.maxTgId();
        int seq = 0;
        if (max != null && max.startsWith("TG")) {
            try {
                seq = Integer.parseInt(max.substring(2));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("TG%03d", seq + 1);
    }

    /** 确定性覆盖人数：base 按加工方式（SQL 2000/RULE 1500/ML 3000）＋sql+id 哈希取模 spread（SQL 1000/RULE 800/ML 1500，区间对齐 mock 随机区间）。 */
    private static int deterministicCover(TagFactoryDef t) {
        int base;
        int spread;
        switch (t.getType() == null ? "" : t.getType()) {
            case TagFactoryDef.TYPE_SQL -> {
                base = 2000;
                spread = 1000;
            }
            case TagFactoryDef.TYPE_RULE -> {
                base = 1500;
                spread = 800;
            }
            default -> {
                base = 3000;
                spread = 1500;
            }
        }
        return base + Math.floorMod(((t.getSql() == null ? "" : t.getSql()) + t.getId()).hashCode(), spread);
    }

    private TagFactoryDef mustGet(Long id) {
        return defRepository.findById(id).orElseThrow(() -> new NotFound("标签定义不存在"));
    }

    /** 状态机前置校验：违反→409 中文透出当前态（幂等提交方据此识别已流转）。 */
    private static void mustIn(TagFactoryDef t, Set<String> allowed) {
        if (!allowed.contains(t.getStatus())) {
            throw new Conflict("当前状态「" + statusLabel(t.getStatus()) + "」不允许此操作");
        }
    }

    private TagView toView(TagFactoryDef t) {
        return new TagView(
                t.getId(),
                t.getCode(),
                t.getName(),
                t.getCategory(),
                t.getType(),
                t.getSensitivity(),
                t.getValueType(),
                t.getDescription(),
                t.getSql(),
                t.getStatus(),
                t.getCoverCount() == null ? 0 : t.getCoverCount(),
                t.getRefreshCron(),
                t.getLastComputeAt() == null ? null : t.getLastComputeAt().toString(),
                parseVersions(t.getVersions()),
                parseConsumers(t.getConsumers()),
                t.getOwner(),
                parseTags(t.getTags()),
                t.getCreatedAt() == null ? "" : t.getCreatedAt().toString(),
                t.getUpdatedAt() == null ? "" : t.getUpdatedAt().toString());
    }

    private List<VersionView> parseVersions(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(json, new TypeReference<ArrayList<VersionView>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<ConsumerView> parseConsumers(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(json, new TypeReference<ArrayList<ConsumerView>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private List<String> parseTags(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return mapper.readValue(json, new TypeReference<ArrayList<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private String writeJson(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 展示用姓名：登录人姓名优先，缺省回退工号（govern createRule 先例）。 */
    private static String displayName(String actor) {
        LoginUser u = SecurityContext.get();
        return (u != null && u.staffName() != null && !u.staffName().isBlank()) ? u.staffName() : actor;
    }

    /** 状态中文标签（4xx 报错透出当前态，便于前端/运维直读）。 */
    private static String statusLabel(String status) {
        return switch (status == null ? "" : status) {
            case TagFactoryDef.STATUS_DRAFT -> "草稿";
            case TagFactoryDef.STATUS_PROCESSING -> "计算中";
            case TagFactoryDef.STATUS_PUBLISHED -> "已发布";
            case TagFactoryDef.STATUS_OFFLINE -> "已下线";
            case TagFactoryDef.STATUS_PENDING -> "待审批";
            default -> status == null ? "未知" : status;
        };
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
