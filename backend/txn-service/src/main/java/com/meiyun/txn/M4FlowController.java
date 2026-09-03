package com.meiyun.txn;

import com.meiyun.common.contraindication.ContraindicationChecker;
import com.meiyun.common.contraindication.ContraindicationChecker.*;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import com.meiyun.txn.audit.AuditRecorder;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M4 交易链核心控制器（M4-06 客情 / M4-10 开单 / M4-13 确认 / M4-14 划扣 / M4-15 收款）。
 *
 * 红线串联：
 * ① 禁忌硬阻断：开单时自动取最新客情 → 三态判定 → RED 须双签豁免方可放行；
 * ② 划扣账实校验：扣次/扣额须 ≤ 卡剩余次数/余额，同事务扣减；
 * ③ 审计：开单/豁免/划扣动作全部落 append-only 链。
 * ④ 金额铁律：订单额=各收费子项小计之和（单位「分」），开单同事务落 order_item，账实一致。
 */
@RestController
@RequestMapping("/api/txn")
public class M4FlowController {

    private final ConsultationRepository consultRepo;
    private final TxnOrderRepository orderRepo;
    private final OrderItemRepository itemRepo;
    private final WriteoffRepository writeoffRepo;
    private final MemberCardRepository cardRepo;
    private final AuditRecorder audit;
    private final ApptRefNameResolver names;
    private final ConsultPlanService planService;
    private final OrderNoGenerator orderNoGen;
    private final PaymentService paymentService;

    public M4FlowController(ConsultationRepository consultRepo, TxnOrderRepository orderRepo,
                            OrderItemRepository itemRepo,
                            WriteoffRepository writeoffRepo, MemberCardRepository cardRepo,
                            AuditRecorder audit, ApptRefNameResolver names,
                            ConsultPlanService planService, OrderNoGenerator orderNoGen,
                            PaymentService paymentService) {
        this.consultRepo = consultRepo;
        this.orderRepo = orderRepo;
        this.itemRepo = itemRepo;
        this.writeoffRepo = writeoffRepo;
        this.cardRepo = cardRepo;
        this.audit = audit;
        this.names = names;
        this.planService = planService;
        this.orderNoGen = orderNoGen;
        this.paymentService = paymentService;
    }

    // ==================== M4-06 客情咨询 ====================

    @PostMapping("/consultation")
    @RequirePerm("consult:create")
    public Consultation createConsultation(@RequestBody @Valid ConsultCmd cmd) {
        Consultation c = new Consultation();
        c.setConsultId(nextNo("CS"));
        c.setCustomerId(cmd.customerId());
        c.setStoreCode(cmd.storeCode());
        c.setAllergyHistory(cmd.allergyHistory());
        c.setDrugAllergy(cmd.drugAllergy());
        c.setScarConstitution(nvl(cmd.scarConstitution()));
        c.setPregnancy(nvl(cmd.pregnancy()));
        c.setCoagulationAbn(nvl(cmd.coagulationAbn()));
        c.setSkinStatus(cmd.skinStatus());
        c.setNeeds(cmd.needs());
        c.setConsultant(cmd.consultant());
        Consultation saved = consultRepo.save(c);
        audit.record("CONSULT", saved.getConsultId(), DataScope.currentActor(),
                "CREATE", "{\"customer\":\"" + cmd.customerId() + "\"}");
        return saved;
    }

    /** 客户客情咨询列表：数据域强制注入（SELF 咨询师只见本人客情、STORE 本店、REGION 本区、GROUP 全量）。 */
    @GetMapping("/consultation/{customerId}")
    @RequirePerm({"consult:view", "customer:view"})
    public List<Consultation> consultations(@PathVariable String customerId) {
        Specification<Consultation> spec = DataScope.<Consultation>ownedSpec("storeCode", "consultant")
                .and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        return consultRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
    }

    // ==================== M4-10 开单（禁忌硬阻断） ====================

