package com.meiyun.store.pricelist;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import com.meiyun.store.consumable.ConsumableAuditRecorder;
import com.meiyun.store.project.ProductSku;
import com.meiyun.store.project.ProductSkuRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 门店价目域服务（B14）：门店对集团 SKU 定价（原价/会员价/活动价）与调价三态状态机。
 *
 * <p>状态机（DESIGN-P5 §3.4）：ACTIVE ─change-request(pricelist:edit)→ PENDING
 * ─approve(brand:approve)→ ACTIVE（pending 覆盖正式价）；PENDING ─reject(brand:approve)→ ACTIVE（清 pending 原价保留）；
 * toggle(pricelist:edit) 在 ACTIVE↔DISABLED 间切换；PENDING 态不可再提交/不可切换/不可重复审批（409 幂等不审计）。
 * 列表富化 join product_sku 带出项目名/服务大类/单位/时长/风险标签；金额 Long 分落库、出参元。
 * 审批人数据域：REGION scope 越区审批 400（DataScope.canReadStore），SUPER/GROUP 放行。
 */
@Service
public class PricelistService {

    private final StorePriceRepository priceRepo;
    private final ProductSkuRepository skuRepo;
    private final ConsumableAuditRecorder audit;

    public PricelistService(StorePriceRepository priceRepo,
                            ProductSkuRepository skuRepo,
                            ConsumableAuditRecorder audit) {
        this.priceRepo = priceRepo;
        this.skuRepo = skuRepo;
        this.audit = audit;
    }

    /** 400 参数错误（中文） */
    static ResponseStatusException badReq(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    /**
     * 价目列表：富化 SKU 字段（name/code=sku/category=serviceCategory/unit/durationMin/riskTags）；
     * category/status/keyword 在富化后内存过滤（价目为门店级小表）。storeCode 空（高权限跨店）查全量。
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> listPrices(String storeCode, String category, String status, String keyword) {
        String sc = isBlank(storeCode) ? null : storeCode.trim();
        String cat = isBlank(category) || "ALL".equals(category) ? null : category.trim();
        String st = isBlank(status) || "ALL".equals(status) ? null : status.trim();
        String kw = isBlank(keyword) ? null : keyword.trim().toLowerCase();

        List<StorePrice> prices = sc == null
                ? priceRepo.findAllByOrderByStoreCodeAscSkuAsc()
                : priceRepo.findByStoreCodeOrderBySku(sc);
        List<Map<String, Object>> out = new ArrayList<>();
        for (StorePrice p : prices) {
            ProductSku sku = skuRepo.findBySku(p.getSku()).orElse(null);
            String skuName = sku == null ? p.getSku() : sku.getName();
            String serviceCat = sku == null ? null : sku.getServiceCategory();
            if (cat != null && !cat.equals(serviceCat)) continue;
            if (st != null && !st.equals(p.getStatus())) continue;
            if (kw != null
                    && !p.getSku().toLowerCase().contains(kw)
                    && !skuName.toLowerCase().contains(kw)) {
                continue;
            }
            out.add(priceRow(p, sku));
        }
        return out;
    }

    /** 门店建档定价：同店同 SKU 409；SKU 不存在 400。 */
    @Transactional
    public StorePrice createPrice(String storeCode, String sku, Long originalPriceFen, Long memberPriceFen,
                                  Long promoPriceFen, String operator) {
        if (isBlank(storeCode) || isBlank(sku)) {
            throw badReq("所属门店、项目编码均不能为空");
        }
        ProductSku s = skuRepo.findBySku(sku.trim())
                .orElseThrow(() -> badReq("项目编码不存在：" + sku));
        if (priceRepo.findByStoreCodeAndSku(storeCode, s.getSku()).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "本店已存在项目「" + s.getSku() + "」的价目，请勿重复建档");
        }
        StorePrice p = new StorePrice();
        p.setStoreCode(storeCode);
        p.setSku(s.getSku());
        p.setOriginalPriceFen(nonNegativeFen(originalPriceFen));
        p.setMemberPriceFen(nonNegativeFen(memberPriceFen));
        p.setPromoPriceFen(promoPriceFen == null ? null : nonNegativeFen(promoPriceFen));
        p.setStatus("ACTIVE");
        p.setCreatedBy(operator);
        p.setUpdatedBy(operator);
        priceRepo.save(p);
        audit.record("PRICE", "PRICE-" + p.getId(), operator, "门店价目建档",
                String.format("{\"storeCode\":%s,\"sku\":%s,\"originalPriceFen\":%d,\"memberPriceFen\":%d}",
                        jsonStr(storeCode), jsonStr(s.getSku()), p.getOriginalPriceFen(), p.getMemberPriceFen()));
        return p;
    }

