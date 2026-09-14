package com.meiyun.audit;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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
     */
    @PostMapping
    @RequirePerm("audit:view")
    public Map<String, Object> append(@RequestBody @Valid AppendRequest req) {
        AuditLog log = auditService.append(
                req.bizType(), req.txnNo(), req.actor(), req.action(), req.payload());
        return Map.of(
                "id", log.getId(),
                "curHash", log.getCurHash(),
                "prevHash", log.getPrevHash(),
                "createdAt", log.getCreatedAt().toString());
    }

    /** 审计全链巡检（不可篡改校验）。 */
    @GetMapping("/verify")
    @RequirePerm("audit:view")
    public AuditService.ChainVerifyResult verify() {
        return auditService.verifyChain();
    }

    @GetMapping
    @RequirePerm("audit:view")
    public List<AuditLog> list() {
        return auditService.findAll();
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
            @NotBlank String actor,
            @NotBlank String action,
            @NotBlank String payload
    ) {
    }
}
