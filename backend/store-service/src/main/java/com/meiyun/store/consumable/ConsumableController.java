package com.meiyun.store.consumable;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 耗材库存公开端点（B5，网关 /api/stores 路由进 store-service）。
 *
 * <p>台账/流水查询走 {@code inventory:consumable:view}；建档/入库走 {@code inventory:consumable:edit}。
 * 领用/报损出库不在本控制器——业务动作必须经审批中心双签，终审后由 txn 以系统身份回调
 * {@link InternalConsumableController}，杜绝前端直接扣库。
 * 数据域：STORE/SELF 强制本店；REGION/GROUP/BRAND 可按 storeCode 参查可见门店。
 */
@RestController
@RequestMapping("/api/stores/consumables")
public class ConsumableController {

    private final ConsumableService service;

    public ConsumableController(ConsumableService service) {
        this.service = service;
    }

    /** 耗材台账（库存余量/移动均价/低库存预警）；金额单位「元」。 */
    @GetMapping
    @RequirePerm("inventory:consumable:view")
    public List<Map<String, Object>> list(@RequestParam(value = "storeCode", required = false) String storeCode,
                                          @RequestParam(value = "category", required = false) String category,
                                          @RequestParam(value = "keyword", required = false) String keyword) {
        String sc = resolveStoreCode(storeCode);
        return service.listConsumables(sc, category, keyword);
    }

    /** 出入库流水（PURCHASE 入库 / USE 领用 / SCRAP 报损）；金额单位「元」。 */
    @GetMapping("/movements")
    @RequirePerm("inventory:consumable:view")
    public List<Map<String, Object>> movements(@RequestParam(value = "storeCode", required = false) String storeCode,
                                               @RequestParam(value = "types", required = false) List<String> types) {
        String sc = resolveStoreCode(storeCode);
        return service.listMovements(sc, types);
    }

    /** 耗材建档（含初始库存，可 0）；金额单位「分」由前端换算后传入。 */
    @PostMapping
    @RequirePerm("inventory:consumable:edit")
    public Map<String, Object> create(@RequestBody CreateSkuCmd cmd) {
        if (cmd == null) throw ConsumableService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        Consumable c = service.createSku(storeCode, cmd.skuCode(), cmd.name(), cmd.category(),
                cmd.spec(), cmd.unit(), cmd.costPriceFen() == null ? 0L : cmd.costPriceFen(),
                cmd.safetyStock() == null ? 0 : cmd.safetyStock(),
                cmd.initialQty() == null ? 0 : cmd.initialQty(),
                cmd.supplier(), cmd.location(), u == null ? "系统" : u.staffName());
        return Map.of("id", c.getId(), "skuCode", c.getSkuCode(), "storeCode", c.getStoreCode());
    }

    /** 入库（PURCHASE，移动平均重算）；batchNo 幂等，金额单位「分」。 */
    @PostMapping("/stock-in")
    @RequirePerm("inventory:consumable:edit")
    public Map<String, Object> stockIn(@RequestBody StockInCmd cmd) {
        if (cmd == null) throw ConsumableService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        service.stockIn(storeCode, cmd.skuCode(),
                cmd.qty() == null ? 0 : cmd.qty(),
                cmd.unitCostFen() == null ? 0L : cmd.unitCostFen(),
                cmd.batchNo(), u == null ? "系统" : u.staffName(), cmd.remark());
        return Map.of("ok", true);
    }

    // ---- 数据域 ----

    /** 查询：门店/自助角色强制本店；区域/集团可用 storeCode 参（须在可见名单），缺省取本店。 */
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

    /** 写操作：门店/自助角色只能写本店；区域/集团写参须显式传 storeCode 且在可见名单。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank()) {
                throw ConsumableService.badReq("请指定所属门店");
            }
            return requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank()) {
            throw ConsumableService.badReq("当前账号无所属门店，无法操作库存");
        }
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode())) {
            throw ConsumableService.badReq("门店角色仅能操作本店库存");
        }
        return u.storeCode();
    }

    /** 建档入参；金额 costPriceFen 单位「分」 */
    public record CreateSkuCmd(String storeCode, String skuCode, String name, String category,
                               String spec, String unit, Long costPriceFen, Integer safetyStock,
                               Integer initialQty, String supplier, String location) {}

    /** 入库入参；金额 unitCostFen 单位「分」，batchNo 为幂等键 */
    public record StockInCmd(String storeCode, String skuCode, Integer qty, Long unitCostFen,
                             String batchNo, String remark) {}
}