    /** 提交调价申请：reason 必填（空 400）；仅 ACTIVE 可提交，否则 409；落 PENDING + pending_*。 */
    @Transactional
    public void changeRequest(String storeCode, Long id, Long memberPriceFen, Long promoPriceFen,
                              String reason, String operator) {
        StorePrice p = mustFindInStore(storeCode, id);
        if (isBlank(reason)) throw badReq("请填写调价原因");
        if (memberPriceFen == null) throw badReq("请填写调价后的会员价");
        if (!"ACTIVE".equals(p.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该价目当前不可提交调价申请");
        }
        p.setPendingMemberPriceFen(nonNegativeFen(memberPriceFen));
        p.setPendingPromoPriceFen(promoPriceFen == null ? null : nonNegativeFen(promoPriceFen));
        p.setPendingReason(reason.trim());
        p.setRequestedBy(operator);
        p.setRequestedAt(OffsetDateTime.now());
        p.setStatus("PENDING");
        p.setUpdatedBy(operator);
        p.setUpdatedAt(OffsetDateTime.now());
        priceRepo.save(p);
        audit.record("PRICE", "PRICE-" + p.getId(), operator, "提交调价申请",
                String.format("{\"storeCode\":%s,\"sku\":%s,\"pendingMemberPriceFen\":%d,\"reason\":%s}",
                        jsonStr(p.getStoreCode()), jsonStr(p.getSku()),
                        p.getPendingMemberPriceFen(), jsonStr(reason.trim())));
    }

    /** 审批通过：brand:approve（Controller 注解）；REGION 越区 400；非 PENDING 409 幂等不审计；pending 覆盖正式价。 */
    @Transactional
    public void approve(Long id, String operator) {
        StorePrice p = priceRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "价目不存在：" + id));
        assertApproverInScope(p);
        if (!"PENDING".equals(p.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该价目不在待审批状态，无需审批");
        }
        Long newMember = p.getPendingMemberPriceFen();
        Long newPromo = p.getPendingPromoPriceFen();
        p.setMemberPriceFen(newMember == null ? p.getMemberPriceFen() : newMember);
        p.setPromoPriceFen(newPromo);
        p.setStatus("ACTIVE");
        p.setPendingMemberPriceFen(null);
        p.setPendingPromoPriceFen(null);
        p.setPendingReason(null);
        p.setRequestedBy(null);
        p.setRequestedAt(null);
        p.setUpdatedBy(operator);
        p.setUpdatedAt(OffsetDateTime.now());
        priceRepo.save(p);
        audit.record("PRICE", "PRICE-" + p.getId(), operator, "审批通过调价",
                String.format("{\"storeCode\":%s,\"sku\":%s,\"memberPriceFen\":%d,\"promoPriceFen\":%s}",
                        jsonStr(p.getStoreCode()), jsonStr(p.getSku()), p.getMemberPriceFen(),
                        p.getPromoPriceFen() == null ? "null" : p.getPromoPriceFen().toString()));
    }

