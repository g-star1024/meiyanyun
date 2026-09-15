package com.meiyun.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B49 卡9 合规中心业务服务（M1 集团屏 /m1-compliance）。
 *
 * <p>检查项只读列表 + 复检写接口。复检语义照前端 mock：pass=true → PASS，false → FAIL
 * （无 PENDING 中间态）；同时刷新 lastCheckAt/checker/updatedAt，remark 非空覆盖。
 * 复检与审计同事务：bizType=COMPLIANCE、action=RECHECK、txnNo=check id，
 * ip/risk/target 落 payload jsonb（audit_log 不扩列），risk=newStatus==FAIL ? HIGH : LOW，
 * ip 由 Controller 经 ClientIp 解析（L134：网关规范化 X-Forwarded-For/X-Real-IP 后透传）。
 */
@Service
public class ComplianceService {

    private final ComplianceCheckRepository checkRepo;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    public ComplianceService(ComplianceCheckRepository checkRepo, AuditService auditService,
                             ObjectMapper objectMapper) {
        this.checkRepo = checkRepo;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** 检查项列表（category/status 可选过滤，按 id 升序与 mock 展示顺序一致）。 */
    public List<ComplianceCheck> list(String category, String status) {
        Specification<ComplianceCheck> spec = (root, q, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (category != null && !category.isBlank()) {
                ps.add(cb.equal(root.get("category"), category.trim()));
            }
            if (status != null && !status.isBlank()) {
                ps.add(cb.equal(root.get("status"), status.trim()));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
        return checkRepo.findAll(spec, Sort.by("id").ascending());
    }

    /**
     * 复检：pass → PASS/FAIL，同事务写一条 COMPLIANCE/RECHECK 审计。
     * 操作人取 SecurityContext.currentStaffName()（中文姓名，贴 mock checker 显示口径，
     * 与卡8 BIZ_TARGET 审计 actor 一致）；clientIp 为网关透传的真实客户端 IP（L134）。
     */
    @Transactional
    public ComplianceCheck recheck(Long id, Boolean pass, String remark, String operator,
                                   String clientIp) {
        if (pass == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "复检结论 pass 必填");
        }
        ComplianceCheck check = checkRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "检查项不存在：" + id));
        String oldStatus = check.getStatus();
        String newStatus = pass ? "PASS" : "FAIL";
        OffsetDateTime now = OffsetDateTime.now();
        check.setStatus(newStatus);
        check.setLastCheckAt(now);
        check.setChecker(operator);
        if (remark != null && !remark.isBlank()) {
            check.setRemark(remark.trim());
        }
        check.setUpdatedAt(now);
        ComplianceCheck saved = checkRepo.save(check);

        String target = check.getTitle() + "（" + check.getStoreName() + "）";
        StringBuilder detail = new StringBuilder("复检「").append(check.getTitle()).append("」")
                .append("（").append(check.getStoreName()).append("）：")
                .append(oldStatus).append(" → ").append(newStatus);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("checkId", id);
        payload.put("target", target);
        payload.put("oldStatus", oldStatus);
        payload.put("newStatus", newStatus);
        if (remark != null && !remark.isBlank()) {
            detail.append("；说明：").append(remark.trim());
            payload.put("remark", remark.trim());
        }
        payload.put("detail", detail.toString());
        payload.put("risk", "FAIL".equals(newStatus) ? "HIGH" : "LOW");
        payload.put("ip", clientIp);
        auditService.append("COMPLIANCE", String.valueOf(id), operator, "RECHECK", toJson(payload));
        return saved;
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计载荷序列化失败", e);
        }
    }
}