    /**
     * 创建订单：自动取客户最新客情做禁忌三态校验。
     * GREEN/YELLOW → 正常创建；RED → 400 硬阻断（除非带合法豁免参数）。
     * 金额铁律：订单总额 = 各收费子项小计之和（单位「分」）；传 items 时以子项合计为准并同事务落 order_item。
     */
    @PostMapping("/order")
    @RequirePerm("cashier:create")
    @Transactional
    public OrderView createOrder(@RequestBody @Valid OrderCmd cmd) {
        // ---- 外键存在性（服务间调用，不直读别域表） ----
        if (!names.customerNames(List.of(cmd.customerId())).containsKey(cmd.customerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在: " + cmd.customerId());
        }
        if (cmd.storeCode() != null && !cmd.storeCode().isBlank()
                && !names.storeNames(List.of(cmd.storeCode())).containsKey(cmd.storeCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "门店不存在: " + cmd.storeCode());
        }

        // ---- 金额：子项合计 vs 传入 amount 对账 ----
        long itemsTotal = 0L;
        List<OrderItem> items = new ArrayList<>();
        if (cmd.items() != null && !cmd.items().isEmpty()) {
            int line = 1;
            for (OrderItemCmd it : cmd.items()) {
                if (it.itemName() == null || it.itemName().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + line + " 行收费项目名称不能为空");
                }
                int qty = it.qty() == null || it.qty() < 1 ? 1 : it.qty();
                long price = it.unitPrice() == null ? 0L : it.unitPrice();
                if (price < 0) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "第 " + line + " 行单价不能为负");
                }
                long sub = price * qty;
                itemsTotal += sub;
                OrderItem oi = new OrderItem();
                oi.setOrderNo(null); // 订单号生成后回填
                oi.setLineNo(line);
                oi.setItemName(it.itemName());
                oi.setQty(qty);
                oi.setUnitPrice(price);
                oi.setAmount(sub);
                items.add(oi);
                line++;
            }
        }
        long amount;
        if (!items.isEmpty()) {
            amount = itemsTotal;
            if (cmd.amount() != null && cmd.amount() != itemsTotal) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "订单金额与收费子项合计不一致：订单 " + cmd.amount() + " 分 ≠ 子项合计 " + itemsTotal + " 分");
            }
        } else {
            if (cmd.amount() == null || cmd.amount() <= 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单金额必须为正（单位：分）");
            }
            amount = cmd.amount();
        }

        // 取最新客情档案做禁忌判定
        Consultation latest = consultRepo.findTopByCustomerIdOrderByCreatedAtDesc(cmd.customerId()).orElse(null);
        Profile profile = latest == null ? null : new Profile(
                latest.getAllergyHistory(), latest.getDrugAllergy(),
                latest.getScarConstitution(), latest.getPregnancy(), latest.getCoagulationAbn());
        Result check = ContraindicationChecker.check(profile, cmd.project());

        TxnOrder o = new TxnOrder();
        o.setOrderNo(orderNoGen.nextOrderNo());
        o.setCustomerId(cmd.customerId());
        o.setStoreCode(cmd.storeCode());
        o.setProject(cmd.project());
        o.setAmount(amount);
        o.setConsultant(cmd.consultant());
        o.setStatus("待签核");

        if (check.red()) {
            // RED 硬阻断：须带合法双签豁免方可放行
            List<String> exErr = ContraindicationChecker.validateExemption(
                    cmd.exemptionSign1(), cmd.exemptionSign2(),
                    Boolean.TRUE.equals(cmd.exemptionSign2Licensed()), cmd.exemptionNote());
            if (!exErr.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "禁忌硬阻断(RED)：" + String.join("; ", check.hits())
                                + " | 豁免校验未通过：" + String.join("; ", exErr));
            }
            o.setContraCheck("YELLOW");  // 豁免后降为 YELLOW
            o.setContraDetail("硬阻断豁免：" + String.join("; ", check.hits())
                    + " | 豁免说明：" + cmd.exemptionNote());
            o.setExemptionSign1(cmd.exemptionSign1());
            o.setExemptionSign2(cmd.exemptionSign2());
            audit.record("CONTRA_EXEMPT", o.getOrderNo(), DataScope.currentActor(),
                    "EXEMPT", "{\"hits\":\"" + String.join(";", check.hits()) + "\"}");
        } else {
            o.setContraCheck(check.level().name());
            if (!check.hits().isEmpty()) {
                o.setContraDetail(String.join("; ", check.hits()));
            }
        }

        orderRepo.save(o);
        // 同事务落收费子项（订单号回填）
        for (OrderItem oi : items) {
            oi.setOrderNo(o.getOrderNo());
            itemRepo.save(oi);
        }
        audit.record("ORDER", o.getOrderNo(), DataScope.currentActor(),
                "CREATE", "{\"project\":\"" + cmd.project() + "\",\"amount\":" + amount
                        + ",\"items\":" + items.size() + ",\"contra\":\"" + o.getContraCheck() + "\"}");
        return toOrderView(o, items);
    }

    /**
     * 订单分页列表（收银台/订单页）：按状态/门店过滤，富化客户名/门店名/咨询师中文名。
     * 数据域强制注入：SELF 咨询师只见本人订单、STORE 本店、REGION 本区、GROUP 全量。
     */
    @GetMapping("/order")
    @RequirePerm("cashier:view")
    public Page<OrderView> listOrders(
            @PageableDefault(size = 20) Pageable pageable,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String storeCode) {
        Specification<TxnOrder> spec = DataScope.ownedSpec("storeCode", "consultant");
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        Page<TxnOrder> page = orderRepo.findAll(spec,
                PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                        Sort.by(Sort.Order.desc("createdAt"))));
        List<TxnOrder> orders = page.getContent();
        Map<String, String> custNames = names.customerNames(orders.stream().map(TxnOrder::getCustomerId).toList());
        Map<String, String> storeNames = names.storeNames(orders.stream().map(TxnOrder::getStoreCode).toList());
        Map<String, String> consultantNames = names.staffNames(orders.stream().map(TxnOrder::getConsultant).toList());
        // 批量取子项
        Map<String, List<OrderItem>> itemsByOrder = loadItems(orders.stream().map(TxnOrder::getOrderNo).toList());
        // 批量取支付明细
        Map<String, List<OrderPayment>> paysByOrder =
                paymentService.loadByOrders(orders.stream().map(TxnOrder::getOrderNo).toList());

        return page.map(o -> new OrderView(
                o.getOrderNo(), o.getCustomerId(), custNames.get(o.getCustomerId()),
                o.getStoreCode(), o.getStoreCode() == null ? null : storeNames.get(o.getStoreCode()),
                o.getProject(), o.getAmount(), o.getStatus(),
                consultantNames.get(o.getConsultant()), o.getContraCheck(),
                o.getCreatedAt(),
                toItemViews(itemsByOrder.getOrDefault(o.getOrderNo(), List.of())),
                sumPosted(paysByOrder.get(o.getOrderNo())),
                toPaymentViews(paysByOrder.getOrDefault(o.getOrderNo(), List.of()))));
    }

    @GetMapping("/order/{no}")
    @RequirePerm("cashier:view")
    public OrderView getOrder(@PathVariable String no) {
        TxnOrder o = requireOrder(no);
        List<OrderItem> items = itemRepo.findByOrderNoIn(List.of(no));
        return toOrderView(o, items);
    }

    // ==================== M4-13 订单确认（双签） ====================

    @PostMapping("/order/{no}/confirm")
    @RequirePerm("cashier:sign")
    @Transactional
    public OrderView confirmOrder(@PathVariable String no, @RequestBody ConfirmCmd cmd) {
        TxnOrder o = getOrderInStatus(no, "待签核");
        if (cmd.sign1() == null || cmd.sign1().isBlank() || cmd.sign2() == null || cmd.sign2().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单确认须双签（签核人 1、签核人 2 均不能为空）");
        }
        if (cmd.sign1().equals(cmd.sign2())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "双签不得为同一人");
        }
        o.setSign1(cmd.sign1());
        o.setSign2(cmd.sign2());
        o.setStatus("待收款");
        orderRepo.save(o);
        audit.record("ORDER", no, DataScope.currentActor(), "CONFIRM",
                "{\"sign1\":\"" + cmd.sign1() + "\",\"sign2\":\"" + cmd.sign2() + "\"}");
        return toOrderView(o, itemRepo.findByOrderNoIn(List.of(no)));
    }

    // ==================== M4-15 收款 ====================

    /**
     * 收款：登记一笔支付明细（支持组合支付 / 部分收款 / 现金找零），收齐即「待收款 → 已收款」。
     * body: {"operator":"SE002","method":"cash|card|wxpay|alipay|balance","tendered":客户实付(分)}。
     * 幂等：已收款单重复收款直接返回当前态（不重复记流水）。收齐联动方案单 READY_PAY → PAID。
     */
    @PostMapping("/order/{no}/pay")
    @RequirePerm("cashier:sign")
    @Transactional
    public PaymentService.PayResult payOrder(@PathVariable String no, @RequestBody(required = false) Map<String, Object> body) {
        String operator = (body == null || body.get("operator") == null)
                ? "system" : String.valueOf(body.get("operator"));
        String method = (body == null || body.get("method") == null)
                ? "wxpay" : String.valueOf(body.get("method"));
        long tendered = parseAmount(body == null ? null : body.get("tendered"));
        return paymentService.pay(no, method, tendered, operator);
    }

    /** 某订单的支付明细流水（已收记录 / 找零对账）。 */
    @GetMapping("/order/{no}/payments")
    @RequirePerm("cashier:view")
    public List<PaymentService.PaymentView> orderPayments(@PathVariable String no) {
        requireOrder(no);
        return paymentService.listByOrder(no);
    }

    private Long parseAmount(Object v) {
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v).trim()); }
        catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "收款金额格式非法: " + v);
        }
    }

    /** 取消订单：待签核/待收款 可取消（已收款不可取消，须走退款流程）。 */
    @PostMapping("/order/{no}/cancel")
    @RequirePerm("cashier:create")
    @Transactional
    public OrderView cancelOrder(@PathVariable String no, @RequestBody(required = false) Map<String, String> body) {
        TxnOrder o = requireOrder(no);
        if ("已取消".equals(o.getStatus())) {
            return toOrderView(o, itemRepo.findByOrderNoIn(List.of(no))); // 幂等
        }
        if ("已收款".equals(o.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "已收款订单不可取消，请走退款流程");
        }
        o.setStatus("已取消");
        orderRepo.save(o);
        audit.record("ORDER", no, DataScope.currentActor(), "CANCEL", "{\"amount\":" + o.getAmount() + "}");
        return toOrderView(o, itemRepo.findByOrderNoIn(List.of(no)));
    }

    // ==================== M4-14 划扣核销（账实校验） ====================

    /**
     * 划扣：从会员卡扣次/扣额，同事务校验账实一致。
     * 校验：① 卡存在且在用 ② 剩余次数 ≥ 扣次 ③ 卡余额 ≥ 扣额（若走卡余额）。
     */
    @PostMapping("/writeoff")
    @RequirePerm("writeoff:create")
    @Transactional
    public WriteoffRecord writeoff(@RequestBody @Valid WriteoffCmd cmd) {
        MemberCard card = cardRepo.findById(cmd.cardNo())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "卡不存在: " + cmd.cardNo()));
        if (!"在用".equals(card.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "卡状态非「在用」: " + card.getStatus());
        }
        // 账实校验：次数
        if (card.getRemainTimes() < cmd.timesUsed()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "账实校验失败：卡剩余次数 " + card.getRemainTimes() + " < 划扣次数 " + cmd.timesUsed());
        }
        // 账实校验：余额（若划扣金额 > 0 则从卡余额扣）
        if (cmd.amount() > 0 && card.getBalance() < cmd.amount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "账实校验失败：卡余额 " + card.getBalance() + " 分 < 划扣金额 " + cmd.amount() + " 分");
        }

        // 同事务扣减
        card.setRemainTimes(card.getRemainTimes() - cmd.timesUsed());
        if (cmd.amount() > 0) {
            card.setBalance(card.getBalance() - cmd.amount());
        }
        if (card.getRemainTimes() == 0) {
            card.setStatus("已用完");
        }
        cardRepo.save(card);

        WriteoffRecord w = new WriteoffRecord();
        w.setWriteoffId(nextWriteoffNo());
        w.setOrderNo(cmd.orderNo());
        w.setCardNo(cmd.cardNo());
        w.setCustomerId(card.getCustomerId());
        w.setStoreCode(cmd.storeCode());
        w.setProject(cmd.project());
        w.setTimesUsed(cmd.timesUsed());
        w.setAmount(cmd.amount());
        w.setOperator(cmd.operator());
        w.setStatus("DONE");
        w.setSign1(cmd.sign1());
        w.setSign2(cmd.sign2());
        writeoffRepo.save(w);

        audit.record("WRITEOFF", w.getWriteoffId(), DataScope.currentActor(),
                "WRITEOFF", "{\"card\":\"" + cmd.cardNo() + "\",\"times\":" + cmd.timesUsed()
                        + ",\"amount\":" + cmd.amount() + "}");
        return w;
    }

    @GetMapping("/writeoff/{id}")
    @RequirePerm("writeoff:view")
    public WriteoffRecord getWriteoff(@PathVariable String id) {
        WriteoffRecord w = writeoffRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        // 详情数据域：门店归属校验，越权统一 404
        if (!DataScope.canReadStore(w.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return w;
    }

    /** 划扣记录列表：数据域强制注入（STORE 本店、REGION 本区、GROUP 全量）。 */
    @GetMapping("/writeoff")
    @RequirePerm("writeoff:view")
    public List<WriteoffRecord> listWriteoff() {
        return writeoffRepo.findAll(DataScope.storeSpec("storeCode"),
                Sort.by(Sort.Order.desc("createdAt")));
    }

    // ==================== 订单整单核销（/writeoff 核销页） ====================

    /**
     * 订单整单核销：对一笔「已收款」订单做履约完结核销（一单一次，演示期不拆次）。
     * 与卡扣次划扣（POST /writeoff）相区分：本端点不动会员卡，只写订单维度核销记录（card_no 为空）并回写订单状态。
     * 四件套：① 订单存在 + 状态须「已收款」（中文错误）；② 幂等——重复核销直接返回既有记录；
     * ③ 全程审计（WRITEOFF/ORDER_WRITEOFF，JSON payload）；④ 金额取订单额（单位分），核销号 DB 当日序号。
     */
    @PostMapping("/order-writeoff")
    @RequirePerm("writeoff:sign")
    @Transactional
    public synchronized WriteoffView orderWriteoff(@RequestBody @Valid OrderWriteoffCmd cmd) {
        TxnOrder o = requireOrder(cmd.orderNo());
        WriteoffRecord exist = writeoffRepo.findByCardNoIsNullOrderByCreatedAtDesc().stream()
                .filter(w -> cmd.orderNo().equals(w.getOrderNo()))
                .findFirst().orElse(null);
        if (exist != null) {
            return toWriteoffView(exist); // 幂等：该订单已核销，返回当前态
        }
        if (!"已收款".equals(o.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "订单状态须为「已收款」方可核销，当前: " + o.getStatus()
                            + "（待收款订单请先收款，已取消/已核销订单不可核销）");
        }

        List<OrderItem> items = itemRepo.findByOrderNoIn(List.of(cmd.orderNo()));
        int timesUsed = items.isEmpty() ? 1 : items.get(0).getQty();
        String project = items.isEmpty() ? o.getProject() : items.get(0).getItemName();

        WriteoffRecord w = new WriteoffRecord();
        w.setWriteoffId(nextWriteoffNo());
        w.setOrderNo(o.getOrderNo());
        w.setCardNo(null); // 订单维度核销，不关联会员卡
        w.setCustomerId(o.getCustomerId());
        w.setStoreCode(o.getStoreCode());
        w.setProject(project);
        w.setTimesUsed(timesUsed);
        w.setAmount(o.getAmount());
        w.setOperator(cmd.operator());
        w.setStatus("DONE");
        writeoffRepo.save(w);

        // 回写订单状态：已收款 → 已核销（整单一次性核销，无部分核销场景）
        o.setStatus("已核销");
        o.setWriteoffAt(w.getCreatedAt() != null ? w.getCreatedAt() : OffsetDateTime.now());
        orderRepo.save(o);

        audit.record("WRITEOFF", w.getWriteoffId(), DataScope.currentActor(), "ORDER_WRITEOFF",
                "{\"orderNo\":\"" + o.getOrderNo() + "\",\"customer\":\"" + o.getCustomerId()
                        + "\",\"amount\":" + o.getAmount() + ",\"times\":" + timesUsed + "}");
        return toWriteoffView(w);
    }

    /**
     * 订单核销记录列表（/writeoff 页「已核销/异常」tab）：status 传 DONE/ABNORMAL 过滤，不传则全量。
     * 数据域强制注入：仅订单维度核销（card_no 为空）+ 门店数据域。
     */
    @GetMapping("/order-writeoff")
    @RequirePerm("writeoff:view")
    public List<WriteoffView> listOrderWriteoff(@RequestParam(required = false) String status) {
        Specification<WriteoffRecord> spec = DataScope.<WriteoffRecord>storeSpec("storeCode")
                .and((root, q, cb) -> cb.isNull(root.get("cardNo")));
        if (status != null && !status.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), status));
        }
        List<WriteoffRecord> list = writeoffRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
        Map<String, String> custNames = names.customerNames(list.stream().map(WriteoffRecord::getCustomerId).toList());
        Map<String, String> staffNames = names.staffNames(list.stream().map(WriteoffRecord::getOperator).toList());
        return list.stream().map(w -> toWriteoffView(w, custNames, staffNames)).toList();
    }

    // ==================== M2 卡项疗程（只读列表） ====================

    /**
     * 会员卡列表：支持 customerId / storeCode 过滤（M2 卡项疗程页）。
     * 数据域强制注入：SELF/STORE 本店、REGION 本区、GROUP 全量。
     */
    @GetMapping("/card")
    @RequirePerm("cashier:view")
    public List<MemberCard> listCards(@RequestParam(required = false) String customerId,
                                      @RequestParam(required = false) String storeCode) {
        Specification<MemberCard> spec = DataScope.storeSpec("storeCode");
        if (customerId != null && !customerId.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("customerId"), customerId));
        }
        if (storeCode != null && !storeCode.isBlank()) {
            spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), storeCode));
        }
        return cardRepo.findAll(spec, Sort.by(Sort.Order.desc("createdAt")));
    }

    // ==================== 内部方法 ====================

    /** 订单读取 + 数据域校验（详情/收款/取消/核销/支付明细共用，越权统一 404）。 */
    private TxnOrder requireOrder(String no) {
        TxnOrder o = orderRepo.findById(no)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadOwned(o.getStoreCode(), o.getConsultant())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        return o;
    }

    private TxnOrder getOrderInStatus(String no, String expected) {
        TxnOrder o = requireOrder(no);
        if (!expected.equals(o.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "订单状态须为「" + expected + "」，当前: " + o.getStatus());
        }
        return o;
    }

    /** 生成当日不重号核销号：WO + yyyyMMdd + - + 6 位序号（取当日最大序号 +1）。 */
    private synchronized String nextWriteoffNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max = 0L;
        // 核销号同样取当日最大序号（writeoff 表），用原生查询兜底
        try {
            max = writeoffRepo.maxSeqOfDay("WO" + day + "-%");
        } catch (Exception ignore) { }
        return "WO" + day + "-" + String.format("%06d", max + 1);
    }

    /** 咨询号：CS + 日期 + 序号（咨询并发低，沿用原生成方式）。 */
    private String nextNo(String prefix) {
        long n = new java.util.concurrent.atomic.AtomicLong(System.nanoTime() % 1_000_000).incrementAndGet() % 1_000_000;
        return prefix + OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))
                + "-" + String.format("%06d", n);
    }

    private String nvl(String v) { return v == null ? "否" : v; }

    /** 批量取订单子项并按 orderNo 归组。 */
    private Map<String, List<OrderItem>> loadItems(List<String> orderNos) {
        Map<String, List<OrderItem>> map = new LinkedHashMap<>();
        if (orderNos.isEmpty()) return map;
        itemRepo.findByOrderNoIn(orderNos).stream()
                .sorted((a, b) -> Integer.compare(a.getLineNo(), b.getLineNo()))
                .forEach(it -> map.computeIfAbsent(it.getOrderNo(), k -> new ArrayList<>()).add(it));
        return map;
    }

    private List<CustomerViewController.OrderItemView> toItemViews(List<OrderItem> items) {
        List<CustomerViewController.OrderItemView> out = new ArrayList<>();
        for (OrderItem it : items) {
            out.add(new CustomerViewController.OrderItemView(it.getItemName(), it.getQty(), it.getUnitPrice(), it.getAmount()));
        }
        return out;
    }

    private OrderView toOrderView(TxnOrder o, List<OrderItem> items) {
        Map<String, String> cust = names.customerNames(List.of(o.getCustomerId()));
        Map<String, String> stores = o.getStoreCode() == null ? Map.of()
                : names.storeNames(List.of(o.getStoreCode()));
        Map<String, String> consultants = o.getConsultant() == null ? Map.of()
                : names.staffNames(List.of(o.getConsultant()));
        List<OrderPayment> pays = paymentService.loadByOrders(List.of(o.getOrderNo()))
                .getOrDefault(o.getOrderNo(), List.of());
        return new OrderView(
                o.getOrderNo(), o.getCustomerId(), cust.get(o.getCustomerId()),
                o.getStoreCode(), o.getStoreCode() == null ? null : stores.get(o.getStoreCode()),
                o.getProject(), o.getAmount(), o.getStatus(),
                o.getConsultant() == null ? null : consultants.get(o.getConsultant()),
                o.getContraCheck(),
                o.getCreatedAt(), toItemViews(items),
                sumPosted(pays), toPaymentViews(pays));
    }

    private Long sumPosted(List<OrderPayment> pays) {
        return pays == null ? 0L : pays.stream().mapToLong(OrderPayment::getPostedAmount).sum();
    }

    private List<PaymentService.PaymentView> toPaymentViews(List<OrderPayment> pays) {
        if (pays == null) return List.of();
        return pays.stream()
                .map(p -> new PaymentService.PaymentView(p.getPaymentId(), p.getOrderNo(),
                        p.getPayMethod(), p.getCashTendered(), p.getPostedAmount(),
                        p.getChangeAmount(), p.getPaidAfter(), p.getOperator()))
                .toList();
    }

    // ==================== 读模型 / 命令 DTO ====================

    /** 订单核销读模型（/writeoff 页）：冗余客户名/操作人名；金额单位「分」，前端适配层转元。 */
    public record WriteoffView(
            String writeoffNo, String orderNo, String customerId, String customerName,
            String project, Integer timesUsed, Long amount,
            String operator, String operatorName,
            String status, String abnormalReason, OffsetDateTime createdAt) {}

    private WriteoffView toWriteoffView(WriteoffRecord w) {
        Map<String, String> custNames = names.customerNames(List.of(w.getCustomerId()));
        Map<String, String> staffNames = names.staffNames(List.of(w.getOperator()));
        return toWriteoffView(w, custNames, staffNames);
    }

    private WriteoffView toWriteoffView(WriteoffRecord w, Map<String, String> custNames, Map<String, String> staffNames) {
        return new WriteoffView(
                w.getWriteoffId(), w.getOrderNo(), w.getCustomerId(),
                custNames.getOrDefault(w.getCustomerId(), w.getCustomerId()),
                w.getProject(), w.getTimesUsed(), w.getAmount(),
                w.getOperator(),
                w.getOperator() == null ? null : staffNames.getOrDefault(w.getOperator(), w.getOperator()),
                w.getStatus() == null ? "DONE" : w.getStatus(),
                w.getAbnormalReason(), w.getCreatedAt());
    }

    /** 订单整单核销命令：operator 为执行人工号（解析姓名展示）。 */
    public record OrderWriteoffCmd(@NotBlank String orderNo, String operator) {}

    /** 订单读模型：冗余客户名/门店名/咨询师中文名 + 收费子项 + 收款明细；金额单位「分」。 */
    public record OrderView(
            String orderNo, String customerId, String customerName,
            String storeCode, String storeName,
            String project, Long amount, String status,
            String consultantName, String contraCheck,
            OffsetDateTime createdAt,
            List<CustomerViewController.OrderItemView> items,
            /** 累计已入账（分）；待收款单为部分收款累计，已收款单等于 amount。 */
            Long paidAmount,
            /** 支付明细流水（组合支付/找零）。 */
            List<PaymentService.PaymentView> payments) {}

    public record OrderItemCmd(String itemName, Integer qty, Long unitPrice) {}

    public record ConsultCmd(
            @NotBlank String customerId, String storeCode,
            String allergyHistory, String drugAllergy,
            String scarConstitution, String pregnancy, String coagulationAbn,
            String skinStatus, String needs, String consultant) {}

    public record OrderCmd(
            @NotBlank String customerId, String storeCode,
            @NotBlank String project, Long amount, String consultant,
            List<OrderItemCmd> items,
            // 豁免参数（仅 RED 时需要）
            String exemptionSign1, String exemptionSign2,
            Boolean exemptionSign2Licensed, String exemptionNote) {}

    public record ConfirmCmd(String sign1, String sign2) {}

    public record WriteoffCmd(
            String orderNo, @NotBlank String cardNo, String storeCode,
            @NotBlank String project, @NotNull Integer timesUsed, @NotNull Long amount,
            String operator, String sign1, String sign2) {}
}
