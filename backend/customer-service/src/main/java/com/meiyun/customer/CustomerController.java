package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.customer.search.CustomerSearchEventAdminService;
import com.meiyun.customer.search.CustomerSearchService;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/customer")
public class CustomerController {

    private final CustomerService service;
    private final CustomerRepository customerRepo;
    private final MemberCardRepository cardRepo;
    private final PointsLedgerRepository ledgerRepo;
    private final PointsPoolRepository poolRepo;
    private final CustomerTagRepository tagRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerSearchService searchService;
    private final CustomerSearchEventAdminService searchEventAdmin;

    @Autowired
    private AuditRecorder audit;

    public CustomerController(CustomerService service, CustomerRepository customerRepo,
                              MemberCardRepository cardRepo,
                              PointsLedgerRepository ledgerRepo, PointsPoolRepository poolRepo,
                              CustomerTagRepository tagRepo, CustomerTagRelRepository tagRelRepo,
                              CustomerSearchService searchService,
                              CustomerSearchEventAdminService searchEventAdmin) {
        this.service = service;
        this.customerRepo = customerRepo;
        this.cardRepo = cardRepo;
        this.ledgerRepo = ledgerRepo;
        this.poolRepo = poolRepo;
        this.tagRepo = tagRepo;
        this.tagRelRepo = tagRelRepo;
        this.searchService = searchService;
        this.searchEventAdmin = searchEventAdmin;
    }

    // ---- 客户主数据（分页 + 过滤 + 标签） ----
    @GetMapping
    @RequirePerm("customer:view")
    public Page<CustomerRowDTO> list(
            @PageableDefault(size = 100, sort = "createdAt") Pageable pageable,
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tagId) {
        return service.listRows(pageable, storeCode, level, status, channel, keyword, tagId);
    }

    @GetMapping("/{id}")
    @RequirePerm("customer:view")
    public CustomerDetailDTO get(@PathVariable String id) {
        return service.getDetail(id);
    }

    /** 新建客户：编号生成 / 字段校验 / 撞单 / 归属上下文均由 service 权威处理；落 CUSTOMER/CREATE 审计。 */
    @PostMapping
    @RequirePerm("customer:create")
    public Customer create(@RequestBody Customer c) {
        Customer created = service.create(c);
        audit.record("CUSTOMER", created.getCustomerId(), DataScope.currentActor(), "CREATE",
                payload(created));
        return created;
    }

    /** 审计 payload 必须是合法 JSON（audit_log.payload 为 jsonb），手机号不在审计中留明文。 */
    private String payload(Customer c) {
        return "{\"customerId\":\"" + esc(c.getCustomerId()) + "\",\"name\":\"" + esc(c.getName())
                + "\",\"gender\":\"" + esc(c.getGender()) + "\",\"level\":\"" + esc(c.getLevel())
                + "\",\"channel\":\"" + esc(c.getChannel()) + "\",\"storeCode\":\"" + esc(c.getStoreCode())
                + "\",\"ownerStaffId\":\"" + esc(c.getOwnerStaffId())
                + "\",\"skinType\":\"" + esc(c.getSkinType())
                + "\",\"concerns\":" + jsonList(c.getConcerns())
                + ",\"allergyNone\":" + Boolean.TRUE.equals(c.getAllergyNone())
                + ",\"allergies\":" + jsonList(c.getAllergies())
                + ",\"intentProjects\":" + jsonList(c.getIntentProjects())
                + ",\"intentLevel\":\"" + esc(c.getIntentLevel())
                + "\",\"budget\":\"" + esc(c.getBudget()) + "\"}";
    }

    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // ---- 会员等级：读模型（实时人数）/ 阈值权益配置 / 升降级规则 / 手工调级 / 按消费自动升级（全程 LEVEL 审计） ----

    /** 等级读模型：五级 DTO（人数实时 count customer，不暴露 cnt 历史聚合假数据）。 */
    @GetMapping("/member-levels")
    @RequirePerm("level:view")
    public List<MemberLevelDTO> levels() {
        return service.listLevelDTOs();
    }

