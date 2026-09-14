package com.meiyun.store.procurement;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 采购订单公开端点（B49 卡5，网关 /api/stores → store-service）。
 *
 * <p>读（列表/详情）走 {@code inventory:view}；建单/提交/作废/收货走 {@code inventory:edit}；
 * 审批通过/驳回走 {@code inventory:approve}（与 M1 采购屏按钮 v-perm 键一致，不新增权限码）。
 * 数据域：GROUP/BRAND/超管全量；REGION 限 JWT stores 可见名单；STORE/SELF 强制本店。
 */
@RestController
@RequestMapping("/api/stores/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("inventory:view")
    public List<Map<String, Object>> list(@RequestParam(value = "storeCode", required = false) String storeCode,
                                          @RequestParam(value = "status", required = false) String status) {
        return service.listPurchaseOrders(resolveReadStoreCode(storeCode), status);
    }

    @GetMapping("/{id}")
    @RequirePerm("inventory:view")
    public Map<String, Object> detail(@PathVariable("id") Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (sc instanceof String s && !DataScope.canReadStore(s)) {
            throw PurchaseOrderService.notFound();
        }
        return row;
    }

    /** 创建草稿采购单（明细金额单位「分」）。 */
    @PostMapping
    @RequirePerm("inventory:edit")
    public Map<String, Object> create(@RequestBody CreatePoCmd cmd) {
        if (cmd == null) throw PurchaseOrderService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        PurchaseOrder po = service.createDraft(storeCode, cmd.supplierId(), cmd.expectDate(), cmd.remark(),
                cmd.lines(), u == null ? "system" : u.staffName());
        return Map.of("id", po.getId(), "poNo", po.getPoNo(), "status", po.getStatus());
    }

    @PostMapping("/{id}/submit")
    @RequirePerm("inventory:edit")
    public Map<String, Object> submit(@PathVariable("id") Long id) {
        assertWritable(id);
        service.submit(id, actor());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/approve")
    @RequirePerm("inventory:approve")
    public Map<String, Object> approve(@PathVariable("id") Long id, @RequestBody(required = false) NoteCmd cmd) {
        assertReadable(id);
        service.approve(id, actor(), cmd == null ? null : cmd.note());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/reject")
    @RequirePerm("inventory:approve")
    public Map<String, Object> reject(@PathVariable("id") Long id, @RequestBody(required = false) NoteCmd cmd) {
        assertReadable(id);
        service.reject(id, actor(), cmd == null ? null : cmd.note());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/cancel")
    @RequirePerm("inventory:edit")
    public Map<String, Object> cancel(@PathVariable("id") Long id, @RequestBody(required = false) NoteCmd cmd) {
        assertWritable(id);
        service.cancel(id, actor(), cmd == null ? null : cmd.note());
        return Map.of("ok", true);
    }

    @PostMapping("/{id}/receive")
    @RequirePerm("inventory:edit")
    public Map<String, Object> receive(@PathVariable("id") Long id, @RequestBody ReceiveCmd cmd) {
        if (cmd == null) throw PurchaseOrderService.badReq("请求体不能为空");
        assertWritable(id);
        GoodsReceipt r = service.receive(id, cmd.lines(), actor(), cmd.note());
        return Map.of("ok", true, "receiptNo", r.getReceiptNo());
    }

    // ---- 数据域 ----

    private void assertReadable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (sc instanceof String s && !DataScope.canReadStore(s)) {
            throw PurchaseOrderService.notFound();
        }
    }

    private void assertWritable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s)) {
            throw PurchaseOrderService.badReq("无权操作该门店采购单");
        }
    }

    /** 查询门店收敛：超管/集团/品牌按传参（空=全量）；区域按可见名单；门店/自助强制本店。 */
    private String resolveReadStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested;
        }
        if ("REGION".equals(u.scope())) {
            if (requested != null && !requested.isBlank()) {
                return DataScope.canReadStore(requested.trim()) ? requested.trim() : "__NONE__";
            }
            List<String> stores = u.stores();
            return stores != null && stores.size() == 1 ? stores.get(0) : null;
        }
        return u.storeCode() == null || u.storeCode().isBlank() ? "__NONE__" : u.storeCode();
    }

    /** 写门店收敛：集团/品牌须显式传门店；区域须显式且在可见名单；门店/自助只能写本店。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank()) {
                throw PurchaseOrderService.badReq("请指定收货门店");
            }
            return requested.trim();
        }
        if (requested == null || requested.isBlank()) {
            throw PurchaseOrderService.badReq("请指定收货门店");
        }
        if (!canWriteStore(requested.trim())) {
            throw PurchaseOrderService.badReq("无权在该门店操作采购单");
        }
        return requested.trim();
    }

    private boolean canWriteStore(String storeCode) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return true;
        }
        if ("REGION".equals(u.scope())) {
            return DataScope.canReadStore(storeCode);
        }
        return storeCode.equals(u.storeCode());
    }

    private static String actor() {
        LoginUser u = SecurityContext.get();
        return u == null ? "system" : u.staffName();
    }

    /** 建单入参；金额 unitPriceFen 单位「分」。 */
    public record CreatePoCmd(String storeCode, Long supplierId, String expectDate, String remark,
                              List<PurchaseOrderService.PoLineCmd> lines) {}

    public record NoteCmd(String note) {}

    public record ReceiveCmd(List<PurchaseOrderService.ReceiveLineCmd> lines, String note) {}
}
