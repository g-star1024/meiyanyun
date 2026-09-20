package com.meiyun.store.inspection;

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
@RequestMapping("/api/stores/inspections")
public class InspectionController {

    private final InspectionService service;

    public InspectionController(InspectionService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("inspection:view")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode) {
        return service.list(resolveReadStoreCode(storeCode));
    }

    @GetMapping("/{id}")
    @RequirePerm("inspection:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        checkReadable(row);
        return row;
    }

    @PostMapping
    @RequirePerm("inspection:create")
    public Map<String, Object> create(@RequestParam(required = false) String storeCode,
                                      @RequestBody(required = false) InspectionService.CreateCmd cmd) {
        return service.create(resolveWritableStoreCode(storeCode), cmd, actor());
    }

    @PostMapping("/issues/{issueId}/assign")
    @RequirePerm("inspection:edit")
    public Map<String, Object> assign(@PathVariable Long issueId,
                                      @RequestBody(required = false) InspectionService.AssignCmd cmd) {
        Map<String, Object> row = service.assignIssue(issueId, cmd, actor());
        checkWritable(row);
        return row;
    }

    @PostMapping("/issues/{issueId}/complete")
    @RequirePerm("inspection:edit")
    public Map<String, Object> complete(@PathVariable Long issueId) {
        Map<String, Object> row = service.completeIssue(issueId, actor());
        checkWritable(row);
        return row;
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

    private String resolveWritableStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            String sc = requested == null ? null : requested.trim();
            if (sc == null || sc.isBlank()) throw InspectionService.badReq("请指定门店");
            return sc;
        }
        if ("REGION".equals(u.scope())) {
            String sc = requested == null ? null : requested.trim();
            if (sc == null || sc.isBlank()) throw InspectionService.badReq("请指定门店");
            if (!DataScope.canReadStore(sc)) throw InspectionService.badReq("无权操作该门店巡检");
            return sc;
        }
        if (u.storeCode() == null || u.storeCode().isBlank())
            throw InspectionService.badReq("无权操作该门店巡检");
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

    private void checkReadable(Map<String, Object> row) {
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !DataScope.canReadStore(s))
            throw InspectionService.notFound();
    }

    private void checkWritable(Map<String, Object> row) {
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw InspectionService.badReq("无权操作该门店巡检");
    }
}
