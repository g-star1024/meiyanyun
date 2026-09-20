package com.meiyun.store.workorder;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stores/work-orders")
public class WorkOrderController {

    private final WorkOrderService service;

    public WorkOrderController(WorkOrderService service) {
        this.service = service;
    }

    public record CompleteCmd(String note) {}

    public record EscalateCmd(String reason) {}

    @GetMapping
    @RequirePerm("workorder:view")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode,
                                          @RequestParam(required = false) String type,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String assignee) {
        return service.list(resolveReadStoreCode(storeCode), type, status, assignee);
    }

    @GetMapping("/{id}")
    @RequirePerm("workorder:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        assertReadable(row);
        return row;
    }

    @PostMapping
    @RequirePerm("workorder:create")
    public Map<String, Object> create(@RequestBody(required = false) WorkOrderService.WoCmd cmd) {
        if (cmd == null) throw WorkOrderService.badReq("请求体不能为空");
        return service.create(resolveWriteStoreCode(cmd.storeCode()), actor(), cmd);
    }

    @PostMapping("/{id}/start")
    @RequirePerm("workorder:edit")
    public Map<String, Object> start(@PathVariable Long id) {
        assertWritable(id);
        return service.start(id, actor());
    }

    @PostMapping("/{id}/complete")
    @RequirePerm("workorder:close")
    public Map<String, Object> complete(@PathVariable Long id,
                                        @RequestBody(required = false) CompleteCmd cmd) {
        assertWritable(id);
        String note = cmd == null ? null : cmd.note();
        return service.complete(id, actor(), note);
    }

    @PostMapping("/{id}/escalate")
    @RequirePerm("workorder:edit")
    public Map<String, Object> escalate(@PathVariable Long id,
                                        @RequestBody(required = false) EscalateCmd cmd) {
        assertWritable(id);
        String reason = cmd == null ? null : cmd.reason();
        return service.escalate(id, actor(), reason);
    }

    private String actor() {
        LoginUser u = SecurityContext.get();
        return u == null ? "system" : u.staffName();
    }

    private String resolveReadStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope()))
            return requested;
        if ("REGION".equals(u.scope())) {
            if (requested != null && !requested.isBlank())
                return DataScope.canReadStore(requested.trim()) ? requested.trim() : "__NONE__";
            List<String> stores = u.stores();
            return stores != null && stores.size() == 1 ? stores.get(0) : null;
        }
        return u.storeCode() == null || u.storeCode().isBlank() ? "__NONE__" : u.storeCode();
    }

    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank())
                throw WorkOrderService.badReq("请指定工单门店");
            if (!DataScope.canReadStore(requested.trim()))
                throw WorkOrderService.badReq("无权在该门店创建工单");
            return requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank())
            throw WorkOrderService.badReq("未获取到当前门店，请稍后重试");
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode()))
            throw WorkOrderService.badReq("无权在该门店创建工单");
        return u.storeCode();
    }

    private boolean canWriteStore(String storeCode) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope()))
            return true;
        if ("REGION".equals(u.scope()))
            return DataScope.canReadStore(storeCode);
        return storeCode.equals(u.storeCode());
    }

    private void assertReadable(Map<String, Object> row) {
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !DataScope.canReadStore(s))
            throw WorkOrderService.notFound();
    }

    private void assertWritable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw WorkOrderService.badReq("无权操作该门店工单");
    }
}
