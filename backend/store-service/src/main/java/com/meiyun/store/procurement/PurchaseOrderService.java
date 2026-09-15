package com.meiyun.store.procurement;

import com.meiyun.security.DataScope;
import com.meiyun.store.Store;
import com.meiyun.store.StoreRepository;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import com.meiyun.store.consumable.ConsumableService;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采购供应链域服务（B49 卡5）：供应商档案 + 采购订单六态状态机 + 收货联动耗材库存。
 *
 * <p>PO 状态机：DRAFT 草稿 → SUBMITTED 待审批 → APPROVED 待入库 → PARTIAL 部分入库 → RECEIVED 已入库；
 * SUBMITTED 可驳回回 DRAFT；DRAFT/SUBMITTED/APPROVED/PARTIAL 可作废 CANCELLED。
 * 金额一律 Long 分；sign_tier 提交时按总额定格（≥2,000,000 分 GROUP / ≥500,000 REGION / 余 STORE）。
 *
 * <p>收货逐 SKU 同事务调 {@link ConsumableService#stockIn}，库存批次号 = {@code receiptNo + "-" + lineNo}
 * （确定性幂等，重试不重复入库）；SKU 未建档由 stockIn 抛 404「请先建档」，整批回滚。
 */
@Service
public class PurchaseOrderService {

    /** 审批层级阈值（分）：5,000 元 / 20,000 元，与前端设置中心 L2/L3 阈值同口径。 */
    static final long TIER_REGION_FEN = 500_000L;
    static final long TIER_GROUP_FEN = 2_000_000L;

    private final SupplierRepository supplierRepo;
    private final PurchaseOrderRepository poRepo;
    private final PurchaseOrderItemRepository itemRepo;
    private final GoodsReceiptRepository receiptRepo;
    private final StoreRepository storeRepo;
    private final PoNoGenerator poNoGenerator;
    private final ConsumableService consumableService;
    private final ConsumableAuditRecorder audit;

    public PurchaseOrderService(SupplierRepository supplierRepo,
                                PurchaseOrderRepository poRepo,
                                PurchaseOrderItemRepository itemRepo,
                                GoodsReceiptRepository receiptRepo,
                                StoreRepository storeRepo,
                                PoNoGenerator poNoGenerator,
                                ConsumableService consumableService,
                                ConsumableAuditRecorder audit) {
        this.supplierRepo = supplierRepo;
        this.poRepo = poRepo;
        this.itemRepo = itemRepo;
        this.receiptRepo = receiptRepo;
        this.storeRepo = storeRepo;
        this.poNoGenerator = poNoGenerator;
        this.consumableService = consumableService;
        this.audit = audit;
    }

    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    static ResponseStatusException unprocessable(String msg) {
        return new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, msg);
    }

    /** 404 数据不存在/无权查看（不泄露存在性，与 DataScope 单条断言口径一致）。 */
    static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "采购单不存在");
    }

    // ============================================================
    // 供应商
    // ============================================================

    /** 供应商列表（集团级全局，不分门店；ACTIVE 在前）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listSuppliers(String keyword) {
        String kw = isBlank(keyword) ? null : keyword.trim();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Supplier s : supplierRepo.findAll()) {
            if (kw != null && !(s.getName().contains(kw) || s.getCode().contains(kw)
                    || (s.getContact() != null && s.getContact().contains(kw)))) {
                continue;
            }
            out.add(supplierRow(s));
        }
        out.sort((a, b) -> Boolean.compare(! "ACTIVE".equals(a.get("status")), ! "ACTIVE".equals(b.get("status"))));
        return out;
    }

    /** 新建供应商（幂等：code 重复 409）。 */
    @Transactional
    public Supplier createSupplier(String code, String name, String contact, String phone,
                                   Integer paymentTerms, Boolean qualified, String status,
                                   String remark, String operator) {
        if (isBlank(code) || isBlank(name)) {
            throw badReq("供应商编码、名称均不能为空");
        }
        if (supplierRepo.findByCode(code.trim()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "供应商编码「" + code + "」已存在");
        }
        Supplier s = new Supplier();
        s.setCode(code.trim());
        s.setName(name.trim());
        s.setContact(contact);
        s.setPhone(phone);
        s.setPaymentTerms(paymentTerms == null ? 30 : Math.max(0, paymentTerms));
        s.setQualified(qualified == null || qualified);
        s.setStatus("INACTIVE".equals(status) ? "INACTIVE" : "ACTIVE");
        s.setRemark(remark);
        supplierRepo.save(s);
        audit.record("SUPPLIER", "SUP-" + s.getId(), operator, "供应商建档",
                String.format("{\"code\":%s,\"name\":%s,\"paymentTerms\":%d,\"qualified\":%s}",
                        jsonStr(s.getCode()), jsonStr(s.getName()), s.getPaymentTerms(), s.isQualified()));
        return s;
    }

    private Map<String, Object> supplierRow(Supplier s) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", s.getId());
        row.put("code", s.getCode());
        row.put("name", s.getName());
        row.put("contact", s.getContact());
        row.put("phone", s.getPhone());
        row.put("paymentTerms", s.getPaymentTerms());
        row.put("qualified", s.isQualified());
        row.put("status", s.getStatus());
        row.put("remark", s.getRemark());
        return row;
    }

    // ============================================================
    // 采购订单：创建/查询
    // ============================================================

    /** 创建草稿采购单（明细行 lineNo 从 1 起）；金额单位「分」。 */
    @Transactional
    public PurchaseOrder createDraft(String storeCode, Long supplierId, String expectDate,
                                     String remark, List<PurchaseOrderService.PoLineCmd> lines,
                                     String operator) {
        if (isBlank(storeCode)) throw badReq("请指定收货门店");
        if (supplierId == null) throw badReq("请选择供应商");
        Supplier supplier = supplierRepo.findById(supplierId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "供应商不存在或已停用"));
        if (!"ACTIVE".equals(supplier.getStatus())) {
            throw unprocessable("供应商「" + supplier.getName() + "」已停用，不能下单");
        }
        storeRepo.findById(storeCode.trim())
                .orElseThrow(() -> badReq("收货门店「" + storeCode + "」不存在"));
        List<PurchaseOrderItem> items = normalizeLines(lines);

        PurchaseOrder po = new PurchaseOrder();
        po.setPoNo(poNoGenerator.nextPoNo());
        po.setSupplierId(supplierId);
        po.setStoreCode(storeCode.trim());
        po.setStatus("DRAFT");
        po.setTotalFen(sumTotal(items));
        po.setSignTier(tierFor(sumTotal(items)));
        po.setExpectDate(expectDate);
        po.setRemark(remark);
        po.setCreatedBy(isBlank(operator) ? "system" : operator);
        poRepo.save(po);
        for (PurchaseOrderItem it : items) {
            it.setPoId(po.getId());
            itemRepo.save(it);
        }
        audit.record("PURCHASE", po.getPoNo(), po.getCreatedBy(), "采购单创建",
                String.format("{\"poNo\":%s,\"storeCode\":%s,\"supplierId\":%d,\"lines\":%d,\"totalFen\":%d}",
                        jsonStr(po.getPoNo()), jsonStr(po.getStoreCode()), supplierId, items.size(), po.getTotalFen()));
        return po;
    }

    /**
     * PO 列表（数据域强制收窄，B50 卡2）。
     *
     * <p>以 {@link DataScope#storeSpec} 为不可绕过的查询基座：REGION 无门店参数时自动 IN JWT
     * stores 名单（原 JPQL {@code cast(null as string) is null} 退化为全量的跨区越权由此闭合）；
     * Controller 显式门店参仅作叠加谓词，越界参数与数据域取交集自然为空。Controller 传入
     * {@code "__NONE__"}（显式越界哨兵）短路空列表。金额投影为「元」，回填供应商/门店名。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPurchaseOrders(String storeCode, String status) {
        String sc = isBlank(storeCode) ? null : ("__NONE__".equals(storeCode) ? "__NONE__" : storeCode.trim());
        String st = isBlank(status) ? null : status.trim();
        List<PurchaseOrder> pos;
        if ("__NONE__".equals(sc)) {
            pos = List.of();
        } else {
            Specification<PurchaseOrder> spec = DataScope.storeSpec("storeCode");
            if (sc != null) {
                final String fsc = sc;
                spec = spec.and((root, q, cb) -> cb.equal(root.get("storeCode"), fsc));
            }
            if (st != null) {
                final String fst = st;
                spec = spec.and((root, q, cb) -> cb.equal(root.get("status"), fst));
            }
            pos = poRepo.findAll(spec, Sort.by(Sort.Direction.DESC, "id"));
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (PurchaseOrder po : pos) {
            out.add(poRow(po, itemRepo.findByPoIdOrderByLineNoAsc(po.getId())));
        }
        return out;
    }

    /** PO 详情（含明细 + 收货批次）；越权由 Controller 先做 DataScope 断言。 */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long id) {
        PurchaseOrder po = poRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "采购单不存在"));
        Map<String, Object> row = poRow(po, itemRepo.findByPoIdOrderByLineNoAsc(id));
        List<Map<String, Object>> receipts = new ArrayList<>();
        for (GoodsReceipt r : receiptRepo.findByPoIdOrderByIdDesc(id)) {
            Map<String, Object> rr = new LinkedHashMap<>();
            rr.put("id", r.getId());
            rr.put("receiptNo", r.getReceiptNo());
            rr.put("receivedAt", r.getReceivedAt());
            rr.put("receiver", r.getReceiver());
            rr.put("qty", r.getTotalQty());
            rr.put("amountYuan", fenToYuan(r.getAmountFen()));
            rr.put("note", r.getNote());
            receipts.add(rr);
        }
        row.put("receipts", receipts);
        return row;
    }

    private Map<String, Object> poRow(PurchaseOrder po, List<PurchaseOrderItem> items) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", po.getId());
        row.put("poNo", po.getPoNo());
        row.put("supplierId", po.getSupplierId());
        Supplier sup = supplierRepo.findById(po.getSupplierId()).orElse(null);
        row.put("supplierName", sup == null ? null : sup.getName());
        row.put("supplierCode", sup == null ? null : sup.getCode());
        row.put("storeCode", po.getStoreCode());
        Store st = storeRepo.findById(po.getStoreCode()).orElse(null);
        row.put("storeName", st == null ? po.getStoreCode() : st.getStoreName());
        row.put("status", po.getStatus());
        row.put("totalYuan", fenToYuan(po.getTotalFen()));
        row.put("signTier", po.getSignTier());
        row.put("expectDate", po.getExpectDate());
        row.put("remark", po.getRemark());
        row.put("createdBy", po.getCreatedBy());
        row.put("submittedAt", po.getSubmittedAt());
        row.put("approver", po.getApprover());
        row.put("approvedAt", po.getApprovedAt());
        row.put("rejectNote", po.getRejectNote());
        row.put("cancelledAt", po.getCancelledAt());
        row.put("createdAt", po.getCreatedAt());
        List<Map<String, Object>> itemRows = new ArrayList<>();
        for (PurchaseOrderItem it : items) {
            Map<String, Object> ir = new LinkedHashMap<>();
            ir.put("lineNo", it.getLineNo());
            ir.put("skuCode", it.getSkuCode());
            ir.put("name", it.getName());
            ir.put("brand", it.getBrand());
            ir.put("unit", it.getUnit());
            ir.put("unitPriceYuan", fenToYuan(it.getUnitPriceFen()));
            ir.put("qty", it.getQty());
            ir.put("receivedQty", it.getReceivedQty());
            ir.put("amountYuan", fenToYuan((long) it.getQty() * it.getUnitPriceFen()));
            itemRows.add(ir);
        }
        row.put("items", itemRows);
        return row;
    }

    // ============================================================
    // 采购订单：状态机动作
    // ============================================================

    /** 提交审批：DRAFT → SUBMITTED，定格总额与审批层级。 */
    @Transactional
    public void submit(Long id, String operator) {
        PurchaseOrder po = mustGet(id);
        if (!"DRAFT".equals(po.getStatus())) {
            throw unprocessable("仅草稿采购单可提交，当前状态「" + po.getStatus() + "」");
        }
        List<PurchaseOrderItem> items = itemRepo.findByPoIdOrderByLineNoAsc(id);
        if (items.isEmpty()) {
            throw unprocessable("采购单无明细行，不能提交");
        }
        long total = sumTotal(items);
        po.setTotalFen(total);
        po.setSignTier(tierFor(total));
        po.setStatus("SUBMITTED");
        po.setSubmittedAt(OffsetDateTime.now());
        po.setRejectNote(null);
        poRepo.save(po);
        audit.record("PURCHASE", po.getPoNo(), operator, "采购单提交审批",
                String.format("{\"poNo\":%s,\"totalFen\":%d,\"signTier\":%s}",
                        jsonStr(po.getPoNo()), total, jsonStr(po.getSignTier())));
    }

    /** 审批通过：SUBMITTED → APPROVED。 */
    @Transactional
    public void approve(Long id, String approver, String note) {
        PurchaseOrder po = mustGet(id);
        if (!"SUBMITTED".equals(po.getStatus())) {
            throw unprocessable("仅待审批采购单可审批通过，当前状态「" + po.getStatus() + "」");
        }
        po.setStatus("APPROVED");
        po.setApprover(isBlank(approver) ? "系统" : approver);
        po.setApprovedAt(OffsetDateTime.now());
        po.setRejectNote(null);
        poRepo.save(po);
        audit.record("PURCHASE", po.getPoNo(), po.getApprover(), "采购单审批通过",
                String.format("{\"poNo\":%s,\"note\":%s}", jsonStr(po.getPoNo()), jsonStr(note)));
    }

    /** 驳回：SUBMITTED → DRAFT，记录驳回原因。 */
    @Transactional
    public void reject(Long id, String operator, String note) {
        PurchaseOrder po = mustGet(id);
        if (!"SUBMITTED".equals(po.getStatus())) {
            throw unprocessable("仅待审批采购单可驳回，当前状态「" + po.getStatus() + "」");
        }
        po.setStatus("DRAFT");
        po.setRejectNote(isBlank(note) ? "审批驳回" : note.trim());
        poRepo.save(po);
        audit.record("PURCHASE", po.getPoNo(), operator, "采购单驳回",
                String.format("{\"poNo\":%s,\"note\":%s}", jsonStr(po.getPoNo()), jsonStr(po.getRejectNote())));
    }

    /** 作废：DRAFT/SUBMITTED/APPROVED/PARTIAL → CANCELLED。 */
    @Transactional
    public void cancel(Long id, String operator, String note) {
        PurchaseOrder po = mustGet(id);
        String st = po.getStatus();
        if ("RECEIVED".equals(st) || "CANCELLED".equals(st)) {
            throw unprocessable("已入库/已作废采购单不能作废，当前状态「" + st + "」");
        }
        po.setStatus("CANCELLED");
        po.setCancelledAt(OffsetDateTime.now());
        if (!isBlank(note)) po.setRejectNote(note.trim());
        poRepo.save(po);
        audit.record("PURCHASE", po.getPoNo(), operator, "采购单作废",
                String.format("{\"poNo\":%s,\"note\":%s}", jsonStr(po.getPoNo()), jsonStr(note)));
    }

    /**
     * 收货入库：APPROVED/PARTIAL 可收。receiptNo = PO号-Rxx；
     * 逐 SKU 按 lineNo 调库存入库（确定性批次号 receiptNo-lineNo 幂等）；
     * 全部行收齐置 RECEIVED，否则 PARTIAL。
     */
    @Transactional
    public GoodsReceipt receive(Long id, List<ReceiveLineCmd> lines, String receiver, String note) {
        PurchaseOrder po = mustGet(id);
        String st = po.getStatus();
        if (!"APPROVED".equals(st) && !"PARTIAL".equals(st)) {
            throw unprocessable("仅审批通过/部分入库的采购单可收货，当前状态「" + st + "」");
        }
        if (lines == null || lines.isEmpty()) {
            throw badReq("收货明细不能为空");
        }
        List<PurchaseOrderItem> items = itemRepo.findByPoIdOrderByLineNoAsc(id);
        Map<String, PurchaseOrderItem> bySku = new LinkedHashMap<>();
        for (PurchaseOrderItem it : items) {
            bySku.put(it.getSkuCode(), it);
        }
        int totalQty = 0;
        long amountFen = 0L;
        for (ReceiveLineCmd r : lines) {
            if (r == null || isBlank(r.skuCode()) || r.qty() == null || r.qty() <= 0) {
                throw badReq("收货行存在空 SKU 或非法数量");
            }
            PurchaseOrderItem it = bySku.get(r.skuCode().trim());
            if (it == null) {
                throw badReq("SKU「" + r.skuCode() + "」不在采购单明细中");
            }
            int remain = it.getQty() - it.getReceivedQty();
            if (r.qty() > remain) {
                throw unprocessable("SKU「" + it.getName() + "」本次收货 " + r.qty()
                        + it.getUnit() + " 超过未交数量 " + remain + it.getUnit());
            }
            totalQty += r.qty();
            amountFen += (long) r.qty() * it.getUnitPriceFen();
        }

        long seq = receiptRepo.countByPoId(id) + 1;
        String receiptNo = po.getPoNo() + "-R" + String.format("%02d", seq);
        String op = isBlank(receiver) ? "系统" : receiver;
        // 先逐 SKU 入库：SKU 未建档（404）/批次重复（409）在此抛出，整批回滚，不产生半成品收货头。
        for (ReceiveLineCmd r : lines) {
            PurchaseOrderItem it = bySku.get(r.skuCode().trim());
            String batchNo = receiptNo + "-" + it.getLineNo();
            String remark = "采购入库 " + po.getPoNo() + " " + receiptNo
                    + (isBlank(note) ? "" : " " + note.trim());
            consumableService.stockIn(po.getStoreCode(), it.getSkuCode(), r.qty(),
                    it.getUnitPriceFen(), batchNo, op, remark);
            it.setReceivedQty(it.getReceivedQty() + r.qty());
            itemRepo.save(it);
        }

        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setReceiptNo(receiptNo);
        receipt.setPoId(po.getId());
        receipt.setPoNo(po.getPoNo());
        receipt.setStoreCode(po.getStoreCode());
        receipt.setTotalQty(totalQty);
        receipt.setAmountFen(amountFen);
        receipt.setReceiver(op);
        receipt.setNote(note);
        receiptRepo.save(receipt);

        boolean allReceived = items.stream().allMatch(it -> it.getReceivedQty() >= it.getQty());
        po.setStatus(allReceived ? "RECEIVED" : "PARTIAL");
        poRepo.save(po);
        audit.record("PURCHASE", receiptNo, op, "采购收货入库",
                String.format("{\"poNo\":%s,\"storeCode\":%s,\"totalQty\":%d,\"amountFen\":%d,\"allReceived\":%s}",
                        jsonStr(po.getPoNo()), jsonStr(po.getStoreCode()), totalQty, amountFen, allReceived));
        return receipt;
    }

    // ============================================================
    // 辅助
    // ============================================================

    private PurchaseOrder mustGet(Long id) {
        if (id == null) throw badReq("采购单 ID 不能为空");
        return poRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "采购单不存在"));
    }

    private List<PurchaseOrderItem> normalizeLines(List<PoLineCmd> lines) {
        if (lines == null || lines.isEmpty()) {
            throw badReq("采购明细不能为空");
        }
        List<PurchaseOrderItem> out = new ArrayList<>();
        int lineNo = 0;
        for (PoLineCmd l : lines) {
            if (l == null || isBlank(l.skuCode()) || isBlank(l.name()) || isBlank(l.unit())) {
                throw badReq("明细行的 SKU 编码、品名、单位均不能为空");
            }
            if (l.qty() == null || l.qty() <= 0) {
                throw badReq("SKU「" + l.skuCode() + "」采购数量必须大于 0");
            }
            long price = l.unitPriceFen() == null ? 0L : l.unitPriceFen();
            if (price < 0) throw badReq("SKU「" + l.skuCode() + "」采购单价不能为负");
            PurchaseOrderItem it = new PurchaseOrderItem();
            it.setLineNo(++lineNo);
            it.setSkuCode(l.skuCode().trim());
            it.setName(l.name().trim());
            it.setBrand(l.brand());
            it.setUnit(l.unit().trim());
            it.setUnitPriceFen(price);
            it.setQty(l.qty());
            it.setReceivedQty(0);
            out.add(it);
        }
        return out;
    }

    private static long sumTotal(List<PurchaseOrderItem> items) {
        return items.stream().mapToLong(it -> (long) it.getQty() * it.getUnitPriceFen()).sum();
    }

    /** 审批层级定格（分口径）：≥20,000 元 GROUP / ≥5,000 元 REGION / 余 STORE。 */
    static String tierFor(long totalFen) {
        if (totalFen >= TIER_GROUP_FEN) return "GROUP";
        if (totalFen >= TIER_REGION_FEN) return "REGION";
        return "STORE";
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static double fenToYuan(long fen) {
        return fen / 100.0;
    }

    /** 创建 PO 明细入参（金额单位「分」）。 */
    public record PoLineCmd(String skuCode, String name, String brand, String unit,
                            Long unitPriceFen, Integer qty) {}

    /** 收货行入参（数量单位 = 明细单位）。 */
    public record ReceiveLineCmd(String skuCode, Integer qty) {}
}
