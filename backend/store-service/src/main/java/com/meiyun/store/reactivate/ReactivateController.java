package com.meiyun.store.reactivate;

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
@RequestMapping("/api/stores/reactivates")
public class ReactivateController {

    private final ReactivateService service;

    public ReactivateController(ReactivateService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("reactivate:edit")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode) {
        return service.list(resolveReadStoreCode(storeCode));
    }

    @GetMapping("/{id}")
    @RequirePerm("reactivate:edit")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        checkReadable(row);
        return row;
    }

    @PostMapping("/{id}/assign")
    @RequirePerm("reactivate:edit")
    public Map<String, Object> assign(@PathVariable Long id,
                                      @RequestBody(required = false) ReactivateService.AssignCmd cmd) {
        Map<String, Object> row = service.detail(id);
        checkWritable(row);
        return service.assign(id, cmd, actor());
    }

    @PostMapping("/{id}/visit")
    @RequirePerm("reactivate:edit")
    public Map<String, Object> visit(@PathVariable Long id,
                                     @RequestBody(required = false) ReactivateService.VisitCmd cmd) {
        Map<String, Object> row = service.detail(id);
        checkWritable(row);
        return service.logVisit(id, cmd, actor());
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
            throw ReactivateService.notFound();
    }

    private void checkWritable(Map<String, Object> row) {
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw ReactivateService.badReq("无权操作该门店沉睡客户");
    }
}
