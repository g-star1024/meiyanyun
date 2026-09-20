package com.meiyun.store.daily;

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
@RequestMapping("/api/stores/daily-reports")
public class DailyController {

    private final DailyService service;

    public DailyController(DailyService service) {
        this.service = service;
    }

    @GetMapping
    @RequirePerm("daily:view")
    public List<Map<String, Object>> list(@RequestParam(required = false) String storeCode,
                                          @RequestParam(required = false) String status) {
        return service.list(resolveReadStoreCode(storeCode), status);
    }

    @GetMapping("/today")
    @RequirePerm("daily:view")
    public Map<String, Object> today(@RequestParam(required = false) String storeCode) {
        return service.today(resolveTodayStoreCode(storeCode), actor());
    }

    @GetMapping("/{id}")
    @RequirePerm("daily:view")
    public Map<String, Object> detail(@PathVariable Long id) {
        Map<String, Object> row = service.detail(id);
        assertReadable(row);
        return row;
    }

    @PostMapping("/{id}/fields")
    @RequirePerm("daily:edit")
    public Map<String, Object> saveFields(@PathVariable Long id,
                                          @RequestBody(required = false) DailyService.FieldsCmd cmd) {
        assertWritable(id);
        return service.saveFields(id, cmd, actor());
    }

    @PostMapping("/{id}/hourly")
    @RequirePerm("daily:edit")
    public Map<String, Object> saveHourly(@PathVariable Long id,
                                          @RequestBody(required = false) DailyService.HourlyCmd cmd) {
        assertWritable(id);
        return service.saveHourly(id, cmd, actor());
    }

    @PostMapping("/{id}/todos")
    @RequirePerm("daily:edit")
    public Map<String, Object> addTodo(@PathVariable Long id,
                                       @RequestBody(required = false) DailyService.TodoCmd cmd) {
        assertWritable(id);
        return service.addTodo(id, cmd, actor());
    }

    @PostMapping("/{id}/todos/{todoId}/toggle")
    @RequirePerm("daily:edit")
    public Map<String, Object> toggleTodo(@PathVariable Long id,
                                          @PathVariable Long todoId) {
        assertWritable(id);
        return service.toggleTodo(id, todoId, actor());
    }

    @DeleteMapping("/{id}/todos/{todoId}")
    @RequirePerm("daily:edit")
    public Map<String, Object> removeTodo(@PathVariable Long id,
                                          @PathVariable Long todoId) {
        assertWritable(id);
        return service.removeTodo(id, todoId, actor());
    }

    @PostMapping("/{id}/submit")
    @RequirePerm("daily:submit")
    public Map<String, Object> submit(@PathVariable Long id) {
        assertWritable(id);
        return service.submit(id, actor());
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

    private String resolveTodayStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank())
                throw DailyService.badReq("请指定门店");
            if (!DataScope.canReadStore(requested.trim()))
                throw DailyService.badReq("无权访问该门店日报");
            return requested.trim();
        }
        if (u.storeCode() == null || u.storeCode().isBlank())
            throw DailyService.badReq("未获取到当前门店，请稍后重试");
        if (requested != null && !requested.isBlank() && !requested.trim().equals(u.storeCode()))
            throw DailyService.badReq("无权访问该门店日报");
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
            throw DailyService.notFound();
    }

    private void assertWritable(Long id) {
        Map<String, Object> row = service.detail(id);
        Object sc = row.get("storeCode");
        if (!(sc instanceof String s) || !canWriteStore(s))
            throw DailyService.badReq("无权操作该门店日报");
    }
}
