package com.meiyun.store.acquisition;

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
@RequestMapping("/api/stores/acquisitions")
public class AcquisitionController {

    private final AcquisitionService service;

    public AcquisitionController(AcquisitionService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("acquisition:edit")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode) {
        return service.list(resolveReadStoreCode(storeCode));
    }

    @GetMapping("/{id}")
    @RequirePerm("acquisition:edit")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        checkReadable(row);
        return row;
    }

    @PostMapping
    @RequirePerm("acquisition:edit")
    public Map<String, Object> create(@RequestParam(required = false) String storeCode,
                                      @RequestBody(required = false) AcquisitionService.CreateCmd cmd) {
        return service.create(resolveWritableStoreCode(storeCode), cmd, actor());
    }

    @PostMapping("/{id}/launch")
    @RequirePerm("acquisition:edit")
    public Map<String, Object> launch(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        checkWritable(row);
        return service.launch(id, actor());
    }

    @PostMapping("/{id}/end")
    @RequirePerm("acquisition:edit")
    public Map<String, Object> end(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        checkWritable(row);
        return service.end(id, actor());
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
            if (sc == null || sc.isBlank()) throw AcquisitionService.badReq("请指定门店");
            return sc;
        }
        if ("REGION".equals(u.scope())) {
            String sc = requested == null ? null : requested.trim();
            if (sc == null || sc.isBlank()) throw AcquisitionService.badReq("请指定门店");
            if (!DataScope.canReadStore(sc)) throw AcquisitionService.badReq("无权操作该门店拓客活动");
            return sc;
        }
        if (u.storeCode() == null || u.storeCode().isBlank()) throw AcquisitionService.badReq("无权操作该门店拓客活动");
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode()))
            throw AcquisitionService.badReq("无权操作该门店拓客活动");
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
            throw AcquisitionService.notFound();
    }

    private void checkWritable(Map<String, Object> row) {
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw AcquisitionService.badReq("无权操作该门店拓客活动");
    }
}
