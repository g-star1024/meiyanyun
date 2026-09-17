package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DSAR 工单业务层：校验/幂等/取号/状态流转 + 四类型履约执行。
 *
 * 卡1：状态流转（SUBMITTED → REVIEWING → FULFILLED/REJECTED）。
 * 卡2：四类型执行逻辑（ACCESS 导出 / DELETE 匿名化脱敏 / RECTIFY 更正说明 / PORTABILITY JSON）。
 *
 * 设计文档 §2.3 实证订正：
 * - ACCESS 不能复用 ai_privacy_export（那是审计区间哈希导出，非客户个人信息导出），
 *   改为 customer-service 内部用 ObjectMapper 序列化 Customer 实体为 JSON。
 * - DELETE 不能用 customer.status=DEACTIVATED（既有 CHECK 约束只允许活跃/沉睡/流失），
 *   也不能物理删除（customerId 被四服务十余张下游表 FK/快照引用，物理删除会摧毁
 *   append-only audit_log 哈希链与订单/随访/画像快照），改依 PIPL 第 73 条匿名化脱敏：
 *   不可逆抹除直接/间接标识字段、保留关联键与非标识业务字段并打 anonymized_at 标记。
 */
@Service
public class DsarRequestService {

    private static final List<String> TYPES = List.of("ACCESS", "DELETE", "RECTIFY", "PORTABILITY");
    private static final List<String> CLOSED = List.of("FULFILLED", "REJECTED");
    /** 匿名化后的姓名/手机号哨兵值（不可逆，非真实标识）。 */
    private static final String ANON_NAME = "匿名客户";
    private static final String ANON_PHONE = "00000000000";

    @Autowired
    private DsarRequestRepository dsarRepo;
    @Autowired
    private CustomerRepository customerRepo;
    @Autowired
    private ObjectMapper objectMapper;

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
     * reject 时 rejectReason 必填（400）。
     * fulfill 时按 type 执行四类型履约逻辑（卡2）：
     *   ACCESS：序列化 Customer 实体为 JSON → fulfillment_data（客户全量个人信息副本）
     *   DELETE：先匿名化脱敏 Customer 标识字段（PIPL 第 73 条）→ fulfillment_data（匿名化结果）
     *   RECTIFY：记录更正说明 JSON → fulfillment_data（实际字段修改由审核人在客户档案页操作）
     *   PORTABILITY：序列化 Customer 基础字段为 JSON → fulfillment_data（可携带数据副本）
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
            // fulfill：按 type 执行四类型履约逻辑；DELETE 先匿名化脱敏客户标识字段
            if ("DELETE".equals(r.getType())) {
                anonymizeCustomer(r.getCustomerId());
            }
            r.setStatus("FULFILLED");
            r.setFulfilledAt(OffsetDateTime.now());
            r.setFulfillmentData(buildFulfillmentData(r));
        }
        r.setReviewer(reviewer);
        return dsarRepo.save(r);
    }

    /**
     * 四类型履约数据组装（fulfill 时调用）：
     * - ACCESS/PORTABILITY：ObjectMapper 序列化 Customer 实体（客户全量个人信息 / 可携带数据）。
     *   两者数据源相同（Customer 实体已含基础信息+扩展字段），区别在语义（ACCESS=访问权，PORTABILITY=可携带权）。
     * - DELETE：匿名化结果 JSON（review 已先匿名化脱敏 Customer，此处读 anonymizedAt 落履约证据）。
     * - RECTIFY：更正说明 JSON（实际字段修改由审核人在客户档案页操作，此处只记录更正说明）。
     */
    private String buildFulfillmentData(DsarRequest r) {
        try {
            if ("DELETE".equals(r.getType())) {
                Customer c = customerRepo.findById(r.getCustomerId())
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + r.getCustomerId()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("anonymizedAt", c.getAnonymizedAt() == null ? null : c.getAnonymizedAt().toString());
                m.put("note", "DSAR 删除请求已执行（PIPL 第 73 条匿名化脱敏）：直接/间接标识字段已不可逆抹除，"
                        + "保留 customerId 关联键与非标识业务字段（等级/门店/渠道/累计消费/积分/状态/同意记录）；"
                        + "物理删除因下游 append-only 台账与审计哈希链技术上不可行，依第 47 条第 2 款停止处理并采取安全保护措施");
                m.put("customerId", r.getCustomerId());
                return objectMapper.writeValueAsString(m);
            }
            if ("RECTIFY".equals(r.getType())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("note", "更正说明（审核人根据请求描述在客户档案页手动更正字段后审核通过）");
                m.put("requestDescription", r.getDescription());
                m.put("customerId", r.getCustomerId());
                return objectMapper.writeValueAsString(m);
            }
            // ACCESS / PORTABILITY：序列化 Customer 实体
            Customer c = customerRepo.findById(r.getCustomerId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + r.getCustomerId()));
            return objectMapper.writeValueAsString(c);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // 序列化失败兜底：返回错误标记，不阻断审核流程
            return "{\"error\":\"履约数据序列化失败: " + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
    }

    /**
     * PIPL 第 47 条删除请求履约：物理删除不可行（customerId 被订单/随访/画像快照等多张
     * 下游表 FK/快照引用，物理删除会摧毁 append-only audit_log 哈希链），改依第 73 条匿名化脱敏——
     * 不可逆抹除直接/间接标识字段，保留 customerId 关联键与 level/storeCode/channel/totalSpend/
     * visitCount/ownerStaffId/points/status/consent 等非标识业务字段，并打 anonymizedAt 标记。
     * 幂等：已匿名化（anonymizedAt 非空）时直接返回，不重复修改。
     */
    private void anonymizeCustomer(String customerId) {
        Customer c = customerRepo.findById(customerId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "客户不存在: " + customerId));
        if (c.getAnonymizedAt() != null) {
            return;
        }
        c.setName(ANON_NAME);
        c.setPhone(ANON_PHONE);
        c.setGender("");
        c.setBirthDate(null);
        c.setAge(null);
        c.setSkinType(null);
        c.setConcerns(null);
        c.setAllergies(null);
        c.setAllergyNone(false);
        c.setAllergyNote(null);
        c.setIntentProjects(null);
        c.setIntentLevel(null);
        c.setBudget(null);
        c.setIntentNote(null);
        c.setAnonymizedAt(OffsetDateTime.now());
        customerRepo.save(c);
    }
}
