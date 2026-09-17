package com.meiyun.customer;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * DSAR 工单业务层：校验/幂等/取号/状态流转。
 *
 * 卡1 仅做状态流转（SUBMITTED → REVIEWING → FULFILLED/REJECTED）。
 * 四类型执行逻辑（ACCESS 导出 / DELETE 软删 / RECTIFY 改字段 / PORTABILITY JSON）留卡2。
 */
@Service
public class DsarRequestService {

    private static final List<String> TYPES = List.of("ACCESS", "DELETE", "RECTIFY", "PORTABILITY");
    private static final List<String> CLOSED = List.of("FULFILLED", "REJECTED");

    @Autowired
    private DsarRequestRepository dsarRepo;
    @Autowired
    private CustomerRepository customerRepo;

    /**
     * 新建 DSAR 工单：客户存在性 + 类型白名单 + 描述非空 + 幂等 + 取号 + 设 deadline。
     * 客户存在性校验：不存在统一 400（工单提交时客户编号由前端选择，不存在视为输入错误）。
     * 幂等：同 customer_id+type+SUBMITTED 视为重复请求（409），防止客户重复提交同类型请求。
     */
    public DsarRequest create(String customerId, String type, String description) {
        if (customerId == null || customerId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户编号不能为空");
        }
        if (!customerRepo.existsById(customerId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + customerId);
        }
        if (type == null || !TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "请求类型不合法，仅支持 ACCESS/DELETE/RECTIFY/PORTABILITY");
        }
        if (description == null || description.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求描述不能为空");
        }
        if (description.length() > 500) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求描述不能超过 500 字");
        }
        if (dsarRepo.existsByCustomerIdAndTypeAndStatus(customerId, type, "SUBMITTED")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该客户已有同类型未处理请求");
        }
        // 取号：DSAR+yyyyMMdd+三位序号，查今日同前缀最大序号+1（防重号）
        String today = OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "DSAR" + today;
        String maxNo = dsarRepo.maxRequestNoByPrefix(prefix + "%");
        int seq = 1;
        if (maxNo != null && maxNo.length() > prefix.length()) {
            try {
                seq = Integer.parseInt(maxNo.substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                // 异常序号兜底从 1 开始
            }
        }
        String requestNo = prefix + String.format("%03d", seq);

        DsarRequest r = new DsarRequest();
        r.setRequestNo(requestNo);
        r.setCustomerId(customerId);
        r.setType(type);
        r.setDescription(description);
        r.setStatus("SUBMITTED");
        // requestedAt/deadlineAt/createdAt 由 @PrePersist 兜底
        return dsarRepo.save(r);
    }

    /**
     * 审核：SUBMITTED/REVIEWING → FULFILLED/REJECTED。
     * 设计文档 §2.2 说 REVIEWING→FULFILLED/REJECTED，简化：SUBMITTED 也可直接审核（不强制先转 REVIEWING）。
     * 已闭合（FULFILLED/REJECTED）不可再审（409）。
     * reject 时 rejectReason 必填（400）；fulfill 时卡1 仅做状态流转，四类型执行逻辑留卡2。
     */
    public DsarRequest review(Long id, String action, String reviewer, String rejectReason) {
        DsarRequest r = dsarRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "工单不存在或无权查看"));
        if (CLOSED.contains(r.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "工单已闭合，不可再次审核");
        }
        if (action == null || (!action.equals("fulfill") && !action.equals("reject"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核动作不合法，仅支持 fulfill/reject");
        }
        if (reviewer == null || reviewer.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "审核人不能为空");
        }
        if (action.equals("reject")) {
            if (rejectReason == null || rejectReason.isBlank()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回原因必填");
            }
            if (rejectReason.length() > 200) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "驳回原因不能超过 200 字");
            }
            r.setStatus("REJECTED");
            r.setRejectReason(rejectReason);
        } else {
            // fulfill：卡1 仅做状态流转，四类型执行逻辑留卡2
            r.setStatus("FULFILLED");
            r.setFulfilledAt(OffsetDateTime.now());
        }
        r.setReviewer(reviewer);
        return dsarRepo.save(r);
    }
}
