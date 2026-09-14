package com.meiyun.store.sop;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.security.RequirePerm;
import com.meiyun.security.SecurityContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * SOP 标准作业流程公开端点（B49 卡6，网关 /api/stores → store-service）。
 *
 * <p>读（模板/任务列表）走 {@code sop:view}；新建模板/派单/开始/勾选/完成走 {@code sop:edit}；
 * 发布走 {@code sop:approve}（与 M1 SOP 屏按钮 v-perm 键一致，不新增权限码）。
 * 数据域：模板为集团级流程库不分门店；任务读按 storeCode 收敛（超管/集团/品牌按传参，
 * REGION 限可见名单，STORE/SELF 强制本店），任务写按目标门店断言。
 */
@RestController
@RequestMapping("/api/stores/sop")
public class SopController {

    private final SopService service;

    public SopController(SopService service) {
        this.service = service;
    }

    // ---- 模板 ----

    @GetMapping("/templates")
    @RequirePerm("sop:view")
    public List<Map<String, Object>> templates(@RequestParam(value = "status", required = false) String status) {
        return service.listTemplates(status);
    }

    @PostMapping("/templates")
    @RequirePerm("sop:edit")
    public Map<String, Object> createTemplate(@RequestBody CreateTemplateCmd cmd) {
        if (cmd == null) throw SopService.badReq("请求体不能为空");
        return service.createTemplate(cmd.title(), cmd.category(), cmd.owner(),
                cmd.applicableStores(), cmd.steps(), actor());
    }

    @PostMapping("/templates/{id}/publish")
    @RequirePerm("sop:approve")
    public Map<String, Object> publishTemplate(@PathVariable("id") String id) {
        return service.publishTemplate(id, actor());
    }

    // ---- 任务 ----

    @GetMapping("/tasks")
    @RequirePerm("sop:view")
    public List<Map<String, Object>> tasks(@RequestParam(value = "storeCode", required = false) String storeCode,
                                           @RequestParam(value = "status", required = false) String status) {
        return service.listTasks(resolveReadStoreCode(storeCode), status);
    }

    /** 派单（数据域按目标门店写收敛）。 */
    @PostMapping("/tasks")
    @RequirePerm("sop:edit")
    public Map<String, Object> createTask(@RequestBody CreateTaskCmd cmd) {
        if (cmd == null) throw SopService.badReq("请求体不能为空");
        String storeCode = resolveWriteStoreCode(cmd.storeCode());
        return service.createTask(cmd.templateId(), storeCode, cmd.assignee(),
                cmd.priority(), cmd.dueDate(), actor());
    }

    @PostMapping("/tasks/{id}/start")
    @RequirePerm("sop:edit")
    public Map<String, Object> startTask(@PathVariable("id") String id) {
        assertWritableTask(id);
        return service.startTask(id, actor());
    }

    @PostMapping("/tasks/{id}/steps/{stepRef}/toggle")
    @RequirePerm("sop:edit")
    public Map<String, Object> toggleStep(@PathVariable("id") String id,
                                          @PathVariable("stepRef") String stepRef) {
        assertWritableTask(id);
        return service.toggleStep(id, stepRef, actor());
    }

    @PostMapping("/tasks/{id}/complete")
    @RequirePerm("sop:edit")
    public Map<String, Object> completeTask(@PathVariable("id") String id,
                                            @RequestBody(required = false) NoteCmd cmd) {
        assertWritableTask(id);
        return service.completeTask(id, cmd == null ? null : cmd.note(), actor());
    }

    // ---- 数据域 ----

    private void assertWritableTask(String idRef) {
        String sc = service.taskStoreCode(idRef);
        if (!canWriteStore(sc)) {
            throw SopService.badReq("无权操作该门店 SOP 任务");
        }
    }

    /** 查询门店收敛：超管/集团/品牌按传参（空=全量）；区域按可见名单；门店/自助强制本店。 */
    private String resolveReadStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return requested;
        }
        if ("REGION".equals(u.scope())) {
            if (requested != null && !requested.isBlank()) {
                return DataScope.canReadStore(requested.trim()) ? requested.trim() : "__NONE__";
            }
            List<String> stores = u.stores();
            return stores != null && stores.size() == 1 ? stores.get(0) : null;
        }
        return u.storeCode() == null || u.storeCode().isBlank() ? "__NONE__" : u.storeCode();
    }

    /** 写门店收敛：集团/品牌须显式传门店；区域须显式且在可见名单；门店/自助只能写本店。 */
    private String resolveWriteStoreCode(String requested) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            if (requested == null || requested.isBlank()) {
                throw SopService.badReq("请指定执行门店");
            }
            return requested.trim();
        }
        if (requested == null || requested.isBlank()) {
            throw SopService.badReq("请指定执行门店");
        }
        if (!canWriteStore(requested.trim())) {
            throw SopService.badReq("无权在该门店派单 SOP 任务");
        }
        return requested.trim();
    }

    private boolean canWriteStore(String storeCode) {
        LoginUser u = SecurityContext.get();
        if (u == null || u.isSuper() || "GROUP".equals(u.scope()) || "BRAND".equals(u.scope())) {
            return true;
        }
        if ("REGION".equals(u.scope())) {
            return DataScope.canReadStore(storeCode);
        }
        return storeCode.equals(u.storeCode());
    }

    private static String actor() {
        LoginUser u = SecurityContext.get();
        return u == null ? "system" : u.staffName();
    }

    /** 新建模板入参。 */
    public record CreateTemplateCmd(String title, String category, String owner,
                                    List<String> applicableStores, List<SopService.StepCmd> steps) {}

    /** 派单入参。 */
    public record CreateTaskCmd(String templateId, String storeCode, String assignee,
                                String priority, String dueDate) {}

    public record NoteCmd(String note) {}
}
