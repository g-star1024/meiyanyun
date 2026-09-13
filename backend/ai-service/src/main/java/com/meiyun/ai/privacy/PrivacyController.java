package com.meiyun.ai.privacy;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 隐私合规业务出口（A1-10 /ai/privacy）。
 * 类级权限 aiPrivacy:view（三角色可见）；写动作（脱敏启停/达标翻转/报告导出）另挂
 * aiPrivacy:edit（SUPER_ADMIN/REGION_MGR 持有，STORE_MGR 只读），全部写 AI_PRIVACY 审计。
 * 管理/治理面，非 AI invoke 功能（不接 FeatureCatalog/模型绑定/配额）。
 */
@RestController
@RequestMapping("/api/ai/privacy")
@RequirePerm("aiPrivacy:view")
public class PrivacyController {

    private final PrivacyService privacyService;

    public PrivacyController(PrivacyService privacyService) {
        this.privacyService = privacyService;
    }

    @GetMapping("/stats")
    public PrivacyService.PrivacyStats stats() {
        return privacyService.stats();
    }

    @GetMapping("/mask-rules")
    public List<PrivacyService.MaskRuleView> maskRules() {
        return privacyService.maskRules();
    }

    @PostMapping("/mask-rules/{id}/toggle")
    @RequirePerm("aiPrivacy:edit")
    public PrivacyService.MaskRuleView toggleMaskRule(@PathVariable Long id) {
        return privacyService.toggleMaskRule(id, DataScope.currentActor());
    }

    @GetMapping("/compliance-items")
    public List<PrivacyService.ComplianceItemView> complianceItems() {
        return privacyService.complianceItems();
    }

    @PostMapping("/compliance-items/{id}/toggle")
    @RequirePerm("aiPrivacy:edit")
    public PrivacyService.ComplianceItemView toggleComplianceItem(@PathVariable Long id) {
        return privacyService.toggleComplianceItem(id, DataScope.currentActor());
    }

    @GetMapping("/exports")
    public Page<PrivacyService.ExportView> exports(@RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return privacyService.exports(page, size);
    }

    @PostMapping("/exports")
    @RequirePerm("aiPrivacy:edit")
    public PrivacyService.ExportView createExport(@RequestBody PrivacyService.ExportCmd cmd) {
        return privacyService.createExport(cmd, DataScope.currentActor());
    }
}
