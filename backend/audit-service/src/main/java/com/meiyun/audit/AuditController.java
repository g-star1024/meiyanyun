package com.meiyun.audit;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** 追加一条审计（唯一写入入口）。 */
    @PostMapping
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

    public record AppendRequest(
            @NotBlank String bizType,
            String txnNo,
            @NotBlank String actor,
            @NotBlank String action,
            @NotBlank String payload
    ) {
    }
}
