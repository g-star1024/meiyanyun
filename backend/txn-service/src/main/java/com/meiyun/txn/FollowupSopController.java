package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 术后随访 SOP 编排端点（P5-B31 卡B）：/api/txn/followup/sop。全部经网关既有 txn 路由，免改网关。
 *
 * <p>读 followup:view（模板查询 / 批次分页 / KPI 汇总），写 followup:edit（节点增改删 / 启停 /
 * 恢复默认 / 一键升级）。门店数据域由 {@link FollowupSopTemplateService} 统一 storeSpec 过滤。
 * 注意：本控制器字面路径段（/sop/...）优先于 {@link FollowupController} 的 /{id} 匹配，互不冲突。</p>
 */
@RestController
@RequestMapping("/api/txn/followup/sop")
public class FollowupSopController {

    private final FollowupSopTemplateService sopService;

    public FollowupSopController(FollowupSopTemplateService sopService) {
        this.sopService = sopService;
    }

    // ==================== 模板编排 ====================

    /** 集团通用模板全部节点（含停用，行号升序；未播种环境首次打开幂等懒初始化）。 */
    @GetMapping("/template")
    @RequirePerm("followup:view")
    public TemplateView template() {
        return new TemplateView(FollowupSopDefaults.DEFAULT_TEMPLATE_NO,
                FollowupSopDefaults.DEFAULT_TEMPLATE_NAME, sopService.getTemplate().stream()
                        .map(FollowupSopController::toNodeView).toList());
    }

    /** 新增自定义节点（stage=MANUAL）：名称/天数/方式，服务层白名单校验 + 审计。 */
    @PostMapping("/template/nodes")
    @RequirePerm("followup:edit")
    public TemplateView addNode(@RequestBody NodeCmdReq req) {
        return refresh(sopService.addNode(new FollowupSopTemplateService.NodeCmd(
                req.label(), req.dayOffset(), req.method())));
    }

    /** 改节点：仅更新非空字段（名称/天数/方式），内置与自定义节点均可改。 */
    @PutMapping("/template/nodes/{id}")
    @RequirePerm("followup:edit")
    public TemplateView updateNode(@PathVariable Long id, @RequestBody NodeCmdReq req) {
        return refresh(sopService.updateNode(id, new FollowupSopTemplateService.NodeCmd(
                req.label(), req.dayOffset(), req.method())));
    }

    /** 启停节点（停用后不参与新批次排程，历史批次不变）。 */
    @PostMapping("/template/nodes/{id}/toggle")
    @RequirePerm("followup:edit")
    public TemplateView toggleNode(@PathVariable Long id, @RequestBody ToggleReq req) {
        return refresh(sopService.toggleNode(id, req == null || req.enabled()));
    }

    /** 删除节点：仅 MANUAL 自定义节点可删，内置节点 400 中文引导停用。 */
    @DeleteMapping("/template/nodes/{id}")
    @RequirePerm("followup:edit")
    public TemplateView deleteNode(@PathVariable Long id) {
        return refresh(sopService.deleteNode(id));
    }

    /** 恢复默认：清空全部节点（含自定义），重建内置四节点并全启用。 */
    @PostMapping("/template/reset")
    @RequirePerm("followup:edit")
    public TemplateView resetTemplate() {
        return refresh(sopService.resetTemplate());
    }

    // ==================== 批次执行看板 ====================

    /** 批次分页聚合：keyword 模糊客户名/项目/批次号；未完结在前、服务日期倒序；节点内嵌随访读模型。 */
    @GetMapping("/batches")
    @RequirePerm("followup:view")
    public Page<BatchView> batches(@RequestParam(required = false) String storeCode,
                                   @RequestParam(required = false) String keyword,
                                   @PageableDefault(size = 20) Pageable pageable) {
        return sopService.batches(storeCode, keyword, pageable).map(FollowupSopController::toBatchView);
    }

    /** 看板五键：activeBatches/finishedBatches/sopPending/sopOverdue/needEscalation。 */
    @GetMapping("/summary")
    @RequirePerm("followup:view")
    public Map<String, Object> summary(@RequestParam(required = false) String storeCode) {
        return sopService.summary(storeCode);
    }

    /** 一键升级本店超期未升级 SOP 节点（FIFO 50，与 60s 巡检同执行器/通知幂等），返回实际升级条数。 */
    @PostMapping("/escalate")
    @RequirePerm("followup:edit")
    public Map<String, Object> escalate(@RequestParam(required = false) String storeCode) {
        return Map.of("escalated", sopService.escalateOverdue(storeCode));
    }

    // ==================== 视图映射 ====================

    private TemplateView refresh(List<FollowupSopTemplateNode> nodes) {
        return new TemplateView(FollowupSopDefaults.DEFAULT_TEMPLATE_NO,
                FollowupSopDefaults.DEFAULT_TEMPLATE_NAME,
                nodes.stream().map(FollowupSopController::toNodeView).toList());
    }

    private static NodeView toNodeView(FollowupSopTemplateNode n) {
        return new NodeView(String.valueOf(n.getId()), n.getTemplateNo(), n.getLineNo(),
                n.getStage(), n.getLabel(), n.getDayOffset(), n.getMethod(),
                Boolean.TRUE.equals(n.getEnabled()));
    }

    private static BatchView toBatchView(FollowupSopTemplateService.BatchAgg agg) {
        FollowupSopBatch b = agg.batch();
        List<FollowupController.FollowupView> nodes = agg.nodes().stream()
                .map(FollowupController::toView).toList();
        return new BatchView(b.getBatchNo(), b.getCustomerId(), b.getCustomerName(),
                b.getProject(), b.getRelatedOrderNo(), b.getServiceDate(),
                agg.total(), agg.done(), agg.overdue(), agg.finished(), nodes);
    }

    /** 模板读模型：模板号 + 名称 + 节点列表（前端 sopTemplate 1:1，节点 id 字符串用于增改删路径）。 */
    public record TemplateView(String templateNo, String name, List<NodeView> nodes) {}

    /** 节点读模型：对齐前端 SopNodeDef（id/id 行号为编排页后端编排列扩展字段）。 */
    public record NodeView(String id, String templateNo, int lineNo,
                           String stage, String label, int dayOffset, String method, boolean enabled) {}

    /** 批次看板行：批次头 + 完成/超期计数 + 完结标记 + 内嵌全部节点（节点即 24 字段随访读模型）。 */
    public record BatchView(String batchId, String customerId, String customerName,
                            String project, String relatedOrderNo, LocalDate serviceDate,
                            int total, int done, int overdue, boolean finished,
                            List<FollowupController.FollowupView> nodes) {}

    /** 节点新增/局部更新请求：新增时三字段必填（服务层校验中文错误），更新时仅非空字段生效。 */
    public record NodeCmdReq(String label, Integer dayOffset, String method) {}

    /** 启停请求：{"enabled":true}；空体默认视为启用。 */
    public record ToggleReq(Boolean enabled) {}
}
