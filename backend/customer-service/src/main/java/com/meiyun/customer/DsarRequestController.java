package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.persistence.criteria.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSAR（个人行使权利请求）工单流：PIPL 第 44-49 条刚性要求，30 日法定响应。
 * 详见 docs/DESIGN-P5-B57-CARD3-COMPLIANCE-2026-09-17.md §2。
 *
 * 卡1：实体 + Repo + 五端点 + 权限播种 + 状态流转。
 * 四类型执行逻辑（ACCESS 导出/DELETE 软删/RECTIFY 改字段/PORTABILITY JSON）留卡2。
 *
 * 五端点挂 /api/customer/dsar（铁律 1：网关 /api/customer 前缀已路由到 customer-service:8082）：
 *   POST   /dsar            新建（dsar:edit）
 *   GET    /dsar            列表（dsar:view，DataScope 门店域 + status/type/customerId 过滤）
 *   GET    /dsar/{id}       详情（dsar:view）
 *   PUT    /dsar/{id}/review 审核（dsar:edit）
 *   GET    /dsar/stats      四态计数 + 超期计数（dsar:view）
 */
@RestController
@RequestMapping("/api/customer/dsar")
public class DsarRequestController {

    @Autowired
    private DsarRequestService service;
    @Autowired
    private DsarRequestRepository dsarRepo;
    @Autowired
    private CustomerRepository customerRepo;
    @Autowired
    private AuditRecorder audit;

    /** 新建工单：校验 + 幂等 + 取号 + 设 deadline；落 DSAR/CREATE 审计。 */
    @PostMapping
    @RequirePerm("dsar:edit")
    public DsarRequest create(@RequestBody DsarCreateReq req) {
        DsarRequest r = service.create(req.customerId(), req.type(), req.description());
        audit.record("DSAR", r.getRequestNo(), DataScope.currentActor(), "CREATE",
                "{\"requestNo\":\"" + esc(r.getRequestNo())
                        + "\",\"customerId\":\"" + esc(r.getCustomerId())
                        + "\",\"type\":\"" + esc(r.getType())
                        + "\",\"description\":\"" + esc(r.getDescription()) + "\"}");
        return r;
    }

    /**
     * 列表：DataScope 门店域（通过 customer.storeCode JOIN 过滤）+ 按 status/type/customerId 过滤 + 分页。
     * dsar_request 表无 store_code 列，门店过滤通过先查本店客户 ids 再用 Specification in 过滤。
     */
    @GetMapping
    @RequirePerm("dsar:view")
    public Page<DsarRequest> list(
            @PageableDefault(size = 20, sort = "requestedAt") Pageable pageable,
            @RequestParam(required = false) String storeCode,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String customerId) {
        List<String> scopedCustomerIds = null;
        if (storeCode != null && !storeCode.isBlank()) {
            // DataScope 门店越权校验：不可读该门店则 404（不泄露数据是否存在）
            if (!DataScope.canReadStore(storeCode)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
            }
            scopedCustomerIds = customerRepo.findByStoreCode(storeCode).stream()
                    .map(Customer::getCustomerId).toList();
            if (scopedCustomerIds.isEmpty()) return Page.empty(pageable);
        }
        final List<String> cids = scopedCustomerIds;
        Specification<DsarRequest> spec = (root, q, cb) -> {
            var preds = new ArrayList<Predicate>();
            if (cids != null) preds.add(root.get("customerId").in(cids));
            if (status != null && !status.isBlank()) preds.add(cb.equal(root.get("status"), status));
            if (type != null && !type.isBlank()) preds.add(cb.equal(root.get("type"), type));
            if (customerId != null && !customerId.isBlank()) preds.add(cb.equal(root.get("customerId"), customerId));
            return cb.and(preds.toArray(new Predicate[0]));
        };
        return dsarRepo.findAll(spec, pageable);
    }

    /** 详情：不存在统一 404。 */
    @GetMapping("/{id}")
    @RequirePerm("dsar:view")
    public DsarRequest get(@PathVariable Long id) {
        return dsarRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在或无权查看"));
    }

    /** 审核：SUBMITTED/REVIEWING → FULFILLED/REJECTED；reject 时 rejectReason 必填；落 DSAR/REVIEW 审计。 */
    @PutMapping("/{id}/review")
    @RequirePerm("dsar:edit")
    public DsarRequest review(@PathVariable Long id, @RequestBody DsarReviewReq req) {
        // 先校验存在性（service.review 内部也查，但这里先查一次用于审计 before 状态）
        dsarRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在或无权查看"));
        DsarRequest r = service.review(id,
                req == null ? null : req.action(),
                req == null ? null : req.reviewer(),
                req == null ? null : req.rejectReason());
        audit.record("DSAR", r.getRequestNo(), DataScope.currentActor(), "REVIEW",
                "{\"requestNo\":\"" + esc(r.getRequestNo())
                        + "\",\"action\":\"" + esc("FULFILLED".equals(r.getStatus()) ? "fulfill" : "reject")
                        + "\",\"reviewer\":\"" + esc(r.getReviewer())
                        + "\",\"rejectReason\":\"" + esc(r.getRejectReason()) + "\"}");
        return r;
    }

    /** 四态计数（SUBMITTED/REVIEWING/FULFILLED/REJECTED）+ 超期计数（deadline_at<now 且未闭合）。 */
    @GetMapping("/stats")
    @RequirePerm("dsar:view")
    public Map<String, Object> stats() {
        Map<String, Long> byStatus = new LinkedHashMap<>();
        byStatus.put("SUBMITTED", 0L);
        byStatus.put("REVIEWING", 0L);
        byStatus.put("FULFILLED", 0L);
        byStatus.put("REJECTED", 0L);
        for (Object[] row : dsarRepo.countGroupByStatus()) {
            byStatus.put((String) row[0], (Long) row[1]);
        }
        long overdue = dsarRepo.countOverdue(OffsetDateTime.now(), List.of("FULFILLED", "REJECTED"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("byStatus", byStatus);
        m.put("overdue", overdue);
        return m;
    }

    /** 审计 payload 字符串转义（对齐 CustomerController.esc 范式，防 JSON 注入）。 */
    private String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 新建请求体：customerId + type + description。 */
    public record DsarCreateReq(String customerId, String type, String description) {}

    /** 审核请求体：action(fulfill/reject) + reviewer + rejectReason（reject 必填）。 */
    public record DsarReviewReq(String action, String reviewer, String rejectReason) {}
}
