package com.meiyun.store.handover;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.DeleteMapping;
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
@RequestMapping("/api/stores/handovers")
public class HandoverController {

    private final HandoverService service;

    public HandoverController(HandoverService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("handover:view")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode,
                                          @RequestParam(required = false) String status) {
        return service.list(resolveReadStoreCode(storeCode), status);
    }

    @GetMapping("/{id}")
    @RequirePerm("handover:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        assertReadable(row);
        return row;
    }

    @PostMapping
    @RequirePerm("handover:create")
    public Map<String, Object> create(@RequestParam(required = false) String storeCode,
                                      @RequestBody(required = false) HandoverService.CreateCmd cmd) {
        String sc = resolveWriteStoreCode(storeCode);
        return service.create(sc, cmd, actor());
    }

    @PostMapping("/{id}/draft")
    @RequirePerm("handover:create")
    public Map<String, Object> updateDraft(@PathVariable Long id,
                                           @RequestBody(required = false) HandoverService.DraftCmd cmd) {
        assertWritable(id);
        return service.updateDraft(id, cmd, actor());
    }

    @PostMapping("/{id}/todos")
    @RequirePerm("handover:create")
    public Map<String, Object> addTodo(@PathVariable Long id,
                                       @RequestBody(required = false) HandoverService.TodoCmd cmd) {
        assertWritable(id);
        return service.addTodo(id, cmd, actor());
    }

    @DeleteMapping("/{id}/todos/{todoId}")
    @RequirePerm("handover:create")
    public Map<String, Object> removeTodo(@PathVariable Long id,
                                          @PathVariable Long todoId) {
        assertWritable(id);
        return service.removeTodo(id, todoId, actor());
    }

    @PostMapping("/{id}/submit")
    @RequirePerm("handover:create")
    public Map<String, Object> submit(@PathVariable Long id) {
        assertWritable(id);
        return service.submit(id, actor());
    }

    @PostMapping("/{id}/confirm")
    @RequirePerm("handover:edit")
    public Map<String, Object> confirm(@PathVariable Long id,
                                       @RequestBody(required = false) HandoverService.ConfirmCmd cmd) {
        assertWritable(id);
        return service.confirm(id, cmd, actor());
    }

    @PostMapping("/{id}/send-back")
    @RequirePerm("handover:edit")
    public Map<String, Object> sendBack(@PathVariable Long id,
                                        @RequestBody(required = false) HandoverService.ConfirmCmd cmd) {
        assertWritable(id);
        return service.sendBack(id, cmd, actor());
    }

    @PostMapping("/{id}/todos/{todoId}/toggle")
    @RequirePerm("handover:edit")
    public Map<String, Object> toggleTodo(@PathVariable Long id,
                                          @PathVariable Long todoId) {
        assertWritable(id);
        return service.toggleTodo(id, todoId, actor());
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
                throw HandoverService.badReq("请指定门店");
            if (!DataScope.canReadStore(requested.trim()))
                throw HandoverService.badReq("无权操作该门店交接班");
            return requested.trim();
        }
        if ("REGION".equals(u.scope())) {
            if (requested != null && !requested.isBlank() && DataScope.canReadStore(requested.trim()))
                return requested.trim();
            List<String> stores = u.stores();
            if (stores != null && stores.size() == 1) return stores.get(0);
            throw HandoverService.badReq("请指定门店");
        }
        if (u.storeCode() == null || u.storeCode().isBlank())
            throw HandoverService.badReq("未获取到当前门店，请稍后重试");
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode()))
            throw HandoverService.badReq("无权操作该门店交接班");
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
            throw HandoverService.notFound();
    }

    private void assertWritable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw HandoverService.badReq("无权操作该门店交接班");
    }
}
