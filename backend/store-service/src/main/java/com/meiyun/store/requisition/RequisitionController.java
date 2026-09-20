package com.meiyun.store.requisition;

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
@RequestMapping("/api/stores/requisitions")
public class RequisitionController {

    private final RequisitionService service;

    public RequisitionController(RequisitionService service) {
        this.service = service;
    }

    public record CreateRqCmd(String storeCode, String applicant, String purpose, String remark,
                              List<RequisitionService.RqLineCmd> items) {}

    public record RejectCmd(String reason) {}

    @GetMapping
    @RequirePerm("requisition:view")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode,
                                          @RequestParam(required = false) String status,
                                          @RequestParam(required = false) String applicant) {
        String sc = resolveReadStoreCode(storeCode);
        return service.list(sc, status, applicant);
    }

    @GetMapping("/{id}")
    @RequirePerm("requisition:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        assertReadable(row);
        return row;
    }

    @PostMapping
    @RequirePerm("requisition:create")
    public Map<String, Object> create(@RequestBody(required = false) CreateRqCmd cmd) {
        if (cmd == null) throw RequisitionService.badReq("请求体不能为空");
        String sc = resolveWriteStoreCode(cmd.storeCode());
        String applicant = (cmd.applicant() == null || cmd.applicant().isBlank())
                ? actor() : cmd.applicant();
        return service.create(sc, applicant, cmd.purpose(), cmd.remark(), actor(), cmd.items());
    }

    @PostMapping("/{id}/submit")
    @RequirePerm("requisition:edit")
    public Map<String, Object> submit(@PathVariable Long id) {
        assertWritable(id);
        return service.submit(id, actor());
    }

    @PostMapping("/{id}/approve")
    @RequirePerm("requisition:sign")
    public Map<String, Object> approve(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, String> body) {
        assertWritable(id);
        String note = body == null ? null : body.get("note");
        return service.approve(id, actor(), note);
    }

    @PostMapping("/{id}/reject")
    @RequirePerm("requisition:sign")
    public Map<String, Object> reject(@PathVariable Long id,
                                      @RequestBody(required = false) RejectCmd cmd) {
        assertWritable(id);
        String reason = cmd == null ? null : cmd.reason();
        return service.reject(id, actor(), reason);
    }

    @PostMapping("/{id}/receive")
    @RequirePerm("requisition:edit")
    public Map<String, Object> receive(@PathVariable Long id) {
        assertWritable(id);
        return service.receive(id, actor());
    }

    @PostMapping("/{id}/notes")
    @RequirePerm("requisition:edit")
    public Map<String, Object> addNote(@PathVariable Long id,
                                       @RequestBody(required = false) NoteCmd cmd) {
        assertWritable(id);
        String note = cmd == null ? null : cmd.note();
        service.addNote(id, actor(), note);
        return service.detail(id);
    }

    public record NoteCmd(String note) {}

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
                throw RequisitionService.badReq("请指定申领门店");
            return requested.trim();
        }
        if (requested == null || requested.isBlank())
            throw RequisitionService.badReq("请指定申领门店");
        if (!canWriteStore(requested.trim()))
            throw RequisitionService.badReq("无权在该门店操作申领单");
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
            throw RequisitionService.notFound();
    }

    private void assertWritable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw RequisitionService.badReq("无权操作该门店申领单");
    }
}
