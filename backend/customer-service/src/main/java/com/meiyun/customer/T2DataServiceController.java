package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T2-B3 数据服务端点（/api/customer/t2/dataservice，DESIGN-T2 T2-04，按域落 customer-service）。
 * 权限三码零新码（PermissionMatrix L125/L275-276/L435/L585-586/L716/L843 预埋）：查询=dataService:view；
 * 创建/发布/下线/审批=dataService:publish（mock createService/approvePermission 均闸 canPublish）；
 * 申请=dataService:apply。
 */
@RestController
@RequestMapping("/api/customer/t2/dataservice")
public class T2DataServiceController {

    private final DataServiceService service;

    public T2DataServiceController(DataServiceService service) {
        this.service = service;
    }

    /** 服务新建请求体（字段名对齐前端 createService input 契约）。 */
    public record ServiceReq(String name, String type, String endpoint, String method,
                             String description, List<String> fields, List<String> tags) {}

    /** 权限申请请求体（applicant 不收，后端一律 DataScope.currentActor() 防伪造）。 */
    public record ApplyReq(String reason) {}

    /** 调用上报请求体（latencyMs 缺省 0·success 缺省 true）。 */
    public record CallReq(Integer latencyMs, Boolean success) {}

    @GetMapping("/services")
    @RequirePerm("dataService:view")
    public List<DataServiceService.ServiceView> services(@RequestParam(required = false) String type,
                                                         @RequestParam(required = false) String keyword) {
        return service.listServices(type, keyword);
    }

    @GetMapping("/permissions")
    @RequirePerm("dataService:view")
    public List<DataServiceService.PermissionView> permissions() {
        return service.listPermissions();
    }

    @PostMapping("/services")
    @RequirePerm("dataService:publish")
    public DataServiceService.ServiceView createService(@RequestBody ServiceReq req) {
        return service.createService(req.name(), req.type(), req.endpoint(), req.method(),
                req.description(), req.fields(), req.tags());
    }

    @PostMapping("/services/{id}/publish")
    @RequirePerm("dataService:publish")
    public DataServiceService.ServiceView publish(@PathVariable Long id) {
        return service.publishService(id);
    }

    @PostMapping("/services/{id}/deprecate")
    @RequirePerm("dataService:publish")
    public DataServiceService.ServiceView deprecate(@PathVariable Long id) {
        return service.deprecateService(id);
    }

    /** 调用上报：逐条落 data_service_call_log（三列指标读时聚合透出）；权限=dataService:view（已授权调用方）。 */
    @PostMapping("/services/{id}/calls")
    @RequirePerm("dataService:view")
    public void recordCall(@PathVariable Long id, @RequestBody(required = false) CallReq req) {
        service.recordCall(id, req == null ? null : req.latencyMs(), req == null ? null : req.success());
    }

    @PostMapping("/services/{id}/apply")
    @RequirePerm("dataService:apply")
    public DataServiceService.PermissionView apply(@PathVariable Long id, @RequestBody ApplyReq req) {
        return service.applyPermission(id, req.reason());
    }

    @PostMapping("/permissions/{id}/approve")
    @RequirePerm("dataService:publish")
    public DataServiceService.PermissionView approve(@PathVariable Long id) {
        return service.approvePermission(id);
    }

    @PostMapping("/permissions/{id}/reject")
    @RequirePerm("dataService:publish")
    public DataServiceService.PermissionView reject(@PathVariable Long id) {
        return service.rejectPermission(id);
    }
}
