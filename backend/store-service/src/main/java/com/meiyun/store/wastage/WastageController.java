package com.meiyun.store.wastage;

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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/stores/wastages")
public class WastageController {

    private final WastageService service;

    public WastageController(WastageService service) {
        this.service = service;
    }

    public record CreateWsCmd(String storeCode, String itemName, String spec, Integer qty,
                              String unit, Long amountFen, String reason, String reporter,
                              String location, String description, OffsetDateTime occurredAt) {}

    public record RejectCmd(String reason) {}

    public record NoteCmd(String note) {}

    @GetMapping
    @RequirePerm("wastage:view")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String reason,
                                          @RequestParam(required = false) String reporter) {
        String sc = resolveReadStoreCode(storeCode);
        return service.list(sc, status, reason, reporter);
    }

    @GetMapping("/{id}")
    @RequirePerm("wastage:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        assertReadable(row);
        return row;
    }

    @PostMapping
    @RequirePerm("wastage:create")
    public Map<String, Object> create(@RequestBody(required = false) CreateWsCmd cmd) {
        if (cmd == null) throw WastageService.badReq("请求体不能为空");
        String sc = resolveWriteStoreCode(cmd.storeCode());
        String reporter = (cmd.reporter() == null || cmd.reporter().isBlank())
                ? actor() : cmd.reporter();
        return service.create(sc, cmd.itemName(), cmd.spec(), cmd.qty(), cmd.unit(),
                cmd.amountFen(), cmd.reason(), reporter, cmd.location(), cmd.description(),
                cmd.occurredAt(), actor());
    }

    @PostMapping("/{id}/submit")
    @RequirePerm("wastage:edit")
    public Map<String, Object> submit(@PathVariable Long id) {
        assertWritable(id);
        return service.submit(id, actor());
    }

    @PostMapping("/{id}/approve")
    @RequirePerm("wastage:sign")
    public Map<String, Object> approve(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, String> body) {
        assertWritable(id);
        String note = body == null ? null : body.get("note");
        return service.approve(id, actor(), note);
    }

    @PostMapping("/{id}/reject")
    @RequirePerm("wastage:sign")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestBody(required = false) RejectCmd cmd) {
        assertWritable(id);
        String reason = cmd == null ? null : cmd.reason();
        return service.reject(id, actor(), reason);
    }

    @PostMapping("/{id}/notes")
    @RequirePerm("wastage:edit")
    public Map<String, Object> addNote(@PathVariable Long id,
                                       @RequestBody(required = false) NoteCmd cmd) {
        assertWritable(id);
        String note = cmd == null ? null : cmd.note();
        service.addNote(id, actor(), note);
        return service.detail(id);
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
                throw WastageService.badReq("请指定报损门店");
            return requested.trim();
        }
        if (requested == null || requested.isBlank())
            throw WastageService.badReq("请指定报损门店");
        if (!canWriteStore(requested.trim()))
            throw WastageService.badReq("无权在该门店操作报损单");
        return requested.trim();
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
            throw WastageService.notFound();
    }

    private void assertWritable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw WastageService.badReq("无权操作该门店报损单");
    }
}
