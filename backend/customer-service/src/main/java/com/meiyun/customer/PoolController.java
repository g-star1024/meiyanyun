package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * M3-B8 公海池（DESIGN-M3 §3 L89）——公海=store_code IS NULL（ownerStaffId 辅助口径）。
 * 池列表手机号掩码（CustomerService.maskPhone 公法复用）；
 * 认领写回当前人 storeCode+staffId（DataScope 零 new LoginUser）；
 * 前置已认领校验抛 409「客户已被认领」；audit CUSTOMER POOL_CLAIM/POOL_ASSIGN。
 * 高级策略（回收/掉落/分配规则）移交后续批——DESIGN §7 L182。
 */
@RestController
@RequestMapping("/api/customer/m3/pool")
public class PoolController {

    private final CustomerRepository customerRepo;
    private final AuditRecorder audit;

    public PoolController(CustomerRepository customerRepo, AuditRecorder audit) {
        this.customerRepo = customerRepo;
        this.audit = audit;
    }

    /** 公海池列表（手机号掩码） */
    @GetMapping("")
    @RequirePerm("customer:view")
    public List<PoolView> list() {
        return customerRepo.findByStoreCodeIsNullAndMergedIntoIsNull().stream().map(PoolController::toView).toList();
    }

    /** 认领（写当前店+当前人·已认领 409） */
    @PostMapping("/{id}/claim")
    @RequirePerm("customer:edit")
    public PoolView claim(@PathVariable String id) {
        Customer c = mustGet(id);
        if (c.getStoreCode() != null && !c.getStoreCode().isBlank()) {
            throw new CustomerService.Conflict("客户已被认领");
        }
        var u = DataScope.current();
        String actor = DataScope.currentActor();
        c.setStoreCode(u == null ? null : u.storeCode());
        c.setOwnerStaffId(actor);
        Customer saved = customerRepo.save(c);
        audit.record("CUSTOMER", id, actor, "POOL_CLAIM",
                "{\"storeCode\":\"" + esc(saved.getStoreCode()) + "\"}");
        return toView(saved);
    }

    /** 分配（指定门店+归属人） */
    @PostMapping("/{id}/assign")
    @RequirePerm("customer:edit")
    public PoolView assign(@PathVariable String id, @RequestBody AssignReq req) {
        if (req == null || req.storeCode() == null || req.storeCode().isBlank()) {
            throw new CustomerService.BadReq("分配门店不能为空");
        }
        if (req.ownerStaffId() == null || req.ownerStaffId().isBlank()) {
            throw new CustomerService.BadReq("归属人不能为空");
        }
        Customer c = mustGet(id);
        String actor = DataScope.currentActor();
        c.setStoreCode(req.storeCode());
        c.setOwnerStaffId(req.ownerStaffId());
        Customer saved = customerRepo.save(c);
        audit.record("CUSTOMER", id, actor, "POOL_ASSIGN",
                "{\"storeCode\":\"" + esc(req.storeCode()) + "\",\"ownerStaffId\":\"" + esc(req.ownerStaffId()) + "\"}");
        return toView(saved);
    }

    private Customer mustGet(String id) {
        Customer c = customerRepo.findById(id)
                .orElseThrow(() -> new CustomerService.NotFound("客户不存在"));
        if (c.getMergedInto() != null) {
            throw new CustomerService.NotFound("客户不存在");
        }
        return c;
    }

    private static PoolView toView(Customer c) {
        return new PoolView(c.getCustomerId(), c.getName(),
                CustomerService.maskPhone(c.getPhone(), false), c.getLevel());
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 公海池行（手机号掩码后透出） */
    public record PoolView(String id, String name, String phone, String level) {}

    public record AssignReq(String storeCode, String ownerStaffId) {}
}
