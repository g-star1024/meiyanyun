package com.meiyun.store.pricelist;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 门店价目公开端点（B14，网关 /api/stores 路由进 store-service，路径不重写）。
 *
 * <p>价目查询走 {@code pricelist:view}；建档、提交调价、停用/启用走 {@code pricelist:edit}；
 * 审批通过/驳回走 {@code brand:approve}（E1：店长只见申请调价，区域/超管才见审批）。
 * 数据域：STORE/SELF 强制本店；SUPER/GROUP/BRAND 可按 storeCode 参查/写，缺省写须显式带店。
 * 审批（approve/reject）不接 storeCode 入参，REGION 越区由 Service 经 DataScope.canReadStore 判 400。
 */
@RestController
@RequestMapping("/api/stores")
public class PricelistController {

    private final PricelistService service;

    public PricelistController(PricelistService service) {
        this.service = service;
    }

    /** 价目列表（富化 SKU 项目名/服务大类/单位/时长/风险标签；金额元；PENDING 带 pendingPrice 嵌套）。 */
    @GetMapping("/prices")
    @RequirePerm("pricelist:view")
    public List<Map<String, Object>> listPrices(@RequestParam(value = "storeCode", required = false) String storeCode,
                                                @RequestParam(value = "category", required = false) String category,
                                                @RequestParam(value = "status", required = false) String status,
                                                @RequestParam(value = "keyword", required = false) String keyword) {
        return service.listPrices(resolveStoreCode(storeCode), category, status, keyword);
    }

    /** 门店建档定价。 */
    @PostMapping("/prices")
    @RequirePerm("pricelist:edit")
    public Map<String, Object> createPrice(@RequestBody CreatePriceCmd cmd) {
        if (cmd == null) throw PricelistService.badReq("请求体不能为空");
        StorePrice p = service.createPrice(resolveWriteStoreCode(cmd.storeCode()), cmd.sku(),
                cmd.originalPriceFen(), cmd.memberPriceFen(), cmd.promoPriceFen(), operator());
        return Map.of("id", p.getId(), "storeCode", p.getStoreCode(), "sku", p.getSku());
    }

    /** 提交调价申请（店长：pricelist:edit；落 PENDING）。 */
    @PostMapping("/prices/{id}/change-request")
    @RequirePerm("pricelist:edit")
    public Map<String, Object> changeRequest(@PathVariable("id") Long id, @RequestBody ChangeRequestCmd cmd) {
        if (cmd == null) throw PricelistService.badReq("请求体不能为空");
        service.changeRequest(resolveWriteStoreCode(cmd.storeCode()), id,
                cmd.memberPriceFen(), cmd.promoPriceFen(), cmd.reason(), operator());
        return Map.of("ok", true);
    }

    /** 审批通过（区域/超管：brand:approve；pending 覆盖正式价）。 */
    @PostMapping("/prices/{id}/approve")
    @RequirePerm("brand:approve")
    public Map<String, Object> approve(@PathVariable("id") Long id) {
        service.approve(id, operator());
        return Map.of("ok", true);
    }

    /** 审批驳回（区域/超管：brand:approve；清 pending 回原价）。 */
    @PostMapping("/prices/{id}/reject")
    @RequirePerm("brand:approve")
    public Map<String, Object> reject(@PathVariable("id") Long id) {
        service.reject(id, operator());
        return Map.of("ok", true);
    }

    /** 停用/启用切换（店长：pricelist:edit；PENDING 态 409）。 */
    @PostMapping("/prices/{id}/toggle")
    @RequirePerm("pricelist:edit")
    public Map<String, Object> toggle(@PathVariable("id") Long id, @RequestBody StoreScopeCmd cmd) {
        String storeCode = cmd == null ? null : cmd.storeCode();
        service.toggle(resolveWriteStoreCode(storeCode), id, operator());
        return Map.of("ok", true);
    }

    // ---- 数据域 ----

    /** 查询：门店/自助角色强制本店；超管/集团/品牌可用 storeCode 参（须在可见名单），缺省取本店。 */
    private String resolveStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested;
        }
        if (requested != null && !requested.isBlank()) {
            if (!DataScope.canReadStore(requested.trim())) {
                return "__NONE__";
            }
            return requested.trim();
        }
        return u.storeCode();
    }

    /** 写操作：门店/自助角色只能写本店；超管/集团/品牌写参须显式传 storeCode。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank()) {
                throw PricelistService.badReq("请指定所属门店");
            }
            return requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            throw PricelistService.badReq("当前账号无所属门店，无法操作门店价目");
        }
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode())) {
            throw PricelistService.badReq("门店角色仅能操作本店价目");
        }
        return u.storeCode();
    }

    private static String operator() {
        LoginUser u = SecurityContext.get();
        return u == null ? "系统" : u.staffName();
    }

    /** 价目建档入参；金额单位「分」。 */
    public record CreatePriceCmd(String storeCode, String sku, Long originalPriceFen, Long memberPriceFen,
                                 Long promoPriceFen) {}

    /** 调价申请入参；reason 必填，memberPriceFen 必填，promoPriceFen 可空。 */
    public record ChangeRequestCmd(String storeCode, Long memberPriceFen, Long promoPriceFen, String reason) {}

    /** 仅携带门店数据域的入参（toggle）。 */
    public record StoreScopeCmd(String storeCode) {}
}
