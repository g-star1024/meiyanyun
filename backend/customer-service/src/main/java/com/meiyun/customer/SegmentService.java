package com.meiyun.customer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.customer.client.MarketingFollowTaskClient;
import com.meiyun.customer.CustomerService.BadReq;
import com.meiyun.customer.CustomerService.Conflict;
import com.meiyun.customer.CustomerService.NotFound;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 客户分群服务（M3-B3 / DESIGN-M3 §3 D2 M3-14、D3-3）：
 * RULE 分群 CRUD + RULE 引擎实时计算命中数 + 批量建跟进任务 + 命中回写标签 +
 * 画像 sync-profile 落 AI 分群（D3-3：画像「应用到分群」→ tags 回写客户标签 →
 * AI 分群 conditions=[TAG_ANY(tags)] 由 RULE 引擎在 customer 域内自闭环计算）。
 *
 * <p>RULE 引擎条件六 kind（conditions JSONB [{kind,...,label}]，AND 组合）：
 * DORMANT_DAYS 沉睡（customer.status='沉睡'，days 文案口径）/ VISIT_IN_DAYS 到店 ≥ times
 * （累计 visit_count 近似，days 文案口径）/ SPEND_RANGE 累计消费区间 / LEVEL_GTE 等级 ≥
 * （普通<银卡<金卡<钻石<黑卡）/ TAG_ANY 带任一标签 / CREATED_IN_DAYS 建档 ≤ days 天。
 */
@Service
public class SegmentService {

