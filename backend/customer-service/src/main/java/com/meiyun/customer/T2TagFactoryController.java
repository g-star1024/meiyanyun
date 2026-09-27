package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T2-B2 标签工厂端点（/api/customer/t2/tagfactory，DESIGN-T2 T2-03，按域落 customer-service）。
 * 权限五码零新码（PermissionMatrix L124/L271-274/L434/L581-584/L715/L842 预埋）：查询/试算=tagFactory:view
 * （mock previewCompute 无权限闸）；创建=tagFactory:create；编辑/下线/删除=tagFactory:edit
 * （store 无 delete 码实证）；发布=tagFactory:publish；审批=tagFactory:approve。
 */
@RestController
@RequestMapping("/api/customer/t2/tagfactory")
public class T2TagFactoryController {

    private final TagFactoryService service;

    public T2TagFactoryController(TagFactoryService service) {
        this.service = service;
    }

    /** 标签新建/编辑请求体（字段名对齐前端契约；编辑为 patch 语义全可空，仅 mock pick 七字段生效）。 */
    public record TagReq(String code, String name, String category, String type, String sensitivity,
                         String valueType, String description, String sql, String refreshCron,
                         List<String> tags) {}

    @GetMapping("/tags")
    @RequirePerm("tagFactory:view")
    public List<TagFactoryService.TagView> tags() {
        return service.listTags();
    }

    @PostMapping("/tags")
    @RequirePerm("tagFactory:create")
    public TagFactoryService.TagView createTag(@RequestBody TagReq req) {
        return service.createTag(req.code(), req.name(), req.category(), req.type(), req.sensitivity(),
                req.valueType(), req.description(), req.sql(), req.refreshCron(), req.tags());
    }

    @PutMapping("/tags/{id}")
    @RequirePerm("tagFactory:edit")
    public TagFactoryService.TagView updateTag(@PathVariable Long id, @RequestBody TagReq req) {
        return service.updateTag(id, req.name(), req.description(), req.sql(), req.sensitivity(),
                req.refreshCron(), req.category(), req.tags());
    }

    @PostMapping("/tags/{id}/preview")
    @RequirePerm("tagFactory:view")
    public TagFactoryService.PreviewView preview(@PathVariable Long id) {
        return service.previewCompute(id);
    }

    @PostMapping("/tags/{id}/publish")
    @RequirePerm("tagFactory:publish")
    public TagFactoryService.TagView publish(@PathVariable Long id) {
        return service.publishTag(id);
    }

    @PostMapping("/tags/{id}/approve")
    @RequirePerm("tagFactory:approve")
    public TagFactoryService.TagView approve(@PathVariable Long id) {
        return service.approvePublish(id);
    }

    @PostMapping("/tags/{id}/offline")
    @RequirePerm("tagFactory:edit")
    public TagFactoryService.TagView offline(@PathVariable Long id) {
        return service.offlineTag(id);
    }

    @DeleteMapping("/tags/{id}")
    @RequirePerm("tagFactory:edit")
    public void delete(@PathVariable Long id) {
        service.deleteTag(id);
    }
}
