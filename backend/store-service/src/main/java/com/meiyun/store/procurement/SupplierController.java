package com.meiyun.store.procurement;

import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 供应商公开端点（B49 卡5，网关 /api/stores → store-service）。
 * 供应商为集团级全局档案：读走 {@code inventory:view}，建档走 {@code inventory:edit}。
 */
@RestController
@RequestMapping("/api/stores/suppliers")
public class SupplierController {

    private final PurchaseOrderService service;

    public SupplierController(PurchaseOrderService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("inventory:view")
    public List<Map<String, Object>> list(@RequestParam(value = "keyword", required = false) String keyword) {
        return service.listSuppliers(keyword);
    }

    @PostMapping
    @RequirePerm("inventory:edit")
    public Map<String, Object> create(@RequestBody CreateSupplierCmd cmd) {
        if (cmd == null) throw PurchaseOrderService.badReq("请求体不能为空");
        LoginUser u = SecurityContext.get();
        Supplier s = service.createSupplier(cmd.code(), cmd.name(), cmd.contact(), cmd.phone(),
                cmd.paymentTerms(), cmd.qualified(), cmd.status(), cmd.remark(),
                u == null ? "system" : u.staffName());
        return Map.of("id", s.getId(), "code", s.getCode());
    }

    /** 供应商建档入参；qualified/status 缺省按有效/合作中处理。 */
    public record CreateSupplierCmd(String code, String name, String contact, String phone,
                                    Integer paymentTerms, Boolean qualified, String status,
                                    String remark) {}
}