    /** 审批驳回：非 PENDING 409；清 pending、回 ACTIVE，原价保留。 */
    @Transactional
    public void reject(Long id, String operator) {
        StorePrice p = priceRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "价目不存在：" + id));
        assertApproverInScope(p);
        if (!"PENDING".equals(p.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该价目不在待审批状态，无需驳回");
        }
        String reason = p.getPendingReason();
        p.setStatus("ACTIVE");
        p.setPendingMemberPriceFen(null);
        p.setPendingPromoPriceFen(null);
        p.setPendingReason(null);
        p.setRequestedBy(null);
        p.setRequestedAt(null);
        p.setUpdatedBy(operator);
        p.setUpdatedAt(OffsetDateTime.now());
        priceRepo.save(p);
        audit.record("PRICE", "PRICE-" + p.getId(), operator, "驳回调价申请",
                String.format("{\"storeCode\":%s,\"sku\":%s,\"reason\":%s}",
                        jsonStr(p.getStoreCode()), jsonStr(p.getSku()), jsonStr(reason)));
    }

    /** 停用/启用切换：PENDING 态 409；ACTIVE↔DISABLED。 */
    @Transactional
    public void toggle(String storeCode, Long id, String operator) {
        StorePrice p = mustFindInStore(storeCode, id);
        if ("PENDING".equals(p.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "调价审批中，不可停用/启用");
        }
        boolean enable = "DISABLED".equals(p.getStatus());
        p.setStatus(enable ? "ACTIVE" : "DISABLED");
        p.setUpdatedBy(operator);
        p.setUpdatedAt(OffsetDateTime.now());
        priceRepo.save(p);
        audit.record("PRICE", "PRICE-" + p.getId(), operator, enable ? "启用价目" : "停用价目",
                String.format("{\"storeCode\":%s,\"sku\":%s,\"status\":%s}",
                        jsonStr(p.getStoreCode()), jsonStr(p.getSku()), jsonStr(p.getStatus())));
    }

    // ---- 种子（供 DataInitializer 复用，不写审计） ----

    /** 某门店价目条数（播种幂等门控用） */
    @Transactional(readOnly = true)
    public long countByStore(String storeCode) {
        return priceRepo.countByStoreCode(storeCode);
    }

    @Transactional
    public StorePrice seedPrice(String storeCode, String sku, long originalFen, long memberFen,
                                Long promoFen, String status, Long pendingMemberFen, Long pendingPromoFen,
                                String pendingReason, String requestedBy, String operator) {
        StorePrice p = new StorePrice();
        p.setStoreCode(storeCode);
        p.setSku(sku);
        p.setOriginalPriceFen(originalFen);
        p.setMemberPriceFen(memberFen);
        p.setPromoPriceFen(promoFen);
        p.setStatus(status == null ? "ACTIVE" : status);
        p.setPendingMemberPriceFen(pendingMemberFen);
        p.setPendingPromoPriceFen(pendingPromoFen);
        p.setPendingReason(pendingReason);
        p.setRequestedBy(requestedBy);
        p.setRequestedAt(pendingMemberFen == null ? null : OffsetDateTime.now());
        p.setCreatedBy(operator);
        p.setUpdatedBy(operator);
        return priceRepo.save(p);
    }

    // ---- 出参装配 ----

    private Map<String, Object> priceRow(StorePrice p, ProductSku sku) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", p.getId());
        row.put("storeCode", p.getStoreCode());
        row.put("sku", p.getSku());
        row.put("code", p.getSku());
        row.put("name", sku == null ? p.getSku() : sku.getName());
        row.put("category", sku == null ? null : sku.getServiceCategory());
        row.put("unit", sku == null ? null : sku.getUnit());
        row.put("durationMin", sku == null ? 0 : sku.getDurationMin());
        row.put("riskTags", sku == null ? List.of() : splitCsv(sku.getRiskTags()));
        row.put("originalPriceYuan", fenToYuan(p.getOriginalPriceFen()));
        row.put("memberPriceYuan", fenToYuan(p.getMemberPriceFen()));
        row.put("promoPriceYuan", p.getPromoPriceFen() == null ? null : fenToYuan(p.getPromoPriceFen()));
        row.put("status", p.getStatus());
        row.put("updatedBy", p.getUpdatedBy());
        row.put("updatedAt", p.getUpdatedAt());
        if ("PENDING".equals(p.getStatus()) && p.getPendingMemberPriceFen() != null) {
            Map<String, Object> pending = new LinkedHashMap<>();
            pending.put("memberPriceYuan", fenToYuan(p.getPendingMemberPriceFen()));
            pending.put("promoPriceYuan",
                    p.getPendingPromoPriceFen() == null ? null : fenToYuan(p.getPendingPromoPriceFen()));
            pending.put("reason", p.getPendingReason());
            pending.put("requestedAt", p.getRequestedAt());
            pending.put("requestedBy", p.getRequestedBy());
            row.put("pendingPrice", pending);
        }
        return row;
    }

    // ---- 内部辅助 ----

    private StorePrice mustFindInStore(String storeCode, Long id) {
        StorePrice p = priceRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "价目不存在：" + id));
        if (storeCode != null && !storeCode.isBlank() && !p.getStoreCode().equals(storeCode.trim())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "本店不存在该价目");
        }
        return p;
    }

    /** 审批人数据域：SUPER/GROUP 放行；REGION 及其他角色须 DataScope.canReadStore(price.storeCode)。 */
    private void assertApproverInScope(StorePrice p) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope())) {
            return;
        }
        if (!DataScope.canReadStore(p.getStoreCode())) {
            throw badReq("无权审批其他区域的调价申请");
        }
    }

    private static long nonNegativeFen(Long fen) {
        return fen == null ? 0L : Math.max(0L, fen);
    }

    private static double fenToYuan(Long fen) {
        return fen == null ? 0.0 : Math.round(fen) / 100.0;
    }

    private static List<String> splitCsv(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /** JSON 字符串转义（审计 payload 手工拼 JSON，与 RoomService.jsonStr 同口径） */
    private static String jsonStr(String s) {
        return s == null ? "null" : "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
