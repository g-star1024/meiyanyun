package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * M3-20 积分商城 B 端管理后台。
 *
 * 定位：B 端配置商品/库存/积分定价/上下架 + 兑换审核（双签）+ 履约发放；C 端兑换入口在小程序/App。
 * 红线：兑换审核须双签（店长初审 + 运营复核），两签不得同一人。
 *
 * 写接口四件套（铁律）：参数校验 → 幂等（单号/幂等键重放、状态机冲突 409）→ 全动作 audit_log → 中文错误。
 * 单号 MP/EX + yyyyMMdd-6 位，基于库内当日最大号递增（synchronized，禁内存计数，铁律 6）。
 * 状态中文存储中文展示：商品「已上架/已下架」（低库存≤50 为前端派生，不入库）；
 * 兑换单「待审核/已通过/已拒绝/已发放」；商品类型「项目/实物/优惠券/服务」。
 */
@RestController
@RequestMapping("/api/customer/mall")
public class MallController {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MallProductRepository productRepo;
    private final PointRuleRepository ruleRepo;
    private final MallExchangeRepository exchangeRepo;
    private final CustomerService customerService;
    private final CustomerRepository customerRepo;
    private final AuditRecorder audit;

    public MallController(MallProductRepository productRepo, PointRuleRepository ruleRepo,
                          MallExchangeRepository exchangeRepo, CustomerService customerService,
                          CustomerRepository customerRepo, AuditRecorder audit) {
        this.productRepo = productRepo;
        this.ruleRepo = ruleRepo;
        this.exchangeRepo = exchangeRepo;
        this.customerService = customerService;
        this.customerRepo = customerRepo;
        this.audit = audit;
    }

    // ==================== 商品管理 ====================

    @GetMapping("/products")
    @RequirePerm("points:view")
    public List<MallProduct> products(@RequestParam(required = false) String status) {
        return status == null ? productRepo.findAllByOrderByCreatedAtDesc()
                : productRepo.findByStatusOrderByPointsPriceAsc(status);
    }

    /** 新建商品：stock=0 待上架（已下架），stock>0 或 -1（优惠券不限库存）直接「已上架」。 */
    @PostMapping("/product")
    @RequirePerm("points:edit")
    @Transactional
    public MallProduct createProduct(@RequestBody @Valid ProductCmd cmd) {
        String type = normalizeType(cmd.type());
        if (cmd.pointsPrice() == null || cmd.pointsPrice() <= 0) {
            throw new CustomerService.BadReq("积分定价须大于 0");
        }
        int stock = cmd.stock() == null ? 0 : cmd.stock();
        if (stock < -1) {
            throw new CustomerService.BadReq("库存不可小于 -1（-1 表示不限库存）");
        }
        MallProduct p = new MallProduct();
        p.setProductId(nextProductNo());
        p.setProductName(cmd.name().trim());
        p.setProductType(type);
        p.setPointsPrice(cmd.pointsPrice());
        p.setStock(stock);
        p.setRedeemedCount(0);
        p.setStatus(stock == 0 ? "已下架" : "已上架");
        p.setCover(cmd.cover());
        p.setDescription(cmd.description());
        p.setCreatedAt(OffsetDateTime.now());
        MallProduct saved = productRepo.save(p);
        audit.record("MALL", saved.getProductId(), actor(), "PRODUCT_CREATE",
                json(productPayload(saved, Map.of("summary",
                        "新建积分商品「" + saved.getProductName() + "」（" + saved.getProductType()
                                + "），定价 " + saved.getPointsPrice() + " 积分，库存 " + stockText(stock)
                                + "，状态" + saved.getStatus()))));
        return saved;
    }