    public static final Set<String> CONDITION_KINDS = Set.of(
            "DORMANT_DAYS", "VISIT_IN_DAYS", "SPEND_RANGE", "LEVEL_GTE", "TAG_ANY", "CREATED_IN_DAYS");
    public static final Set<String> TYPES = Set.of(
            "HIGH_POTENTIAL", "DORMANT", "PRICE_SENSITIVE", "HIGH_VALUE", "CHURN_RISK", "NEW");
    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "普通", 0, "银卡", 1, "金卡", 2, "钻石", 3, "黑卡", 4);
    /** 列表内嵌成员数（详情表「群内客户」快照）；完整名单走 members 端点。 */
    private static final int LIST_MEMBER_LIMIT = 20;
    private static final int MEMBER_LIMIT = 500;
    private static final int FOLLOW_TASK_LIMIT = 50;

    private final SegmentDefRepository segmentRepo;
    private final CustomerRepository customerRepo;
    private final CustomerTagRepository tagRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerService customerService;
    private final MarketingFollowTaskClient followTaskClient;
    private final AuditRecorder audit;
    private final ObjectMapper om = new ObjectMapper();

    public SegmentService(SegmentDefRepository segmentRepo, CustomerRepository customerRepo,
                          CustomerTagRepository tagRepo, CustomerTagRelRepository tagRelRepo,
                          CustomerService customerService, MarketingFollowTaskClient followTaskClient,
                          AuditRecorder audit) {
        this.segmentRepo = segmentRepo;
        this.customerRepo = customerRepo;
        this.tagRepo = tagRepo;
        this.tagRelRepo = tagRelRepo;
        this.customerService = customerService;
        this.followTaskClient = followTaskClient;
        this.audit = audit;
    }

    // -------------------- DTO --------------------

    public record CondInput(String kind, Integer days, Integer times, BigDecimal min, BigDecimal max,
                            String level, List<String> tags, String label) {}
    public record CreateCmd(String name, String type, List<CondInput> conditions,
                            String storeCode, String clientToken) {}
    public record UpdateCmd(String name, String type, List<CondInput> conditions) {}
    public record MemberView(String id, String name, String level, String lastVisit, List<String> matched) {}
    public record SegmentView(Long id, String segmentNo, String name, String type, String aiStatus,
                              List<String> conditions, String ruleSummary, int customerCount,
                              BigDecimal sharePct, String aiSuggestion, String updatedAt,
                              List<MemberView> members) {}
    public record RefreshResult(Long id, int customerCount, BigDecimal sharePct, int scanned) {}
    public record FollowTaskResult(Long id, int matched, int created) {}
    public record ApplyTagsResult(Long id, String tagId, int matched, int assigned) {}
    public record SyncProfileCmd(Long profileId, String customerId, String customerName,
                                 List<String> groups, List<String> tags) {}
    public record SyncProfileResult(int tagsAssigned, List<String> groupsCreated, List<String> groupsExisted) {}

    // -------------------- 查询 --------------------

    /** 列表：每个分群内嵌 top20 命中成员（详情表快照）。 */
    @Transactional(readOnly = true)
    public List<SegmentView> list() {
        List<SegmentDef> all = segmentRepo.findAll();
        Map<String, Set<String>> tagNames = loadTagNames();
        List<SegmentView> out = new ArrayList<>();
        for (SegmentDef s : all) {
            out.add(toView(s, scanMembers(s, LIST_MEMBER_LIMIT, null, tagNames)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public List<MemberView> members(Long id) {
        SegmentDef s = segmentRepo.findById(id).orElseThrow(() -> new NotFound("分群不存在: " + id));
        return scanMembers(s, MEMBER_LIMIT, null, loadTagNames());
    }

    // -------------------- CRUD --------------------

    @Transactional
    public synchronized SegmentView create(CreateCmd cmd, String actor) {
        validate(cmd.name(), cmd.type(), cmd.conditions());
        if (cmd.clientToken() != null && !cmd.clientToken().isBlank()) {
            Optional<SegmentDef> dup = segmentRepo.findByClientToken(cmd.clientToken());
            if (dup.isPresent()) {
                return toView(dup.get(), scanMembers(dup.get(), LIST_MEMBER_LIMIT, null, loadTagNames()));
            }
        }
        if (segmentRepo.findByNameAndStore(cmd.name().trim(), cmd.storeCode()).isPresent()) {
            throw new Conflict("同名分群已存在：" + cmd.name().trim());
        }
        SegmentDef s = new SegmentDef();
        s.setSegmentNo(nextSegmentNo());
        s.setName(cmd.name().trim());
        s.setType(cmd.type());
        s.setAiStatus("RULE");
        s.setConditions(writeConds(cmd.conditions()));
        s.setRuleSummary(summaryOf(cmd.conditions()));
        s.setStoreCode(cmd.storeCode());
        s.setClientToken(cmd.clientToken());
        segmentRepo.save(s);
        refresh(s.getId());
        audit.record("SEGMENT", s.getSegmentNo(), actor, "CREATE",
                "{\"name\":\"" + s.getName() + "\",\"type\":\"" + s.getType() + "\"}");
        return toView(segmentRepo.findById(s.getId()).orElseThrow(),
                scanMembers(s, LIST_MEMBER_LIMIT, null, loadTagNames()));
    }

    @Transactional
    public SegmentView update(Long id, UpdateCmd cmd, String actor) {
        SegmentDef s = segmentRepo.findById(id).orElseThrow(() -> new NotFound("分群不存在: " + id));
        validate(cmd.name(), cmd.type(), cmd.conditions());
        s.setName(cmd.name().trim());
        s.setType(cmd.type());
        s.setConditions(writeConds(cmd.conditions()));
        s.setRuleSummary(summaryOf(cmd.conditions()));
        segmentRepo.save(s);
        refresh(id);
        audit.record("SEGMENT", s.getSegmentNo(), actor, "UPDATE", "{\"name\":\"" + s.getName() + "\"}");
        return toView(segmentRepo.findById(id).orElseThrow(),
                scanMembers(s, LIST_MEMBER_LIMIT, null, loadTagNames()));
    }

    @Transactional
    public void delete(Long id, String actor) {
        SegmentDef s = segmentRepo.findById(id).orElseThrow(() -> new NotFound("分群不存在: " + id));
        segmentRepo.delete(s);
        audit.record("SEGMENT", s.getSegmentNo(), actor, "DELETE", "{\"name\":\"" + s.getName() + "\"}");
    }

    // -------------------- RULE 引擎 --------------------

    /** 重算命中数/占比并回写快照。 */
    @Transactional
    public RefreshResult refresh(Long id) {
        SegmentDef s = segmentRepo.findById(id).orElseThrow(() -> new NotFound("分群不存在: " + id));
        int[] counter = new int[2]; // [0]=scanned [1]=matched
        scanMembers(s, MEMBER_LIMIT, counter, loadTagNames());
        long total = customerRepo.count();
        BigDecimal pct = total == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(counter[1] * 100.0 / total).setScale(2, RoundingMode.HALF_UP);
        s.setCustomerCount(counter[1]);
        s.setSharePct(pct);
        segmentRepo.save(s);
        return new RefreshResult(id, counter[1], pct, counter[0]);
    }

    /**
     * 分页扫描客户，命中收集成员（limit 截断）；counter 非空时累计 scanned/matched 全量计数
     * （成员截断但计数完整——refresh 与列表共用同一趟扫描，计数跑满全表）。
     */
    private List<MemberView> scanMembers(SegmentDef s, int limit, int[] counter, Map<String, Set<String>> tagNames) {
        ArrayNode conds = parseConds(s.getConditions());
        List<MemberView> out = new ArrayList<>();
        Pageable page = PageRequest.of(0, 200);
        while (true) {
            Page<Customer> pg = customerRepo.findAll(page);
            if (pg.isEmpty()) break;
            for (Customer c : pg.getContent()) {
                if (counter != null) counter[0]++;
                List<String> hits = new ArrayList<>();
                if (matchesAll(conds, c, tagNames.getOrDefault(c.getCustomerId(), Set.of()), hits)) {
                    if (counter != null) counter[1]++;
                    if (out.size() < limit) {
                        out.add(new MemberView(c.getCustomerId(), c.getName(), c.getLevel(),
                                // customer 无 last_visit_at 列：以建档日近似「最后到店」（口径见 V56 注释/04 登记）
                                c.getCreatedAt() == null ? "" : c.getCreatedAt().toString(), hits));
                    }
                }
            }
            if (!pg.hasNext()) break;
            page = pg.nextPageable();
        }
        return out;
    }

    /** AND 组合：全部条件命中才算命中；hits 收集命中条件 label（成员「命中点」列直展）。 */
    private boolean matchesAll(ArrayNode conds, Customer c, Set<String> tagNames, List<String> hits) {
        if (conds == null || conds.isEmpty()) return false;
        for (JsonNode cond : conds) {
            if (!matchOne(cond, c, tagNames)) return false;
            hits.add(cond.path("label").asText(cond.path("kind").asText()));
        }
        return true;
    }

    /** 单条件求值（字段空值零值兜底；非法参数按不命中，不中断整批）。 */
    private boolean matchOne(JsonNode cond, Customer c, Set<String> tagNames) {
        String kind = cond.path("kind").asText("");
        try {
            return switch (kind) {
                case "DORMANT_DAYS" -> "沉睡".equals(c.getStatus());
                case "VISIT_IN_DAYS" -> (c.getVisitCount() == null ? 0 : c.getVisitCount())
                        >= cond.path("times").asInt(1);
                case "SPEND_RANGE" -> {
                    BigDecimal spend = c.getTotalSpend() == null ? BigDecimal.ZERO : c.getTotalSpend();
                    BigDecimal min = new BigDecimal(cond.path("min").asText("0"));
                    BigDecimal max = new BigDecimal(cond.path("max").asText("99999999"));
                    yield spend.compareTo(min) >= 0 && spend.compareTo(max) <= 0;
                }
                case "LEVEL_GTE" -> LEVEL_ORDER.getOrDefault(c.getLevel(), 0)
                        >= LEVEL_ORDER.getOrDefault(cond.path("level").asText(""), 0);
                case "TAG_ANY" -> {
                    Set<String> want = new HashSet<>();
                    cond.path("tags").forEach(t -> want.add(t.asText()));
                    boolean hit = false;
                    for (String t : tagNames) { if (want.contains(t)) { hit = true; break; } }
                    yield hit;
                }
                case "CREATED_IN_DAYS" -> c.getCreatedAt() != null && c.getCreatedAt()
                        .isAfter(OffsetDateTime.now().minusDays(cond.path("days").asInt(31)));
                default -> false;
            };
        } catch (RuntimeException ex) {
            return false;
        }
    }

    // -------------------- 联动：跟进任务 / 回写标签 / 画像同步 --------------------

    /** 一键建跟进任务：命中客户（≤50）逐个下发 marketing internal follow-tasks（软降级不阻断）。 */
    @Transactional
    public FollowTaskResult followTasks(Long id, String actor) {
        SegmentDef s = segmentRepo.findById(id).orElseThrow(() -> new NotFound("分群不存在: " + id));
        int[] counter = new int[2];
        List<MemberView> hits = scanMembers(s, FOLLOW_TASK_LIMIT, counter, loadTagNames());
        int created = 0;
        String day = LocalDate.now().toString();
        for (MemberView m : hits) {
            boolean ok = followTaskClient.createFollowTask("SEGMENT", s.getSegmentNo(), m.id(), m.name(),
                    m.level(), "PHONE", "分群「" + s.getName() + "」批量跟进（命中：" + String.join("、", m.matched()) + "）",
                    "MEDIUM", null, "SEG:" + s.getSegmentNo() + ":" + m.id() + ":" + day);
            if (ok) created++;
        }
        audit.record("SEGMENT", s.getSegmentNo(), actor, "FOLLOW_TASKS",
                "{\"matched\":" + counter[1] + ",\"created\":" + created + "}");
        return new FollowTaskResult(id, counter[1], created);
    }

    /** 命中回写标签：以分群名建/取标签（行为类），对全部命中客户幂等打标（D3-3 回推通道）。 */
    @Transactional
    public ApplyTagsResult applyTags(Long id, String actor) {
        SegmentDef s = segmentRepo.findById(id).orElseThrow(() -> new NotFound("分群不存在: " + id));
        CustomerTag tag = ensureTag(s.getName());
        int[] counter = new int[2];
        List<MemberView> hits = scanMembers(s, MEMBER_LIMIT, counter, loadTagNames());
        int assigned = 0;
        for (MemberView m : hits) {
            if (tagRelRepo.existsByCustomerIdAndTagId(m.id(), tag.getTagId())) continue;
            try {
                customerService.assignTag(m.id(), tag.getTagId());
                assigned++;
            } catch (RuntimeException ignored) {
                // 单客户异常跳过，不中断整批（照 TagAutoRuleService 先例）
            }
        }
        audit.record("SEGMENT", s.getSegmentNo(), actor, "APPLY_TAGS",
                "{\"tagId\":\"" + tag.getTagId() + "\",\"assigned\":" + assigned + "}");
        return new ApplyTagsResult(id, tag.getTagId(), counter[1], assigned);
    }

    /**
     * 画像 sync-profile（D3-3 兑现）：画像「应用到分群」后前端中转调用——
     * 1) 画像 tags 回写客户真实标签（tag_pool 建/取 + assignTag 幂等）；
     * 2) 画像 groups 逐个 upsert AI 分群（conditions=[TAG_ANY(tags)]，RULE 引擎自闭环可算）；
     * 3) 同 profileId 重放幂等：已落群更新溯源字段，不重复建行。
     */
    @Transactional
    public synchronized SyncProfileResult syncProfile(SyncProfileCmd cmd, String actor) {
        if (cmd.customerId() == null || cmd.customerId().isBlank()) throw new BadReq("customerId 必填");
        if (!customerRepo.existsById(cmd.customerId())) throw new NotFound("客户不存在: " + cmd.customerId());
        List<String> tags = cmd.tags() == null ? List.of() : cmd.tags();
        List<String> groups = cmd.groups() == null ? List.of() : cmd.groups();

        int tagsAssigned = 0;
        for (String t : tags) {
            String name = t == null ? "" : t.trim();
            if (name.isEmpty()) continue;
            CustomerTag tag = ensureTag(name);
            if (tagRelRepo.existsByCustomerIdAndTagId(cmd.customerId(), tag.getTagId())) continue;
            try {
                customerService.assignTag(cmd.customerId(), tag.getTagId());
                tagsAssigned++;
            } catch (RuntimeException ignored) {
            }
        }

        List<String> created = new ArrayList<>();
        List<String> existed = new ArrayList<>();
        for (String g : groups) {
            String name = g == null ? "" : g.trim();
            if (name.isEmpty()) continue;
            Optional<SegmentDef> dup = segmentRepo.findByNameAndStore(name, null);
            if (dup.isPresent()) {
                SegmentDef s = dup.get();
                if ("AI".equals(s.getAiStatus())) {
                    s.setSourceProfileId(cmd.profileId());
                    segmentRepo.save(s);
                }
                existed.add(name);
                continue;
            }
            SegmentDef s = new SegmentDef();
            s.setSegmentNo(nextSegmentNo());
            s.setName(name);
            s.setType(mapGroupType(name));
            s.setAiStatus("AI");
            String label = "画像标签：" + String.join("、", tags);
            s.setConditions("[{\"kind\":\"TAG_ANY\",\"tags\":" + toJsonArray(tags)
                    + ",\"label\":\"" + label.replace("\"", "'") + "\"}]");
            s.setRuleSummary(label);
            s.setAiSuggestion("画像群体「" + name + "」：建议结合画像标签做精准触达（标签：" + String.join("、", tags) + "）。");
            s.setSourceProfileId(cmd.profileId());
            segmentRepo.save(s);
            refresh(s.getId());
            created.add(name);
        }
        audit.record("SEGMENT", cmd.customerId(), actor, "SYNC_PROFILE",
                "{\"profileId\":" + cmd.profileId() + ",\"groupsCreated\":" + created.size()
                        + ",\"tagsAssigned\":" + tagsAssigned + "}");
        return new SyncProfileResult(tagsAssigned, created, existed);
    }

    // -------------------- 内部工具 --------------------

    private void validate(String name, String type, List<CondInput> conds) {
        if (name == null || name.isBlank() || name.trim().length() > 64) throw new BadReq("分群名称必填且 ≤ 64 字");
        if (type == null || !TYPES.contains(type)) throw new BadReq("分群类型非法：" + type);
        if (conds == null || conds.isEmpty()) throw new BadReq("至少 1 条规则条件");
        for (CondInput c : conds) {
            if (c == null || c.kind() == null || !CONDITION_KINDS.contains(c.kind())) {
                throw new BadReq("条件类型非法：" + (c == null ? null : c.kind()));
            }
            if (c.label() == null || c.label().isBlank()) throw new BadReq("条件文案必填");
        }
    }

    private String writeConds(List<CondInput> conds) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CondInput c : conds) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("kind", c.kind());
            if (c.days() != null) m.put("days", c.days());
            if (c.times() != null) m.put("times", c.times());
            if (c.min() != null) m.put("min", c.min());
            if (c.max() != null) m.put("max", c.max());
            if (c.level() != null) m.put("level", c.level());
            if (c.tags() != null) m.put("tags", c.tags());
            m.put("label", c.label());
            out.add(m);
        }
        try {
            return om.writeValueAsString(out);
        } catch (Exception e) {
            throw new BadReq("条件序列化失败");
        }
    }

    private String summaryOf(List<CondInput> conds) {
        String joined = String.join("、", conds.stream().map(CondInput::label).toList());
        return joined.length() > 200 ? joined.substring(0, 200) : joined;
    }

    private ArrayNode parseConds(String json) {
        try {
            JsonNode n = om.readTree(json == null || json.isBlank() ? "[]" : json);
            return n instanceof ArrayNode a ? a : om.createArrayNode();
        } catch (Exception e) {
            return om.createArrayNode();
        }
    }

    private List<String> condLabels(String json) {
        List<String> out = new ArrayList<>();
        for (JsonNode cond : parseConds(json)) {
            out.add(cond.path("label").asText(cond.path("kind").asText()));
        }
        return out;
    }

    private SegmentView toView(SegmentDef s, List<MemberView> members) {
        return new SegmentView(s.getId(), s.getSegmentNo(), s.getName(), s.getType(), s.getAiStatus(),
                condLabels(s.getConditions()), s.getRuleSummary(),
                s.getCustomerCount() == null ? 0 : s.getCustomerCount(),
                s.getSharePct() == null ? BigDecimal.ZERO : s.getSharePct(),
                s.getAiSuggestion(), s.getUpdatedAt() == null ? "" : s.getUpdatedAt().toString(), members);
    }

    /** 全量客户标签名预载（rel 一次 findAll + 定义一次 findAll 组映射，避免扫描期 N+1）。 */
    private Map<String, Set<String>> loadTagNames() {
        Map<String, String> tagNameById = new HashMap<>();
        for (CustomerTag t : tagRepo.findAll()) tagNameById.put(t.getTagId(), t.getTagName());
        Map<String, Set<String>> out = new HashMap<>();
        for (CustomerTagRel r : tagRelRepo.findAll()) {
            String name = tagNameById.get(r.getTagId());
            if (name == null) continue;
            out.computeIfAbsent(r.getCustomerId(), k -> new HashSet<>()).add(name);
        }
        return out;
    }

    /** 建/取标签（行为类；标签名 ≤32 截断，TG 号池 synchronized 防重照 CustomerService 先例）。 */
    private synchronized CustomerTag ensureTag(String rawName) {
        String name = rawName.trim();
        if (name.length() > 32) name = name.substring(0, 32);
        for (CustomerTag t : tagRepo.findAll()) {
            if (name.equals(t.getTagName())) return t;
        }
        String max = tagRepo.maxTgId();
        int seq = 0;
        if (max != null && max.startsWith("TG")) {
            try {
                seq = Integer.parseInt(max.substring(2));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        CustomerTag t = new CustomerTag();
        t.setTagId(String.format("TG%03d", seq + 1));
        t.setTagName(name);
        t.setCategory("行为");
        return tagRepo.save(t);
    }

    /** AI 群名 → 六类映射（关键词兜底 HIGH_POTENTIAL）。 */
    private String mapGroupType(String name) {
        if (name.contains("沉睡")) return "DORMANT";
        if (name.contains("流失")) return "CHURN_RISK";
        if (name.contains("高价值") || name.contains("VIP") || name.contains("vip")) return "HIGH_VALUE";
        if (name.contains("价格") || name.contains("敏感")) return "PRICE_SENSITIVE";
        if (name.contains("新")) return "NEW";
        return "HIGH_POTENTIAL";
    }

    private String toJsonArray(List<String> items) {
        try {
            return om.writeValueAsString(items);
        } catch (Exception e) {
            return "[]";
        }
    }

    /** 号池：SG#### 基于库内最大号递增（种子 SG0001-0003 占号段；synchronized 防并发重号）。 */
    private String nextSegmentNo() {
        String max = segmentRepo.maxSegmentNo();
        int seq = 0;
        if (max != null && max.startsWith("SG")) {
            try {
                seq = Integer.parseInt(max.substring(2));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("SG%04d", seq + 1);
    }
}