    /**
     * 更新等级阈值/权益：service 校验 + 同态短路（changed=false 不重复审计）；
     * 落 LEVEL/UPDATE，payload 记录 before/after 全动作。精确路径优先于 /{id} 匹配。
     */
    @PutMapping("/member-levels/{level}")
    @RequirePerm("level:edit")
    public MemberLevelDTO updateLevel(@PathVariable String level, @RequestBody LevelConfigReq req) {
        CustomerService.LevelConfigResult result = service.updateLevelConfig(
                level, req == null ? null : req.upgradeThreshold(), req == null ? null : req.benefits());
        if (result.changed()) {
            audit.record("LEVEL", level, DataScope.currentActor(), "UPDATE",
                    "{\"level\":\"" + esc(level) + "\",\"name\":\"" + esc(result.dto().name())
                            + "\",\"before\":{\"upgradeThreshold\":" + result.oldThreshold()
                            + ",\"benefits\":" + jsonList(result.oldBenefits())
                            + "},\"after\":{\"upgradeThreshold\":" + result.dto().upgradeThreshold()
                            + ",\"benefits\":" + jsonList(result.dto().benefits()) + "}}");
        }
        return result.dto();
    }

    /** 按累计消费批量自动升级（手动触发；只升不降），有实际升级时落单条 LEVEL/AUTO_UPGRADE 汇总审计。 */
    @PostMapping("/member-levels/auto-upgrade")
    @RequirePerm("level:edit")
    public CustomerService.AutoUpgradeResult autoUpgrade() {
        CustomerService.AutoUpgradeResult result = service.autoUpgrade();
        if (result.upgraded() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"upgraded\":").append(result.upgraded()).append(",\"items\":[");
            for (int i = 0; i < result.items().size(); i++) {
                CustomerService.UpgradeItem it = result.items().get(i);
                if (i > 0) sb.append(',');
                sb.append("{\"customerId\":\"").append(esc(it.customerId()))
                        .append("\",\"name\":\"").append(esc(it.name()))
                        .append("\",\"fromLevel\":\"").append(esc(it.fromLevel()))
                        .append("\",\"toLevel\":\"").append(esc(it.toLevel()))
                        .append("\",\"totalSpend\":").append(it.totalSpend()).append('}');
            }
            sb.append("]}");
            audit.record("LEVEL", "AUTO-UPGRADE", DataScope.currentActor(), "AUTO_UPGRADE", sb.toString());
        }
        return result;
    }

    /** 升降级规则读模型：未配置时 service 返回默认值（不落库）。 */
    @GetMapping("/level-rule")
    @RequirePerm("level:view")
    public LevelRuleConfig levelRule() {
        return service.getLevelRule();
    }

    /** 保存升降级规则：同态短路不审计；变更落 LEVEL/RULE_SAVE（bizId=RULE-1，对齐积分规则 MALL/RULE-1）。 */
    @PutMapping("/level-rule")
    @RequirePerm("level:edit")
    public LevelRuleConfig saveLevelRule(@RequestBody LevelRuleReq req) {
        CustomerService.RuleSaveResult result = service.saveLevelRule(
                req == null ? null : req.calcPeriod(),
                req == null ? null : req.downgradeProtectMonths(),
                req == null ? null : req.autoUpgrade(),
                req == null ? null : req.pointsMultiplier());
        LevelRuleConfig r = result.rule();
        if (result.changed()) {
            audit.record("LEVEL", "RULE-1", DataScope.currentActor(), "RULE_SAVE",
                    "{\"calcPeriod\":\"" + esc(r.getCalcPeriod())
                            + "\",\"downgradeProtectMonths\":" + r.getDowngradeProtectMonths()
                            + ",\"autoUpgrade\":" + r.getAutoUpgrade()
                            + ",\"pointsMultiplier\":" + r.getPointsMultiplier() + "}");
        }
        return r;
    }

    /**
     * 手工调级：requireReadable 强制数据域（不存在/越权统一 404）；目标等级白名单 + 原因校验由 service 处理；
     * 同值重放短路不审计；变更落 LEVEL/ADJUST（bizId=客户号）。
     */
    @PostMapping("/{id}/level")
    @RequirePerm("level:edit")
    public Customer changeLevel(@PathVariable String id, @RequestBody LevelAdjustReq req) {
        Customer c = requireReadable(id);
        CustomerService.LevelAdjustResult result = service.adjustCustomerLevel(
                c, req == null ? null : req.targetLevel(), req == null ? null : req.reason());
        if (result.changed()) {
            audit.record("LEVEL", id, DataScope.currentActor(), "ADJUST",
                    "{\"customerId\":\"" + esc(id) + "\",\"name\":\"" + esc(c.getName())
                            + "\",\"fromLevel\":\"" + esc(result.fromLevel())
                            + "\",\"toLevel\":\"" + esc(result.toLevel())
                            + "\",\"reason\":\"" + esc(req.reason()) + "\"}");
        }
        return result.customer();
    }

    /** 字符串清单序列化为 JSON 数组（审计 payload 内嵌 benefits 用；元素经 esc 转义，不留 JSON 注入）。 */
    private String jsonList(List<String> list) {
        if (list == null || list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(esc(list.get(i))).append('"');
        }
        return sb.append(']').toString();
    }

    // ---- 会员卡项 ----
    @GetMapping("/{id}/cards")
    @RequirePerm("customer:view")
    public List<MemberCard> cards(@PathVariable String id,
                                  @RequestParam(defaultValue = "false") boolean activeOnly) {
        requireReadable(id);
        return service.listCards(id, activeOnly);
    }

    // ---- 积分：台账（分页倒序） + 池 DTO + 人工调分（幂等 + 审计） ----
    @GetMapping("/{id}/points")
    @RequirePerm("points:view")
    public Page<PointsLedger> pointsLog(@PathVariable String id,
                                        @PageableDefault(size = 50) Pageable pageable) {
        requireReadable(id);
        return ledgerRepo.findByCustomerIdOrderByLedgerIdDesc(id, pageable);
    }

    @PostMapping("/{id}/points")
    @RequirePerm("points:edit")
    public PointsLedger changePoints(@PathVariable String id, @RequestBody PointsChangeReq req) {
        requireReadable(id);
        CustomerService.AdjustResult result = service.adjustPoints(id, req.changeAmt(), req.reason(), req.clientToken());
        PointsLedger saved = result.ledger();
        if (result.created()) {
            audit.record("POINTS", String.valueOf(saved.getLedgerId()), DataScope.currentActor(),
                    "MANUAL_ADJUST", "{\"customerId\":\"" + esc(id)
                            + "\",\"changeAmt\":" + saved.getChangeAmt()
                            + ",\"balanceAfter\":" + saved.getBalanceAfter()
                            + ",\"reason\":\"" + esc(saved.getReason()) + "\"}");
        }
        return saved;
    }

    /** 积分池读模型：只暴露四个统计口径，不直接序列化实体。 */
    @GetMapping("/points-pool")
    @RequirePerm({"points:view", "customer:view"})
    public PointsPoolDTO pointsPool() {
        PointsPool p = poolRepo.findById(1)
                .orElseThrow(() -> new CustomerService.NotFound("积分池未初始化"));
        return new PointsPoolDTO(
                p.getTotalIssued() == null ? 0L : p.getTotalIssued(),
                p.getGainedMonth() == null ? 0L : p.getGainedMonth(),
                p.getRedeemedMonth() == null ? 0L : p.getRedeemedMonth(),
                p.getExpiring90d() == null ? 0L : p.getExpiring90d());
    }

    // ---- 标签：定义 CRUD（带覆盖人数读模型） + 客户打标/删标（防重 + 全程审计） ----
    @GetMapping("/tags")
    @RequirePerm({"tag:view", "customer:view"})
    public List<TagStatDTO> tags() { return service.listTagStats(); }

    /** 标签覆盖汇总：标签总数/去重覆盖客户数/累计打标人次/人均标签数（精确路径优先于 /{id} 匹配）。 */
    @GetMapping("/tags/overview")
    @RequirePerm({"tag:view", "customer:view"})
    public TagOverviewDTO tagOverview() { return service.listTagOverview(); }

    /** 新建标签：五分类白名单 / 重名 409 / TG### 编号均由 service 权威处理；落 TAG/CREATE 审计。 */
    @PostMapping("/tags")
    @RequirePerm("tag:edit")
    public CustomerTag createTag(@RequestBody TagUpsertReq req) {
        CustomerTag t = service.createTag(req == null ? null : req.tagName(),
                req == null ? null : req.category());
        audit.record("TAG", t.getTagId(), DataScope.currentActor(), "CREATE",
                "{\"tagId\":\"" + esc(t.getTagId()) + "\",\"tagName\":\"" + esc(t.getTagName())
                        + "\",\"category\":\"" + esc(t.getCategory()) + "\"}");
        return t;
    }

    /** 改名/改分类：落 TAG/UPDATE 审计（记录变更后值）。 */
    @PutMapping("/tags/{tagId}")
    @RequirePerm("tag:edit")
    public CustomerTag updateTag(@PathVariable String tagId, @RequestBody TagUpsertReq req) {
        CustomerTag t = service.updateTag(tagId, req == null ? null : req.tagName(),
                req == null ? null : req.category());
        audit.record("TAG", t.getTagId(), DataScope.currentActor(), "UPDATE",
                "{\"tagId\":\"" + esc(t.getTagId()) + "\",\"tagName\":\"" + esc(t.getTagName())
                        + "\",\"category\":\"" + esc(t.getCategory()) + "\"}");
        return t;
    }

    /** 删除标签：service 先级联解绑全部客户关联再删定义；落 TAG/DELETE 审计（带解绑客户数）。 */
    @DeleteMapping("/tags/{tagId}")
    @RequirePerm("tag:edit")
    public Map<String, Object> deleteTag(@PathVariable String tagId) {
        CustomerTag t = tagRepo.findById(tagId)
                .orElseThrow(() -> new CustomerService.NotFound("标签不存在: " + tagId));
        int removed = service.deleteTag(tagId);
        audit.record("TAG", tagId, DataScope.currentActor(), "DELETE",
                "{\"tagId\":\"" + esc(tagId) + "\",\"tagName\":\"" + esc(t.getTagName())
                        + "\",\"unassignedCustomers\":" + removed + "}");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("deleted", tagId);
        m.put("unassignedCustomers", removed);
        return m;
    }

    @GetMapping("/{id}/tags")
    @RequirePerm({"tag:view", "customer:view"})
    public List<CustomerTagRel> customerTags(@PathVariable String id) {
        requireReadable(id);
        return tagRelRepo.findByCustomerId(id);
    }

    /** 打标：requireReadable 强制数据域（不存在/越权统一 404）；重复打标 service 抛 409；落 TAG/ASSIGN 审计。 */
    @PostMapping("/{id}/tags/{tagId}")
    @RequirePerm("tag:edit")
    public CustomerTagRel addTag(@PathVariable String id, @PathVariable String tagId) {
        requireReadable(id);
        CustomerTagRel rel = service.assignTag(id, tagId);
        audit.record("TAG", id, DataScope.currentActor(), "ASSIGN",
                "{\"customerId\":\"" + esc(id) + "\",\"tagId\":\"" + esc(tagId) + "\"}");
        return rel;
    }

    /** 删标（客户解绑单个标签）：落 TAG/UNASSIGN 审计；关联不存在 404。 */
    @DeleteMapping("/{id}/tags/{tagId}")
    @RequirePerm("tag:edit")
    public Map<String, Object> removeTag(@PathVariable String id, @PathVariable String tagId) {
        requireReadable(id);
        service.unassignTag(id, tagId);
        audit.record("TAG", id, DataScope.currentActor(), "UNASSIGN",
                "{\"customerId\":\"" + esc(id) + "\",\"tagId\":\"" + esc(tagId) + "\"}");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("customerId", id);
        m.put("tagId", tagId);
        m.put("removed", true);
        return m;
    }

    /**
     * 批量客户名解析：GET /api/customer/name-map?ids=SC001&ids=SC002 → {"SC001":"王女士"}。
     * 供交易域（预约/订单列表）服务间调用富化客户名，缺失的 id 不留 key。
     * 客户真实姓名属敏感字段：仅服务间内部身份（X-Internal-Token）可调用，
     * 普通登录人即便带 token 也无 internal:name-map 权限 → 403。
     * 注：精确路径 /name-map 优先于 /{id} 匹配，不会被当成客户号。
     */
    @GetMapping("/name-map")
    @RequirePerm("internal:name-map")
    public Map<String, String> nameMap(@RequestParam("ids") List<String> ids) {
        List<String> distinct = ids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, String> m = new LinkedHashMap<>();
        if (distinct.isEmpty()) return m;
        for (Customer c : customerRepo.findAllById(distinct)) {
            if (c.getName() != null) m.put(c.getCustomerId(), c.getName());
        }
        return m;
    }

    /**
     * 批量客户掩码手机号解析：GET /api/customer/phone-map?ids=SC001 → {"SC001":"138****2046"}。
     * 手机号属敏感字段：无条件返回掩码（不开放明文），且仅服务间内部身份（X-Internal-Token）可调用；
     * 普通登录人即便带 token 也无 internal:phone-map 权限 → 403。缺失的 id 不留 key。
     */
    @GetMapping("/phone-map")
    @RequirePerm("internal:phone-map")
    public Map<String, String> phoneMap(@RequestParam("ids") List<String> ids) {
        List<String> distinct = ids.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, String> m = new LinkedHashMap<>();
        if (distinct.isEmpty()) return m;
        for (Customer c : customerRepo.findAllById(distinct)) {
            if (c.getPhone() != null && !c.getPhone().isBlank()) {
                m.put(c.getCustomerId(), CustomerService.maskPhone(c.getPhone(), false));
            }
        }
        return m;
    }

    // ---- 客户全文检索（ES，ES 不可用降级 DB 内存过滤） ----
    @GetMapping("/search")
    @RequirePerm("customer:view")
    public List<Customer> search(@RequestParam String q) {
        List<String> ids = searchService.search(q);
        return customerRepo.findAllById(ids).stream()
                .filter(c -> DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId()))
                .toList();
    }

    /** 客户数据域断言：不存在或越权统一 404，不泄露数据是否存在。 */
    private Customer requireReadable(String id) {
        Customer c = customerRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        return c;
    }

    /**
     * 全量重建 ES 客户索引（运维/初始化用）。B32 起权限由 aiAdmin:edit 收口为
     * customer:search:admin（检索域专属运维码），并补 CUSTOMER_SEARCH_EVENT/REINDEX 审计。
     */
    @PostMapping("/search/reindex")
    @RequirePerm("customer:search:admin")
    public Map<String, Object> reindex() {
        int n = searchService.reindexAll();
        searchEventAdmin.auditReindex(n, DataScope.currentActor());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("indexed", n);
        m.put("index", "meiyun-customer");
        return m;
    }

    /** 人工调分请求：clientToken 为客户端生成的幂等键（同键重放返回既有流水，不重复加减分）。 */
    public record PointsChangeReq(Long changeAmt, String reason, String clientToken) {}

    /** 标签新建/改名请求体：名称 + 五分类之一（白名单由 service 权威校验）。 */
    public record TagUpsertReq(String tagName, String category) {}

    /** 积分池读模型：累计发放 / 本月获得 / 本月核销 / 90 天内到期。 */
    public record PointsPoolDTO(long totalIssued, long gainedMonth, long redeemedMonth, long expiring90d) {}

    /** 等级阈值/权益更新请求体：阈值必填非负（普通固定 0），权益可空（空数组=无权益）。 */
    public record LevelConfigReq(BigDecimal upgradeThreshold, List<String> benefits) {}

    /** 升降级规则保存请求体：四字段对齐前端 LevelRule（缺省由 service 置默认并校验区间）。 */
    public record LevelRuleReq(String calcPeriod, Integer downgradeProtectMonths,
                               Boolean autoUpgrade, BigDecimal pointsMultiplier) {}

    /** 手工调级请求体：目标等级为五级中文短名（白名单由 service 校验）；原因必填 ≤64 字。 */
    public record LevelAdjustReq(String targetLevel, String reason) {}
}