    /** 编辑商品资料（名称/类型/定价/说明/封面；库存走 adjust，上下架走 toggle，此处不改状态/库存）。 */
    @PutMapping("/product/{id}")
    @RequirePerm("points:edit")
    @Transactional
    public MallProduct editProduct(@PathVariable String id, @RequestBody @Valid ProductCmd cmd) {
        MallProduct p = productRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + id));
        String before = p.getProductName() + "/" + p.getProductType() + "/" + p.getPointsPrice() + "积分";
        p.setProductName(cmd.name().trim());
        p.setProductType(normalizeType(cmd.type()));
        if (cmd.pointsPrice() == null || cmd.pointsPrice() <= 0) {
            throw new CustomerService.BadReq("积分定价须大于 0");
        }
        p.setPointsPrice(cmd.pointsPrice());
        p.setDescription(cmd.description());
        if (cmd.cover() != null) {
            p.setCover(cmd.cover());
        }
        MallProduct saved = productRepo.save(p);
        audit.record("MALL", saved.getProductId(), actor(), "PRODUCT_EDIT",
                json(productPayload(saved, Map.of("before", before, "summary",
                        "编辑积分商品「" + saved.getProductName() + "」，类型 " + saved.getProductType()
                                + "，定价 " + saved.getPointsPrice() + " 积分"))));
        return saved;
    }

    /** 上下架切换（幂等：目标状态与当前一致时直接返回，不重复落审计）。 */
    @PostMapping("/product/{id}/toggle")
    @RequirePerm("points:edit")
    @Transactional
    public MallProduct toggle(@PathVariable String id) {
        MallProduct p = productRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + id));
        boolean onSale = "已上架".equals(p.getStatus());
        if (!onSale && p.getStock() != null && p.getStock() == 0) {
            throw new CustomerService.Unprocessable("库存为 0，不可上架；请先调整库存");
        }
        String target = onSale ? "已下架" : "已上架";
        if (target.equals(p.getStatus())) {
            return p;
        }
        p.setStatus(target);
        MallProduct saved = productRepo.save(p);
        audit.record("MALL", saved.getProductId(), actor(), onSale ? "PRODUCT_OFF_SHELF" : "PRODUCT_ON_SHELF",
                json(productPayload(saved, Map.of("summary",
                        (onSale ? "商品「" + saved.getProductName() + "」下架" : "商品「" + saved.getProductName() + "」上架")
                                + "，剩余库存 " + stockText(saved.getStock())))));
        return saved;
    }

    /** 调整库存 / 积分定价（库存 0 且在售则自动转下架；-1 为不限库存）。 */
    @PostMapping("/product/{id}/adjust")
    @RequirePerm("points:edit")
    @Transactional
    public MallProduct adjust(@PathVariable String id, @RequestBody AdjustCmd cmd) {
        MallProduct p = productRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + id));
        Map<String, Object> extra = new LinkedHashMap<>();
        if (cmd.stock() != null) {
            if (cmd.stock() < -1) {
                throw new CustomerService.BadReq("库存不可小于 -1（-1 表示不限库存）");
            }
            extra.put("stockBefore", stockText(p.getStock()));
            p.setStock(cmd.stock());
            // 在售商品库存调为 0 自动下架（防止超兑）；优惠券 -1 不受影响。
            if (cmd.stock() == 0 && "已上架".equals(p.getStatus())) {
                p.setStatus("已下架");
                extra.put("autoOffShelf", true);
            }
        }
        if (cmd.pointsPrice() != null) {
            if (cmd.pointsPrice() <= 0) {
                throw new CustomerService.BadReq("积分定价须大于 0");
            }
            extra.put("pointsPriceBefore", p.getPointsPrice());
            p.setPointsPrice(cmd.pointsPrice());
        }
        MallProduct saved = productRepo.save(p);
        StringBuilder sb = new StringBuilder("调整商品「").append(saved.getProductName()).append("」");
        if (cmd.stock() != null) sb.append("，库存改为 ").append(stockText(saved.getStock()));
        if (cmd.pointsPrice() != null) sb.append("，定价改为 ").append(saved.getPointsPrice()).append(" 积分");
        if (Boolean.TRUE.equals(extra.get("autoOffShelf"))) sb.append("（库存归零已自动下架）");
        extra.put("summary", sb.toString());
        audit.record("MALL", saved.getProductId(), actor(), "PRODUCT_ADJUST",
                json(productPayload(saved, extra)));
        return saved;
    }

    // ==================== 积分规则 ====================

    @GetMapping("/rule")
    @RequirePerm("points:view")
    public PointRule rule() {
        return ruleRepo.findById(1).orElseGet(PointRule::new);
    }

    /** 保存积分规则（单行 rule_id=1，全字段覆盖；未上线的字段以配置入库，事件流接入后生效）。 */
    @PutMapping("/rule")
    @RequirePerm("points:edit")
    @Transactional
    public PointRule saveRule(@RequestBody @Valid RuleCmd cmd) {
        PointRule r = ruleRepo.findById(1).orElseGet(PointRule::new);
        r.setRuleId(1);
        if (cmd.earnRate() != null && cmd.earnRate().compareTo(BigDecimal.ZERO) < 0) {
            throw new CustomerService.BadReq("消费积分比例不可为负");
        }
        if (cmd.expireMonths() != null && cmd.expireMonths() <= 0) {
            throw new CustomerService.BadReq("积分有效期须大于 0（月）");
        }
        r.setEarnRate(cmd.earnRate() == null ? BigDecimal.ONE : cmd.earnRate());
        r.setRedeemRatio(cmd.redeemRatio() == null ? new BigDecimal("100.00") : cmd.redeemRatio());
        r.setExpireMonths(cmd.expireMonths() == null ? 12 : cmd.expireMonths());
        r.setSignInReward(cmd.signInReward());
        r.setBirthdayMultiplier(cmd.birthdayMultiplier());
        r.setReferralReward(cmd.referralReward());
        r.setManualGrantEnabled(Boolean.TRUE.equals(cmd.manualGrantEnabled()));
        r.setUpdatedAt(OffsetDateTime.now());
        PointRule saved = ruleRepo.save(r);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("earnRate", saved.getEarnRate());
        payload.put("redeemRatio", saved.getRedeemRatio());
        payload.put("expireMonths", saved.getExpireMonths());
        payload.put("signInReward", saved.getSignInReward());
        payload.put("birthdayMultiplier", saved.getBirthdayMultiplier());
        payload.put("referralReward", saved.getReferralReward());
        payload.put("manualGrantEnabled", saved.getManualGrantEnabled());
        payload.put("authority", "customer");
        payload.put("summary", "保存积分规则：消费 " + saved.getEarnRate() + " 倍积分、有效期 "
                + saved.getExpireMonths() + " 个月、签到奖励 " + saved.getSignInReward()
                + "、生日倍率 " + saved.getBirthdayMultiplier() + "、推荐奖励 " + saved.getReferralReward()
                + "、手动调分 " + (Boolean.TRUE.equals(saved.getManualGrantEnabled()) ? "开启" : "关闭"));
        audit.record("MALL", "RULE-1", actor(), "RULE_SAVE", json(payload));
        return saved;
    }

    // ==================== 兑换审核（双签） ====================

    @GetMapping("/exchanges")
    @RequirePerm("points:approve")
    public List<MallExchange> exchanges(@RequestParam(required = false) String status) {
        List<MallExchange> list = status == null ? exchangeRepo.findAllByOrderByCreatedAtDesc()
                : exchangeRepo.findByStatusOrderByCreatedAtDesc(status);
        return withNames(list);
    }

    /**
     * 兑换申请入口：客户提交兑换申请（生成「待审核」单，不立即扣积分/库存，双签审核通过时同事务扣减）。
     * 红线：仅已上架商品可兑；有限库存须充足；数量须大于 0；实物类须填收货信息。
     * 幂等：clientToken 同键重放返回既有单（网络重试/重复点击不重复落库/审计）。
     * 权限：须登录（points:view 登录门槛），customerId 必须为真实存在的客户——禁止匿名刷单；
     * C 端会员独立 token 体系上线前，customerId 由 B 端代客下单/已登录会话带入。
     */
    @PostMapping("/exchange")
    @RequirePerm("points:view")
    @Transactional
    public MallExchange placeExchange(@RequestBody @Valid PlaceExchangeCmd cmd) {
        // 幂等重放：同 clientToken 已存在 → 直接返回既有单（不重复落库/审计）。
        if (cmd.clientToken() != null && !cmd.clientToken().isBlank()) {
            Optional<MallExchange> replay = exchangeRepo.findFirstByClientToken(cmd.clientToken());
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        Customer c = customerRepo.findById(cmd.customerId())
                .orElseThrow(() -> new CustomerService.BadReq("客户不存在: " + cmd.customerId()));
        MallProduct p = productRepo.findById(cmd.productId())
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + cmd.productId()));
        if (!"已上架".equals(p.getStatus())) {
            throw new CustomerService.BadReq("商品未上架，不可兑换");
        }
        int qty = cmd.qty() == null ? 1 : cmd.qty();
        if (qty <= 0) {
            throw new CustomerService.BadReq("兑换数量须大于 0");
        }
        // 有限库存（stock != -1）才校验；优惠券等不限库存跳过。
        if (p.getStock() != -1 && p.getStock() < qty) {
            throw new CustomerService.Unprocessable("库存不足：剩余 " + p.getStock() + "，需 " + qty);
        }
        // 实物商品须留收货信息（审核队列/发货依赖）。
        if ("实物".equals(p.getProductType()) && (cmd.shipName() == null || cmd.shipName().isBlank()
                || cmd.shipPhone() == null || cmd.shipPhone().isBlank()
                || cmd.shipAddress() == null || cmd.shipAddress().isBlank())) {
            throw new CustomerService.BadReq("实物商品兑换须填写收货人、电话与地址");
        }
        int pointsSpent = p.getPointsPrice() * qty;
        MallExchange e = new MallExchange();
        e.setExchangeId(nextExchangeNo());
        e.setProductId(cmd.productId());
        e.setCustomerId(cmd.customerId());
        e.setQty(qty);
        e.setPointsSpent(pointsSpent);
        e.setStatus("待审核");
        e.setShipName(trim(cmd.shipName()));
        e.setShipPhone(trim(cmd.shipPhone()));
        e.setShipAddress(trim(cmd.shipAddress()));
        e.setClientToken(trim(cmd.clientToken()));
        e.setCreatedAt(OffsetDateTime.now());
        MallExchange saved = exchangeRepo.save(e);
        Map<String, Object> payload = exchangePayload(saved, c.getName(), p.getProductName());
        payload.put("summary", "客户「" + c.getName() + "」提交兑换「" + p.getProductName() + "」×"
                + qty + "，消耗 " + pointsSpent + " 积分，待双签审核");
        audit.record("MALL", saved.getExchangeId(), actor(), "EXCHANGE_PLACE", json(payload));
        saved.setCustomerName(c.getName());
        saved.setProductName(p.getProductName());
        return saved;
    }

    /** C 端「我的兑换」：按客户查兑换记录及审核状态；须通过该客户数据域断言，越权/不存在统一 404。 */
    @GetMapping("/exchanges/my")
    @RequirePerm({"points:view", "customer:view"})
    public List<MallExchange> myExchanges(@RequestParam String customerId) {
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new CustomerService.NotFound("数据不存在或无权查看"));
        if (!DataScope.canReadOwned(c.getStoreCode(), c.getOwnerStaffId())) {
            throw new CustomerService.NotFound("数据不存在或无权查看");
        }
        return withNames(exchangeRepo.findByCustomerIdOrderByCreatedAtDesc(customerId));
    }

    /**
     * 兑换审核双签：sign1 店长初审 + sign2 运营复核，两签不得同一人。
     * 通过后同事务：累加商品已兑数 + 扣减库存（-1 不限库存跳过）+ 扣减客户积分（changePoints append-only）。
     * 状态机：非「待审核」一律 409 拒绝重复审核（幂等提交方据此识别已受理）。
     */
    @PostMapping("/exchange/{id}/review")
    @RequirePerm("points:approve")
    @Transactional
    public MallExchange review(@PathVariable String id, @RequestBody @Valid ReviewCmd cmd) {
        MallExchange e = exchangeRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("兑换单不存在: " + id));
        if (!"待审核".equals(e.getStatus())) {
            throw new CustomerService.Conflict("兑换单已审核（当前状态：" + e.getStatus() + "），不可重复审核");
        }
        if (cmd.sign1() == null || cmd.sign1().isBlank() || cmd.sign2() == null || cmd.sign2().isBlank()) {
            throw new CustomerService.BadReq("兑换审核须双签（店长初审 + 运营复核）");
        }
        if (cmd.sign1().equals(cmd.sign2())) {
            throw new CustomerService.BadReq("双签不得为同一人");
        }
        OffsetDateTime now = OffsetDateTime.now();
        e.setSign1(cmd.sign1().trim());
        e.setSign1Role(trim(cmd.sign1Role()));
        e.setSignedAt1(now);
        e.setSign2(cmd.sign2().trim());
        e.setSign2Role(trim(cmd.sign2Role()));
        e.setSignedAt2(now);

        boolean reject = Boolean.TRUE.equals(cmd.reject());
        if (reject) {
            e.setStatus("已拒绝");
            e.setRejectReason(cmd.rejectReason() == null || cmd.rejectReason().isBlank()
                    ? "审核未通过" : cmd.rejectReason().trim());
            MallExchange saved = exchangeRepo.save(e);
            auditExchange(saved, "EXCHANGE_REJECT",
                    "兑换单 " + saved.getExchangeId() + " 双签驳回（" + e.getSign1() + "、" + e.getSign2()
                            + "），原因：" + saved.getRejectReason());
            return withName(saved);
        }

        // 通过：校验并扣减库存（-1 不限库存跳过），累加已兑数。
        MallProduct p = productRepo.findById(e.getProductId())
                .orElseThrow(() -> new CustomerService.NotFound("商品不存在: " + e.getProductId()));
        if (p.getStock() != -1 && p.getStock() < e.getQty()) {
            throw new CustomerService.Unprocessable("库存不足：剩余 " + p.getStock() + "，需 " + e.getQty());
        }
        if (p.getStock() != -1) {
            p.setStock(p.getStock() - e.getQty());
        }
        p.setRedeemedCount((p.getRedeemedCount() == null ? 0 : p.getRedeemedCount()) + e.getQty());
        productRepo.save(p);

        // 扣减客户积分（append-only，积分不足抛 Unprocessable 422）。
        Customer c = customerRepo.findById(e.getCustomerId()).orElse(null);
        try {
            customerService.changePoints(e.getCustomerId(), -e.getPointsSpent(),
                    "积分商城兑换：" + p.getProductName());
        } catch (CustomerService.BadReq ex) {
            // 积分不足 → 422 业务不可处理（区别于参数错误 400）。
            throw new CustomerService.Unprocessable(ex.getMessage());
        }

        e.setStatus("已通过");
        MallExchange saved = exchangeRepo.save(e);
        auditExchange(saved, "EXCHANGE_APPROVE",
                "兑换单 " + saved.getExchangeId() + " 双签通过（" + e.getSign1() + "、" + e.getSign2()
                        + "），客户「" + (c == null ? e.getCustomerId() : c.getName()) + "」兑换「"
                        + p.getProductName() + "」×" + e.getQty() + "，扣减 " + e.getPointsSpent()
                        + " 积分，商品已兑累计 " + p.getRedeemedCount());
        saved.setProductName(p.getProductName());
        saved.setCustomerName(c == null ? e.getCustomerId() : c.getName());
        return saved;
    }

    /**
     * 履约发放：「已通过」→「已发放」（实物发货/优惠券核销/项目预约到店）。
     * 状态机：仅「已通过」可履约，其余 409；幂等：已发放直接返回既有单，不重复落审计。
     */
    @PostMapping("/exchange/{id}/fulfill")
    @RequirePerm("points:approve")
    @Transactional
    public MallExchange fulfill(@PathVariable String id) {
        MallExchange e = exchangeRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("兑换单不存在: " + id));
        if ("已发放".equals(e.getStatus())) {
            return e;
        }
        if (!"已通过".equals(e.getStatus())) {
            throw new CustomerService.Conflict("兑换单须为「已通过」方可履约发放，当前状态：" + e.getStatus());
        }
        e.setStatus("已发放");
        e.setFulfilledAt(OffsetDateTime.now());
        MallExchange saved = exchangeRepo.save(e);
        auditExchange(saved, "EXCHANGE_FULFILL",
                "兑换单 " + saved.getExchangeId() + " 履约发放完成（" + e.getFulfilledAt() + "）");
        return withName(saved);
    }

    // ==================== 单号 / 工具 ====================

    /** 商品单号 MP+yyyyMMdd-6 位：库内当日最大号递增（synchronized 防并发重号，铁律 6）。 */
    private synchronized String nextProductNo() {
        String day = LocalDate.now().toString().replace("-", "");
        String prefix = "MP" + day + "-";
        long seq = productRepo.maxSeqOfDay(prefix + "%") + 1;
        return prefix + String.format("%06d", seq);
    }

    /** 兑换单号 EX+yyyyMMdd-6 位：库内当日最大号递增（synchronized 防并发重号，铁律 6）。 */
    private synchronized String nextExchangeNo() {
        String day = LocalDate.now().toString().replace("-", "");
        String prefix = "EX" + day + "-";
        long seq = exchangeRepo.maxSeqOfDay(prefix + "%") + 1;
        return prefix + String.format("%06d", seq);
    }

    /** 商品类型中文白名单校验（兼容前端历史英文码 PROJECT/PHYSICAL/COUPON/SERVICE 映射为中文）。 */
    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            throw new CustomerService.BadReq("商品类型不能为空");
        }
        String t = type.trim();
        String mapped = switch (t) {
            case "PROJECT", "项目" -> "项目";
            case "PHYSICAL", "实物" -> "实物";
            case "COUPON", "优惠券" -> "优惠券";
            case "SERVICE", "服务" -> "服务";
            default -> null;
        };
        if (mapped == null) {
            throw new CustomerService.BadReq("商品类型须为：项目 / 实物 / 优惠券 / 服务");
        }
        return mapped;
    }

    private static String stockText(Integer stock) {
        return stock != null && stock == -1 ? "不限" : String.valueOf(stock);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private static String actor() {
        String a = DataScope.currentActor();
        return (a == null || a.isBlank()) ? "system" : a;
    }

    /** 审计 payload 序列化为合法 JSON（audit_log.payload 为 jsonb，散文会被 PG 拒绝）。 */
    private static String json(Object payload) {
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (Exception ex) {
            return "{}";
        }
    }

    /** 商品动作审计 payload（额外字段 + authority + summary）。 */
    private Map<String, Object> productPayload(MallProduct p, Map<String, ?> extra) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("productId", p.getProductId());
        m.put("name", p.getProductName());
        m.put("type", p.getProductType());
        m.put("pointsPrice", p.getPointsPrice());
        m.put("stock", p.getStock());
        m.put("redeemedCount", p.getRedeemedCount());
        m.put("status", p.getStatus());
        m.put("authority", "customer");
        if (extra != null) m.putAll(extra);
        return m;
    }

    /** 兑换动作审计 payload（关联商品名/客户名 + authority + summary）。 */
    private Map<String, Object> exchangePayload(MallExchange e, String customerName, String productName) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exchangeId", e.getExchangeId());
        m.put("productId", e.getProductId());
        m.put("productName", productName);
        m.put("customerId", e.getCustomerId());
        m.put("customerName", customerName);
        m.put("qty", e.getQty());
        m.put("pointsSpent", e.getPointsSpent());
        m.put("status", e.getStatus());
        if (e.getShipName() != null) m.put("shipName", e.getShipName());
        if (e.getShipAddress() != null) m.put("shipAddress", e.getShipAddress());
        m.put("authority", "customer");
        return m;
    }

    /** 兑换单审计：补查商品/客户名后落审计（审核/履约动作复用）。 */
    private void auditExchange(MallExchange e, String action, String summary) {
        String productName = productRepo.findById(e.getProductId())
                .map(MallProduct::getProductName).orElse(e.getProductId());
        String customerName = customerRepo.findById(e.getCustomerId())
                .map(Customer::getName).orElse(e.getCustomerId());
        Map<String, Object> payload = exchangePayload(e, customerName, productName);
        if (e.getRejectReason() != null) payload.put("rejectReason", e.getRejectReason());
        if (e.getSign1() != null) payload.put("sign1", e.getSign1());
        if (e.getSign2() != null) payload.put("sign2", e.getSign2());
        payload.put("summary", summary);
        audit.record("MALL", e.getExchangeId(), actor(), action, json(payload));
    }

    /** 批量注入兑换单的客户名/商品名（同库批量查，避免 N+1；缺失回退为 ID）。 */
    private List<MallExchange> withNames(List<MallExchange> list) {
        if (list == null || list.isEmpty()) {
            return list;
        }
        List<String> customerIds = list.stream().map(MallExchange::getCustomerId).distinct().toList();
        List<String> productIds = list.stream().map(MallExchange::getProductId).distinct().toList();
        Map<String, String> customerNames = new LinkedHashMap<>();
        customerRepo.findAllById(customerIds).forEach(c -> customerNames.put(c.getCustomerId(), c.getName()));
        Map<String, String> productNames = new LinkedHashMap<>();
        productRepo.findAllById(productIds).forEach(p -> productNames.put(p.getProductId(), p.getProductName()));
        for (MallExchange e : list) {
            e.setCustomerName(customerNames.getOrDefault(e.getCustomerId(), e.getCustomerId()));
            e.setProductName(productNames.getOrDefault(e.getProductId(), e.getProductId()));
        }
        return list;
    }

    /** 单条注入客户名/商品名（审核/履约返回）。 */
    private MallExchange withName(MallExchange e) {
        return withNames(List.of(e)).get(0);
    }

    // ==================== 命令 DTO ====================

    public record ProductCmd(
            @NotBlank String name, @NotBlank String type,
            @NotNull Integer pointsPrice, Integer stock, String cover, String description) {}

    public record AdjustCmd(Integer stock, Integer pointsPrice) {}

    public record ReviewCmd(
            String sign1, String sign1Role,
            String sign2, String sign2Role,
            Boolean reject, String rejectReason) {}

    /** C 端兑换申请：商品 + 客户 + 数量（积分按商品定价 × 数量后端计算，防止篡改）；实物须带收货信息；clientToken 幂等。 */
    public record PlaceExchangeCmd(
            @NotBlank String productId,
            @NotBlank String customerId,
            Integer qty,
            String shipName, String shipPhone, String shipAddress,
            String clientToken) {}

    /** 积分规则保存：消费倍率/抵扣比例/有效期 + 签到/生日/推荐/手动调分开关（未上线字段配置入库）。 */
    public record RuleCmd(
            BigDecimal earnRate, BigDecimal redeemRatio, Integer expireMonths,
            Integer signInReward, BigDecimal birthdayMultiplier, Integer referralReward,
            Boolean manualGrantEnabled) {}
}
