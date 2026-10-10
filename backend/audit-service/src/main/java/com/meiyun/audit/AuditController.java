package com.meiyun.audit;

import com.meiyun.security.AuditBoundary;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;
    private final AuditOutboxRelay outboxRelay;

    public AuditController(AuditService auditService, AuditOutboxRelay outboxRelay) {
        this.auditService = auditService;
        this.outboxRelay = outboxRelay;
    }

    /**
     * 追加一条审计（唯一写入入口）。
     * 双通道鉴权：各业务服务携带 X-Internal-Token 系统身份写入，或持 audit:view 的员工（如审计/超管）。
     * 路径口径（F3 卡1 方案 A）：服务间通道迁 /internal/entries 伞下（AuthInterceptor 仅放行
     * 系统 token，员工 JWT 403），根路径保留员工 JWT 直连（token 在非伞下不再授予身份）。
     * actor 口径按通道强制收敛：X-Internal-Token 系统身份（staffId=system）时业务服务已在本地
     * SecurityContext 代填真实操作人（代操作取 realSub），沿用请求体 actor；员工 JWT 直连（合规
     * impersonate 留痕）时一律以 JWT 真实人为准（realSub 优先），忽略请求体 actor，payload 服务端
     * 注入 act/realSub，杜绝登录人伪造他人留痕破坏审计不可抵赖性。
     */
    @PostMapping({"", "/internal/entries"})
    @RequirePerm("audit:view")
    public Map<String, Object> append(@RequestBody @Valid AppendRequest req) {
        boolean systemChannel = "system".equals(DataScope.currentActor());
        String actor = systemChannel
                ? (req.actor() == null || req.actor().isBlank() ? "system" : req.actor())
                : DataScope.currentRealActor();
        String payload = systemChannel ? req.payload() : AuditBoundary.enrichPayload(req.payload());
        AuditLog log = auditService.append(
                req.bizType(), req.txnNo(), actor, req.action(), payload);
        return Map.of(
                "id", log.getId(),
                "curHash", log.getCurHash(),
                "prevHash", log.getPrevHash(),
                "createdAt", log.getCreatedAt().toString());
    }

    /** 审计全链巡检（不可篡改校验；已豁免断链与新增断链分离呈现）。 */
    @GetMapping("/verify")
    @RequirePerm("audit:view")
    public AuditService.ChainVerifyResult verify() {
        return auditService.verifyChain();
    }

    /**
     * 断链豁免登记（棒③卡1）。双通道鉴权同 append：X-Internal-Token 系统身份或
     * 持 audit:view 的员工。登记人取通道口径：系统通道=system，员工通道=JWT 真实人，
     * 杜绝冒名登记。幂等：同一 audit_log_id 重复登记返回已存在记录（duplicated=true）。
     * 登记行为本身写入审计链（action=AUDIT_CHAIN_EXEMPT）自洽留痕。
     */
    @PostMapping("/exemptions")
    @RequirePerm("audit:view")
    public Map<String, Object> registerExemption(@RequestBody @Valid ExemptionRequest req) {
        boolean systemChannel = "system".equals(DataScope.currentActor());
        String registeredBy = systemChannel ? "system" : DataScope.currentRealActor();
        return auditService.registerExemption(req.auditLogId(), req.reason(), registeredBy);
    }

    /** 豁免清单全量（登记顺序）：已豁免断链的理由/登记人/快照，供前端分离呈现。 */
    @GetMapping("/exemptions")
    @RequirePerm("audit:view")
    public List<AuditChainExemption> exemptions() {
        return auditService.listExemptions();
    }

    @GetMapping
    @RequirePerm("audit:view")
    public List<AuditLog> list() {
        return auditService.findAll();
    }

    /**
     * 审计日志分页检索（M1 集团审计日志页）：bizType/actor 精确过滤、created_at 时间范围、
     * keyword 模糊匹配单号/动作/操作人/载荷，按 id 倒序。既有 GET /api/audit 全链端点契约不变。
     */
    @GetMapping("/page")
    @RequirePerm("audit:view")
    public AuditService.PageResult page(
            @RequestParam(required = false) String bizType,
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditService.search(bizType, actor, keyword, from, to, page, size);
    }

    /** 审计统计面：总数/近 24h/操作人数/模块分布（页面 KPI 卡与模块过滤器同源）。 */
    @GetMapping("/facets")
    @RequirePerm("audit:view")
    public Map<String, Object> facets() {
        return auditService.facets();
    }

    /** outbox 对账监测：状态计数 + 按来源服务聚合 + 最近失败明细。 */
    @GetMapping("/outbox/stats")
    @RequirePerm("audit:view")
    public Map<String, Object> outboxStats() {
        return outboxRelay.stats();
    }

    /** outbox 列表（可按 status=PENDING/SENT/DEAD 过滤，最新 100 条）。 */
    @GetMapping("/outbox")
    @RequirePerm("audit:view")
    public List<Map<String, Object>> outboxList(@RequestParam(required = false) String status) {
        return outboxRelay.list(status);
    }

    /** 死信人工重投：DEAD→PENDING，并记一条审计（经办人=当前操作员）。 */
    @PostMapping("/outbox/{id}/retry")
    @RequirePerm("audit:view")
    public Map<String, Object> outboxRetry(@PathVariable Long id) {
        if (!outboxRelay.retryOne(id, DataScope.currentActor())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "outbox 记录不存在或非 DEAD 状态");
        }
        return Map.of("id", id, "status", "PENDING");
    }

    public record AppendRequest(
            @NotBlank String bizType,
            String txnNo,
            // 员工 JWT 通道忽略此字段（服务端强制取 JWT staffId）；仅 X-Internal-Token 系统通道沿用。
            String actor,
            @NotBlank String action,
            @NotBlank String payload
    ) {
    }

    public record ExemptionRequest(
            @NotNull Long auditLogId,
            @NotBlank String reason
    ) {
    }
}
