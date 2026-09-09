package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.SecurityContext;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class CustomerService {

    /** 性别取值白名单（对齐 customer gender CHECK 约束）。 */
    private static final Set<String> GENDERS = Set.of("女", "男", "其他");
    /** 获客渠道白名单（对齐真实库 7 码 + CUSTOMER_SOURCE 字典）。 */
    private static final Set<String> CHANNELS =
            Set.of("WALK_IN", "REFERRAL", "WECHAT", "DOUYIN", "XIAOHONGSHU", "MEITUAN", "OTHER");
    /** 大陆手机号：1 开头、第二位 3-9、共 11 位数字。 */
    private static final Pattern PHONE_RE = Pattern.compile("^1[3-9]\\d{9}$");
    /** 标签分类白名单（对齐 customer_tag.category CHECK 五分类）。 */
    private static final Set<String> TAG_CATEGORIES = Set.of("消费", "肤质", "行为", "价值", "医疗");

    /** 等级序：五级中文短名主键 → sortNo（member_level.sort_no 回填前的兜底口径）。 */
    private static final Map<String, Integer> LEVEL_ORDER = Map.of(
            "普通", 1, "银卡", 2, "金卡", 3, "钻石", 4, "黑卡", 5);
    /** 等级英文码兜底（tier 列回填前）。 */
    private static final Map<String, String> LEVEL_TIER = Map.of(
            "普通", "NORMAL", "银卡", "SILVER", "金卡", "GOLD", "钻石", "DIAMOND", "黑卡", "BLACK");
    /** 等级主色兜底（color 列回填前，对齐前端 COLOR_BY_TIER；NORMAL/SILVER 同为 #8B5CF6）。 */
    private static final Map<String, String> LEVEL_COLOR = Map.of(
            "普通", "#8B5CF6", "银卡", "#8B5CF6", "金卡", "#F59E0B", "钻石", "#6366F1", "黑卡", "#10B981");
    /** 升级累计消费阈值兜底（0/5000/20000/50000/100000，upgrade_threshold 回填前）。 */
    private static final Map<String, BigDecimal> LEVEL_THRESHOLD = Map.of(
            "普通", BigDecimal.ZERO,
            "银卡", new BigDecimal("5000"),
            "金卡", new BigDecimal("20000"),
            "钻石", new BigDecimal("50000"),
            "黑卡", new BigDecimal("100000"));
    /** 默认权益清单兜底（benefits 列回填前，文案逐字对齐前端 stores/level.ts 默认）。 */
    private static final Map<String, List<String>> LEVEL_BENEFITS = Map.of(
            "普通", List.of("项目基础价"),
            "银卡", List.of("项目折扣 9.5 折", "生日当月 1.2 倍积分"),
            "金卡", List.of("项目折扣 9 折", "生日当月 1.5 倍积分", "专属咨询师"),
            "钻石", List.of("项目折扣 8.5 折", "生日当月 2 倍积分", "专属咨询师 + 免排队", "每月 1 次免费护理"),
            "黑卡", List.of("项目折扣 8 折", "生日当月 3 倍积分", "专属咨询师 + 免排队", "每月 2 次免费护理"));
    /** 升降级规则单行主键（仿 point_rule rule_id=1）。 */
    private static final int RULE_ID = 1;

    private final CustomerRepository customerRepo;
    private final MemberLevelRepository levelRepo;
    private final LevelRuleConfigRepository levelRuleRepo;
    private final MemberCardRepository cardRepo;
    private final PointsLedgerRepository ledgerRepo;
    private final PointsPoolRepository pointsPoolRepo;
    private final CustomerTagRelRepository tagRelRepo;
    private final CustomerTagRepository tagRepo;
    private final RefNameResolver nameResolver;

    public CustomerService(CustomerRepository customerRepo, MemberLevelRepository levelRepo,
                           LevelRuleConfigRepository levelRuleRepo,
                           MemberCardRepository cardRepo,
                           PointsLedgerRepository ledgerRepo, PointsPoolRepository pointsPoolRepo,
                           CustomerTagRelRepository tagRelRepo, CustomerTagRepository tagRepo,
                           RefNameResolver nameResolver) {
        this.customerRepo = customerRepo;
        this.levelRepo = levelRepo;
        this.levelRuleRepo = levelRuleRepo;
        this.cardRepo = cardRepo;
        this.ledgerRepo = ledgerRepo;
        this.pointsPoolRepo = pointsPoolRepo;
        this.tagRelRepo = tagRelRepo;
        this.tagRepo = tagRepo;
        this.nameResolver = nameResolver;
    }

    public List<Customer> listCustomers(String storeCode, String level, String status) {
        if (level != null && storeCode != null) return customerRepo.findByLevelAndStoreCode(level, storeCode);
        if (storeCode != null) return customerRepo.findByStoreCode(storeCode);
        if (level != null) return customerRepo.findByLevel(level);
        if (status != null) return customerRepo.findByStatus(status);
        return customerRepo.findAll();
    }

    /**
     * 客户列表（分页 + 动态过滤 + 标签批量解析）。
     * 过滤：门店/等级/状态/来源/关键字（姓名或手机号模糊）/标签（命中指定 tagId，exists 子查询）。
     * 标签通过 findByCustomerIdIn 一次批量取出，避免 N+1。
     */
    public Page<CustomerRowDTO> listRows(Pageable pageable, String storeCode, String level,
                                         String status, String channel, String keyword, String tagId) {
        // 数据域强制注入（服务端权威）：SELF 只见本人归属客户，STORE 本店，REGION 本区门店，GROUP/BRAND 全量；前端 storeCode 参数在域内收窄
        Specification<Customer> scopeSpec = DataScope.ownedSpec("storeCode", "ownerStaffId");
        // 手机号脱敏：无 customer:phone:decrypt 权限（或匿名服务间通道）仅见掩码；有权限见明文（列表仍受数据域约束）
        boolean showPhone = DataScope.hasPerm("customer:phone:decrypt");
        Specification<Customer> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (storeCode != null) ps.add(cb.equal(root.get("storeCode"), storeCode));
            if (level != null) ps.add(cb.equal(root.get("level"), level));
            if (status != null) ps.add(cb.equal(root.get("status"), status));
            if (channel != null) ps.add(cb.equal(root.get("channel"), channel));
            if (tagId != null && !tagId.isBlank()) {
                // 按标签过滤：客户在 customer_tag_rel 中存在该 tagId 关联即命中（数据域仍由 scopeSpec 强制）
                var sq = q.subquery(CustomerTagRel.class);
                var relRoot = sq.from(CustomerTagRel.class);
                sq.select(relRoot).where(cb.and(
                        cb.equal(relRoot.get("tagId"), tagId.trim()),
                        cb.equal(relRoot.get("customerId"), root.get("customerId"))));
                ps.add(cb.exists(sq));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword + "%";
                // 支持客户编号（SC001）检索：触达等 B 端操作页常按编号定位客户
                ps.add(cb.or(cb.like(root.get("name"), like),
                             cb.like(root.get("phone"), like),
                             cb.like(root.get("customerId"), like)));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };

        Page<Customer> page = customerRepo.findAll(scopeSpec.and(spec), pageable);

        List<String> ids = page.stream().map(Customer::getCustomerId).toList();
        Map<String, List<String>> tagsByCust = new HashMap<>();
        if (!ids.isEmpty()) {
            List<CustomerTagRel> rels = tagRelRepo.findByCustomerIdIn(ids);
            if (!rels.isEmpty()) {
                Map<String, String> nameById = tagRepo.findAllById(
                                rels.stream().map(CustomerTagRel::getTagId).collect(Collectors.toSet()))
                        .stream().collect(Collectors.toMap(CustomerTag::getTagId, CustomerTag::getTagName));
                for (CustomerTagRel r : rels) {
                    String nm = nameById.get(r.getTagId());
                    if (nm != null) tagsByCust.computeIfAbsent(r.getCustomerId(), k -> new ArrayList<>()).add(nm);
                }
            }
        }

        // 批量解析归属员工/门店中文名（同库只读，避免 N+1）
        Map<String, String> staffNames = nameResolver.staffNames(
                page.stream().map(Customer::getOwnerStaffId).toList());
        Map<String, String> storeNames = nameResolver.storeNames(
                page.stream().map(Customer::getStoreCode).toList());

        return page.map(c -> new CustomerRowDTO(
                c.getCustomerId(), c.getName(), maskPhone(c.getPhone(), showPhone), c.getGender(), c.getLevel(),
                c.getStoreCode(), storeNames.get(c.getStoreCode()),
                c.getChannel(), c.getTotalSpend(), c.getVisitCount(),
                c.getOwnerStaffId(), staffNames.get(c.getOwnerStaffId()),
                c.getStatus(), c.getPoints(),
                tagsByCust.getOrDefault(c.getCustomerId(), List.of())));
    }

    /** 客户详情：基础档案 + 归属员工/门店中文名。越权一律 404，不泄露数据是否存在。 */
    public CustomerDetailDTO getDetail(String customerId) {
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId())) {
            throw new NotFound("数据不存在或无权查看");
        }
        boolean showPhone = DataScope.hasPerm("customer:phone:decrypt");
        // 归属员工可能为空（公海客户）：singletonList 允许 null 元素，resolver 内部过滤 null
        Map<String, String> staffNames = nameResolver.staffNames(Collections.singletonList(c.getOwnerStaffId()));
        Map<String, String> storeNames = nameResolver.storeNames(Collections.singletonList(c.getStoreCode()));
        return new CustomerDetailDTO(
                c.getCustomerId(), c.getName(), maskPhone(c.getPhone(), showPhone), c.getGender(), c.getBirthDate(),
                c.getLevel(), c.getStoreCode(), storeNames.get(c.getStoreCode()),
                c.getChannel(), c.getTotalSpend(), c.getVisitCount(),
                c.getOwnerStaffId(), staffNames.get(c.getOwnerStaffId()),
                c.getStatus(), c.getPoints(), c.getCreatedAt());
    }

    /**
     * 手机号脱敏：无解密权限时 11 位手机号掩码为 138****8000（保留前 3 后 4）；
     * 非标准长度退化为尾 4 位掩码；null/空原样返回。
     */
    static String maskPhone(String phone, boolean showPlain) {
        if (phone == null || phone.isBlank() || showPlain) {
            return phone;
        }
        String p = phone.trim();
        if (p.length() == 11) {
            return p.substring(0, 3) + "****" + p.substring(7);
        }
        if (p.length() > 4) {
            return "****" + p.substring(p.length() - 4);
        }
        return "****";
    }

    public List<MemberCard> listCards(String customerId, boolean activeOnly) {
        if (activeOnly) return cardRepo.findByCustomerIdAndStatus(customerId, "在用");
        return cardRepo.findByCustomerId(customerId);
    }

    /**
     * 积分变更（append-only）：只插入流水台账，不改历史；
     * 同步更新 customer.points 终值与 points_pool 月度累计。
     * 系统流水（兑换扣分等）无幂等键，走此入口。
     */
    @Transactional
    public PointsLedger changePoints(String customerId, long changeAmt, String reason) {
        return adjustPoints(customerId, changeAmt, reason, null).ledger();
    }

    /**
     * 人工调分（写接口四件套）：
     * - 校验：changeAmt 非 0、reason 必填（≤64 字）、扣减后余额不得为负（422，业务不可处理）；
     * - 幂等：clientToken 非空时同键重放直接返回既有流水（created=false），不重复加减分、不重复落审计；
     * - 审计：由 Controller 仅在 created=true 时落 POINTS/MANUAL_ADJUST；
     * - 中文错误：全部中文文案。
     * synchronized 与 clientToken 唯一约束双保险防并发重复提交。
     */
    @Transactional
    public synchronized AdjustResult adjustPoints(String customerId, Long changeAmtBoxed, String reason, String clientToken) {
        if (changeAmtBoxed == null) throw new BadReq("调分金额不能为空");
        long changeAmt = changeAmtBoxed;
        if (changeAmt == 0) throw new BadReq("调分金额不能为 0");
        String r = reason == null ? "" : reason.trim();
        if (r.isEmpty()) throw new BadReq("调分原因不能为空");
        if (r.length() > 64) throw new BadReq("调分原因不能超过 64 字");
        String token = clientToken == null || clientToken.isBlank() ? null : clientToken.trim();
        if (token != null) {
            PointsLedger exist = ledgerRepo.findByClientToken(token).orElse(null);
            if (exist != null) return new AdjustResult(exist, false);
        }
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new NotFound("客户不存在: " + customerId));
        long after = c.getPoints() + changeAmt;
        if (after < 0) {
            throw new Unprocessable("积分不足：当前 " + c.getPoints() + "，变更 " + changeAmt);
        }
        c.setPoints(after);
        customerRepo.save(c);

        // 同步聚合池：获得计入 gained_month，兑换/扣减（负）计入 redeemed_month（新建池各列置 0 防空指针）。
        PointsPool pool = pointsPoolRepo.findById(1).orElseGet(() -> {
            PointsPool p = new PointsPool();
            p.setPoolId(1);
            p.setTotalIssued(0L);
            p.setGainedMonth(0L);
            p.setRedeemedMonth(0L);
            p.setExpiring90d(0L);
            return p;
        });
        if (changeAmt > 0) {
            pool.setGainedMonth(ns(pool.getGainedMonth()) + changeAmt);
        } else {
            pool.setRedeemedMonth(ns(pool.getRedeemedMonth()) + Math.abs(changeAmt));
        }
        pointsPoolRepo.save(pool);

        PointsLedger log = new PointsLedger();
        log.setCustomerId(customerId);
        log.setChangeAmt(changeAmt);
        log.setBalanceAfter(after);
        log.setReason(r);
        log.setClientToken(token);
        return new AdjustResult(ledgerRepo.save(log), true);
    }

    /** 调分结果：ledger 为流水（新建或重放的既有流水），created=false 表示幂等重放。 */
    public record AdjustResult(PointsLedger ledger, boolean created) {}

    /**
     * 新建客户（写接口四件套：校验 / 防重 / 审计 / 中文错误）。
     * 编号由后端按库内最大 M 序号生成（synchronized + max 查询防重号，禁内存自增）；
     * 归属门店/归属人取登录上下文，前端不可伪造；status/points/消费等聚合字段一律服务端置默认。
     * 同门店同手机号撞单直接中文拒绝（避免重复建档）。审计由 Controller 落 CUSTOMER/CREATE。
     */
    @Transactional
    public synchronized Customer create(Customer input) {
        if (input == null) throw new BadReq("请求体不能为空");
        String name = trim(input.getName());
        String phone = trim(input.getPhone());
        String gender = trim(input.getGender());
        String level = trim(input.getLevel());
        String channel = trim(input.getChannel());

        if (name.isEmpty()) throw new BadReq("请填写客户姓名");
        if (name.length() > 32) throw new BadReq("客户姓名最长 32 字");
        if (phone.isEmpty()) throw new BadReq("请填写手机号");
        if (!PHONE_RE.matcher(phone).matches()) throw new BadReq("手机号格式不正确（需 11 位大陆手机号）");
        if (!GENDERS.contains(gender)) throw new BadReq("性别取值非法：女 / 男 / 其他");
        if (level.isEmpty()) level = "普通";
        if (!levelRepo.existsById(level)) throw new BadReq("会员等级不存在：" + level);
        if (channel.isEmpty()) channel = "WALK_IN";
        if (!CHANNELS.contains(channel)) throw new BadReq("获客渠道取值非法：" + channel);

        // 归属门店/归属人由登录上下文权威注入（SELF/STORE 有门店；GROUP/REGION 无门店则进公海）
        String storeCode = DataScope.current() != null ? trim(DataScope.current().storeCode()) : "";
        if (storeCode.isEmpty()) storeCode = null;
        String ownerStaffId = trim(SecurityContext.currentStaffId());
        if (ownerStaffId.isEmpty()) ownerStaffId = null;

        // 撞单：同门店同手机号拒绝（公海客户全局查重），提示已有客户号
        Optional<Customer> dup = storeCode != null
                ? customerRepo.findFirstByStoreCodeAndPhone(storeCode, phone)
                : customerRepo.findFirstByStoreCodeIsNullAndPhone(phone);
        if (dup.isPresent()) {
            throw new BadReq("该手机号已建档（客户号 " + dup.get().getCustomerId() + "），请勿重复新建");
        }

        Customer c = new Customer();
        c.setCustomerId(nextCustomerId());
        c.setName(name);
        c.setPhone(phone);
        c.setGender(gender);
        c.setLevel(level);
        c.setChannel(channel);
        c.setBirthDate(input.getBirthDate());
        c.setStoreCode(storeCode);
        c.setOwnerStaffId(ownerStaffId);
        // points/status/totalSpend/visitCount/createdAt 由 @PrePersist 置默认（0/活跃/0 元/0 次/当前时间）
        return customerRepo.save(c);
    }

    /** 生成下一个客户编号：M+3 位序号，基于库内最大号递增（synchronized 防并发重号）。 */
    private String nextCustomerId() {
        String max = customerRepo.maxMId();
        int seq = 0;
        if (max != null && max.startsWith("M")) {
            try {
                seq = Integer.parseInt(max.substring(1));
            } catch (NumberFormatException ignored) {
                seq = 0;
            }
        }
        return String.format("M%03d", seq + 1);
    }

    // ---- 标签：定义 CRUD / 打标 / 删标（写接口四件套：校验 / 防重 / 审计由 Controller 落 / 中文错误） ----

    /** 标签列表读模型：全量标签 + 覆盖客户数（group by 一次取回，无关联计 0）。 */
    @Transactional(readOnly = true)
    public List<TagStatDTO> listTagStats() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : tagRelRepo.countGroupByTag()) {
            if (row[0] != null) counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        return tagRepo.findAll().stream()
                .map(t -> new TagStatDTO(t.getTagId(), t.getTagName(), t.getCategory(),
                        counts.getOrDefault(t.getTagId(), 0L)))
                .toList();
    }

    /** 标签覆盖汇总：标签总数 / 去重覆盖客户数 / 累计打标人次 / 已打标客户人均标签数（无覆盖客户时人均 0）。 */
    @Transactional(readOnly = true)
    public TagOverviewDTO listTagOverview() {
        long totalTags = tagRepo.count();
        long covered = tagRelRepo.countDistinctCustomers();
        long assignments = tagRelRepo.count();
        double avg = covered == 0 ? 0d : Math.round(assignments * 100.0 / covered) / 100.0;
        return new TagOverviewDTO(totalTags, covered, assignments, avg);
    }

    /** 新建标签：名称必填（≤32 字）且唯一、分类必须在五分类白名单；TG### 编号库内 max+1（synchronized 防重号）。 */
    @Transactional
    public synchronized CustomerTag createTag(String name, String category) {
        String n = trim(name);
        String cat = trim(category);
        if (n.isEmpty()) throw new BadReq("标签名称不能为空");
        if (n.length() > 32) throw new BadReq("标签名称最长 32 字");
        if (!TAG_CATEGORIES.contains(cat)) throw new BadReq("标签分类取值非法：消费 / 肤质 / 行为 / 价值 / 医疗");
        if (tagRepo.existsByTagName(n)) throw new Conflict("标签名称已存在：" + n);
        CustomerTag t = new CustomerTag();
        t.setTagId(nextTagId());
        t.setTagName(n);
        t.setCategory(cat);
        return tagRepo.save(t);
    }

    /** 改名/改分类：标签不存在 404；分类白名单校验；改名时排除自身做唯一冲突 409。 */
    @Transactional
    public synchronized CustomerTag updateTag(String tagId, String name, String category) {
        CustomerTag t = tagRepo.findById(tagId)
                .orElseThrow(() -> new NotFound("标签不存在: " + tagId));
        String n = trim(name);
        String cat = trim(category);
        if (n.isEmpty()) throw new BadReq("标签名称不能为空");
        if (n.length() > 32) throw new BadReq("标签名称最长 32 字");
        if (!TAG_CATEGORIES.contains(cat)) throw new BadReq("标签分类取值非法：消费 / 肤质 / 行为 / 价值 / 医疗");
        if (!n.equals(t.getTagName()) && tagRepo.existsByTagName(n)) {
            throw new Conflict("标签名称已存在：" + n);
        }
        t.setTagName(n);
        t.setCategory(cat);
        return tagRepo.save(t);
    }

    /**
     * 删除标签：先删客户关联再删定义（customer_tag_rel 对 customer_tag 有物理外键，无 cascade 注解须显式删）。
     * 返回被解绑的客户数，供 Controller 落审计；标签不存在 404。
     */
    @Transactional
    public synchronized int deleteTag(String tagId) {
        if (!tagRepo.existsById(tagId)) throw new NotFound("标签不存在: " + tagId);
        int removed = tagRelRepo.deleteByTagId(tagId);
        tagRepo.deleteById(tagId);
        return removed;
    }

    /** 打标：客户/标签存在性校验（数据域由 Controller requireReadable 权威校验）；重复打标 409，复合主键天然兜底。 */
    @Transactional
    public CustomerTagRel assignTag(String customerId, String tagId) {
        if (!customerRepo.existsById(customerId)) throw new NotFound("客户不存在: " + customerId);
        if (!tagRepo.existsById(tagId)) throw new NotFound("标签不存在: " + tagId);
        if (tagRelRepo.existsByCustomerIdAndTagId(customerId, tagId)) {
            throw new Conflict("该客户已打此标签，请勿重复操作");
        }
        CustomerTagRel rel = new CustomerTagRel();
        rel.setCustomerId(customerId);
        rel.setTagId(tagId);
        return tagRelRepo.save(rel);
    }

    /** 删标（客户解绑单个标签）：关联不存在 404；返回被删的关系供审计。 */
    @Transactional
    public CustomerTagRel unassignTag(String customerId, String tagId) {
        CustomerTagRel.Key key = new CustomerTagRel.Key();
        key.setCustomerId(customerId);
        key.setTagId(tagId);
        if (!tagRelRepo.existsById(key)) throw new NotFound("该客户未打此标签");
        tagRelRepo.deleteByCustomerIdAndTagId(customerId, tagId);
        CustomerTagRel snapshot = new CustomerTagRel();
        snapshot.setCustomerId(customerId);
        snapshot.setTagId(tagId);
        return snapshot;
    }

    /** 生成下一个标签编号：TG+3 位序号，基于库内最大 TG 号递增（STG 种子标签不占号段，synchronized 防并发重号）。 */
    private String nextTagId() {
        String max = tagRepo.maxTgId();
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

    // ---- 会员等级：读模型（实时人数）/ 阈值权益配置 / 升降级规则 / 手工调级 / 按消费自动升级 ----

    /**
     * 等级读模型：五级按 sortNo 升序，人数读时实时 count(customer) group by level（不读 cnt 历史假数据）；
     * memberPercent 为整数四舍五入百分比，不强制合计 100（对齐 mock 60/24/11/4/1 口径）。
     * tier/颜色/阈值/权益列在 prod 回填 SQL 执行前由 LEVEL_* 常量兜底，页面不出现空值。
     */
    @Transactional(readOnly = true)
    public List<MemberLevelDTO> listLevelDTOs() {
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : customerRepo.countGroupByLevel()) {
            if (row[0] != null) counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return sortedLevels().stream()
                .map(lv -> toLevelDTO(lv, counts.getOrDefault(lv.getLevel(), 0L), total))
                .toList();
    }

    /** 五级按 sortNo 升序；sort_no 为空（回填前）回落到 LEVEL_ORDER 常量序。 */
    private List<MemberLevel> sortedLevels() {
        return levelRepo.findAll().stream()
                .sorted(Comparator.comparingInt(lv -> lv.getSortNo() != null
                        ? lv.getSortNo()
                        : LEVEL_ORDER.getOrDefault(lv.getLevel(), 99)))
                .toList();
    }

    private MemberLevelDTO toLevelDTO(MemberLevel lv, long count, long total) {
        String id = lv.getLevel();
        BigDecimal threshold = lv.getUpgradeThreshold() != null
                ? lv.getUpgradeThreshold()
                : LEVEL_THRESHOLD.getOrDefault(id, BigDecimal.ZERO);
        List<String> benefits = lv.getBenefits() != null && !lv.getBenefits().isEmpty()
                ? List.copyOf(lv.getBenefits())
                : LEVEL_BENEFITS.getOrDefault(id, List.of());
        long percent = total == 0 ? 0L : Math.round(count * 100.0 / total);
        boolean top = lv.getIsTop() != null ? lv.getIsTop() : "黑卡".equals(id);
        return new MemberLevelDTO(
                id,
                lv.getTier() != null ? lv.getTier() : LEVEL_TIER.get(id),
                id + "会员",
                lv.getColor() != null ? lv.getColor() : LEVEL_COLOR.get(id),
                threshold,
                upgradeCondition(threshold),
                benefits,
                count,
                percent,
                top,
                lv.getDiscount());
    }

    /** 升级条件文案：阈值 0=注册即享；>0=累计消费 ≥ ¥x,xxx（美式千分位，对齐前端 toLocaleString）。 */
    private static String upgradeCondition(BigDecimal threshold) {
        if (threshold == null || threshold.signum() <= 0) return "注册即享（无门槛）";
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.US);
        nf.setMaximumFractionDigits(2);
        nf.setMinimumFractionDigits(0);
        return "累计消费 ≥ ¥" + nf.format(threshold.stripTrailingZeros());
    }

    /**
     * 更新等级阈值/权益（写接口四件套）：等级不存在 404；阈值必填非负、普通固定 0、不得超过 1 亿；
     * 权益清单最多 10 条、单条 ≤40 字。全部参数与现值相同则 changed=false（幂等同态短路，Controller 不重复审计）。
     */
    @Transactional
    public synchronized LevelConfigResult updateLevelConfig(String level, BigDecimal threshold, List<String> benefits) {
        MemberLevel lv = levelRepo.findById(level)
                .orElseThrow(() -> new NotFound("会员等级不存在：" + level));
        if (threshold == null) throw new BadReq("升级阈值不能为空");
        if (threshold.signum() < 0) throw new BadReq("升级阈值不能为负数");
        if (threshold.compareTo(new BigDecimal("100000000")) > 0) throw new BadReq("升级阈值超出合理上限（1 亿）");
        if ("普通".equals(level) && threshold.signum() != 0) {
            throw new BadReq("普通会员为注册即享等级，阈值必须为 0");
        }
        List<String> cleaned = List.of();
        if (benefits != null) {
            cleaned = benefits.stream()
                    .filter(b -> b != null && !b.isBlank())
                    .map(String::trim)
                    .toList();
            if (cleaned.size() > 10) throw new BadReq("权益清单最多 10 条");
            if (cleaned.stream().anyMatch(b -> b.length() > 40)) throw new BadReq("单条权益不能超过 40 字");
        }
        BigDecimal oldThreshold = lv.getUpgradeThreshold() != null
                ? lv.getUpgradeThreshold()
                : LEVEL_THRESHOLD.getOrDefault(level, BigDecimal.ZERO);
        List<String> oldBenefits = lv.getBenefits() != null && !lv.getBenefits().isEmpty()
                ? List.copyOf(lv.getBenefits())
                : LEVEL_BENEFITS.getOrDefault(level, List.of());
        boolean changed = oldThreshold.compareTo(threshold) != 0 || !oldBenefits.equals(cleaned);
        if (changed) {
            lv.setUpgradeThreshold(threshold);
            lv.setBenefits(cleaned);
            levelRepo.save(lv);
        }
        Map<String, Long> counts = new HashMap<>();
        for (Object[] row : customerRepo.countGroupByLevel()) {
            if (row[0] != null) counts.put((String) row[0], ((Number) row[1]).longValue());
        }
        long total = counts.values().stream().mapToLong(Long::longValue).sum();
        return new LevelConfigResult(toLevelDTO(lv, counts.getOrDefault(level, 0L), total), changed,
                oldThreshold, oldBenefits);
    }

    /** 等级配置更新结果：dto 为更新后读模型；changed=false 表示同态短路不审计；old* 供审计 before/after。 */
    public record LevelConfigResult(MemberLevelDTO dto, boolean changed,
                                    BigDecimal oldThreshold, List<String> oldBenefits) {}

    /** 升降级规则读模型：未配置时返回默认（不落库，与积分规则 orElseGet 同风格）。 */
    @Transactional(readOnly = true)
    public LevelRuleConfig getLevelRule() {
        return levelRuleRepo.findById(RULE_ID).orElseGet(CustomerService::defaultRule);
    }

    private static LevelRuleConfig defaultRule() {
        LevelRuleConfig r = new LevelRuleConfig();
        r.setRuleId(RULE_ID);
        r.setCalcPeriod("自然月（每月1号）");
        r.setDowngradeProtectMonths(3);
        r.setAutoUpgrade(true);
        r.setPointsMultiplier(BigDecimal.ONE);
        return r;
    }

    /**
     * 保存升降级规则（四件套）：周期文案必填（≤32 字）、保护期 0~36 月、积分倍率 0.01~10；
     * 全字段同现值时 changed=false（幂等不审计）；行不存在则按默认值兜底新建。
     */
    @Transactional
    public synchronized RuleSaveResult saveLevelRule(String calcPeriod, Integer protectMonths,
                                                     Boolean autoUpgrade, BigDecimal pointsMultiplier) {
        String period = trim(calcPeriod);
        if (period.isEmpty()) throw new BadReq("等级计算周期不能为空");
        if (period.length() > 32) throw new BadReq("等级计算周期不能超过 32 字");
        int protect = protectMonths == null ? 3 : protectMonths;
        if (protect < 0 || protect > 36) throw new BadReq("降级保护期须在 0~36 月之间");
        boolean auto = autoUpgrade == null || autoUpgrade;
        BigDecimal mult = pointsMultiplier == null ? BigDecimal.ONE : pointsMultiplier;
        if (mult.compareTo(BigDecimal.ZERO) <= 0 || mult.compareTo(new BigDecimal("10")) > 0) {
            throw new BadReq("消费积分倍率须在 0.01~10 之间");
        }

        LevelRuleConfig r = levelRuleRepo.findById(RULE_ID).orElseGet(CustomerService::defaultRule);
        boolean changed = !period.equals(r.getCalcPeriod())
                || protect != (r.getDowngradeProtectMonths() == null ? 3 : r.getDowngradeProtectMonths())
                || auto != (r.getAutoUpgrade() == null || r.getAutoUpgrade())
                || mult.compareTo(r.getPointsMultiplier() == null ? BigDecimal.ONE : r.getPointsMultiplier()) != 0;
        if (changed) {
            r.setCalcPeriod(period);
            r.setDowngradeProtectMonths(protect);
            r.setAutoUpgrade(auto);
            r.setPointsMultiplier(mult);
            r.setUpdatedAt(java.time.OffsetDateTime.now());
            levelRuleRepo.save(r);
        }
        return new RuleSaveResult(r, changed);
    }

    /** 规则保存结果：changed=false 表示同态短路不落审计。 */
    public record RuleSaveResult(LevelRuleConfig rule, boolean changed) {}

    /**
     * 手工调级（四件套）：数据域由 Controller requireReadable 强制；目标等级须在五级白名单；
     * 与当前等级相同返回 changed=false（幂等同态短路，不重复落审计）。返回调级后的客户实体。
     */
    @Transactional
    public synchronized LevelAdjustResult adjustCustomerLevel(Customer c, String targetLevel, String reason) {
        String target = trim(targetLevel);
        String r = trim(reason);
        if (target.isEmpty()) throw new BadReq("目标等级不能为空");
        if (!levelRepo.existsById(target)) throw new BadReq("会员等级不存在：" + target);
        if (r.isEmpty()) throw new BadReq("调整原因不能为空");
        if (r.length() > 64) throw new BadReq("调整原因不能超过 64 字");
        String from = c.getLevel();
        if (target.equals(from)) return new LevelAdjustResult(c, false, from, target);
        c.setLevel(target);
        return new LevelAdjustResult(customerRepo.save(c), true, from, target);
    }

    /** 手工调级结果：changed=false 表示与现等级相同（幂等短路）。 */
    public record LevelAdjustResult(Customer customer, boolean changed, String fromLevel, String toLevel) {}

    /**
     * 按累计消费自动升级（手动触发批量重算；定时批处理列 Backlog）。
     * 规则：遍历五级（sortNo 升序），客户 totalSpend ≥ 目标阈值且当前等级序严格低于目标序才升级——只升不降，
     * 已在更高等级的客户即使消费不足也绝不回落（降级引擎另列 Backlog）。一次批量升级完成后统一返回明细，
     * 由 Controller 在 upgraded>0 时落单条 LEVEL/AUTO_UPGRADE 汇总审计。
     */
    @Transactional
    public synchronized AutoUpgradeResult autoUpgrade() {
        List<MemberLevel> levels = sortedLevels();
        Map<String, Integer> order = new HashMap<>();
        Map<String, BigDecimal> threshold = new HashMap<>();
        for (MemberLevel lv : levels) {
            order.put(lv.getLevel(), lv.getSortNo() != null
                    ? lv.getSortNo()
                    : LEVEL_ORDER.getOrDefault(lv.getLevel(), 99));
            threshold.put(lv.getLevel(), lv.getUpgradeThreshold() != null
                    ? lv.getUpgradeThreshold()
                    : LEVEL_THRESHOLD.getOrDefault(lv.getLevel(), BigDecimal.ZERO));
        }
        List<UpgradeItem> items = new ArrayList<>();
        for (Customer c : customerRepo.findAll()) {
            Integer curOrder = order.get(c.getLevel());
            if (curOrder == null) continue;
            String target = c.getLevel();
            for (MemberLevel lv : levels) {
                Integer to = order.get(lv.getLevel());
                if (to > curOrder
                        && c.getTotalSpend() != null
                        && c.getTotalSpend().compareTo(threshold.get(lv.getLevel())) >= 0
                        && to > order.get(target)) {
                    target = lv.getLevel();
                }
            }
            if (!target.equals(c.getLevel())) {
                String from = c.getLevel();
                c.setLevel(target);
                customerRepo.save(c);
                items.add(new UpgradeItem(c.getCustomerId(), c.getName(), from, target, c.getTotalSpend()));
            }
        }
        return new AutoUpgradeResult(items.size(), items);
    }

    /** 自动升级明细行。 */
    public record UpgradeItem(String customerId, String name, String fromLevel, String toLevel,
                              java.math.BigDecimal totalSpend) {}

    /** 自动升级结果：upgraded=升级人数，items 为全部明细（审计 payload + 接口返回共用）。 */
    public record AutoUpgradeResult(int upgraded, List<UpgradeItem> items) {}

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /** Long 空值安全转 0（聚合池历史行可能为 null）。 */
    private static long ns(Long v) {
        return v == null ? 0L : v;
    }

    /** 业务异常 → HTTP 状态码映射（由 GlobalExceptionHandler 处理）。 */
    public static class NotFound extends RuntimeException { public NotFound(String m){super(m);} }
    public static class BadReq extends RuntimeException { public BadReq(String m){super(m);} }
    /** 状态机冲突 / 幂等重放冲突 → 409（审核重复提交、重复打标等，幂等提交方据此识别已受理）。 */
    public static class Conflict extends RuntimeException { public Conflict(String m){super(m);} }
    /** 业务不可处理（库存/积分不足等）→ 422，区别于参数格式错误 400。 */
    public static class Unprocessable extends RuntimeException { public Unprocessable(String m){super(m);} }
}
