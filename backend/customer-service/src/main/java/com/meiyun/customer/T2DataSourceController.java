package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * T2-01 数据源注册端点（/api/customer/t2/datasources，DESIGN-T2 T2-01，按域落 customer-service）。
 * 权限四码零新码（PermissionMatrix L122/L266-268 预埋）：查询=collect:view；
 * 新建=collect:create；编辑/停用=collect:edit；连通探测=collect:sync。
 */
@RestController
@RequestMapping("/api/customer/t2/datasources")
public class T2DataSourceController {

    private final DataSourceService service;

    public T2DataSourceController(DataSourceService service) {
        this.service = service;
    }

    /** 新建请求体（code 建后不可变；type 三值 CDC/KAFKA/THIRD_PARTY）。 */
    public record CreateReq(String code, String name, String type, String endpoint, String description) {}

    /** 编辑请求体（仅 name/endpoint/description；code/type 不可变）。 */
    public record UpdateReq(String name, String endpoint, String description) {}

    @GetMapping
    @RequirePerm("collect:view")
    public List<DataSourceService.DataSourceView> list(@RequestParam(required = false) String type,
                                                       @RequestParam(required = false) String keyword) {
        return service.list(type, keyword);
    }

    @PostMapping
    @RequirePerm("collect:create")
    public DataSourceService.DataSourceView create(@RequestBody CreateReq req) {
        return service.create(req.code(), req.name(), req.type(), req.endpoint(), req.description());
    }

    @PutMapping("/{id}")
    @RequirePerm("collect:edit")
    public DataSourceService.DataSourceView update(@PathVariable Long id, @RequestBody UpdateReq req) {
        return service.update(id, req.name(), req.endpoint(), req.description());
    }

    @PostMapping("/{id}/disable")
    @RequirePerm("collect:edit")
    public DataSourceService.DataSourceView disable(@PathVariable Long id) {
        return service.disable(id);
    }

    /** 连通探测：仅 THIRD_PARTY 真实探测；CDC/KAFKA 如实拒绝（接入运行时归 v2）。 */
    @PostMapping("/{id}/sync")
    @RequirePerm("collect:sync")
    public DataSourceService.DataSourceView sync(@PathVariable Long id) {
        return service.sync(id);
    }
}
