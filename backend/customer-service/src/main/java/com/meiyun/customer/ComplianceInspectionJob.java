package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 合规巡检定时任务（PIPL 小型处理者简化措施下的最低合规保障）。
 *
 * <p>设计文档 §4：每日 02:00（北京时区）三项巡检：
 * <ul>
 *   <li>DSAR 超期预警（WARN）：{@code deadline_at < now()+7d 且 status ∉ FULFILLED/REJECTED} → 7 日内即将超期</li>
 *   <li>DSAR 超期未响应（CRITICAL）：{@code deadline_at < now() 且 status ∉ FULFILLED/REJECTED} → 已超期法定 30 日</li>
 *   <li>同意过期（INFO）：{@code consent_at < now()-3y 且 consent_withdrawn_at IS NULL} → 同意授权超 3 年需重新征得</li>
 * </ul>
 *
 * <p>告警通道（设计文档 §4.2）：WARN → STORE_MGR 站内信 / CRITICAL → REGION_MGR+超管 / INFO → 仅落 audit。
 * 巡检命中清单统一落 {@code COMPLIANCE/INSPECT} 审计（payload 含分级计数 + 工单号/客户号清单），
 * 同时按告警级别 log.warn / log.error 输出；WARN/CRITICAL 另经 txn-service 内部端点
 * {@code /api/txn/internal/compliance-alert} 跨服务落站内信（txn 不可用软降级，下轮自愈）。
 *
 * <p>参照 {@link LevelMonthlyJob} 范式：actor 统一记 SYSTEM（定时任务无登录人），
 * 无命中时也落一条 INSPECT 审计（payload 标 0 命中，证明巡检已执行）。
 */
@Component
public class ComplianceInspectionJob {

    private static final Logger log = LoggerFactory.getLogger(ComplianceInspectionJob.class);
    private static final String ACTOR = "SYSTEM";
    private static final List<String> CLOSED = List.of("FULFILLED", "REJECTED");
    /** 同意有效期：3 年（PIPL 小型处理者简化措施下的口径）。 */
    private static final long CONSENT_EXPIRY_YEARS = 3L;
    /** DSAR 超期预警窗口：7 天。 */
    private static final long DSAR_WARN_DAYS = 7L;
    /** 业务时区：巡检 cron 与执行日均按北京时区（Asia/Shanghai）口径。 */
    private static final java.time.ZoneId BIZ_ZONE = java.time.ZoneId.of("Asia/Shanghai");

    private final DsarRequestRepository dsarRepo;
    private final CustomerRepository customerRepo;
    private final AuditRecorder audit;
    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://localhost:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public ComplianceInspectionJob(DsarRequestRepository dsarRepo,
                                  CustomerRepository customerRepo,
                                  AuditRecorder audit,
                                  RestTemplate restTemplate) {
        this.dsarRepo = dsarRepo;
        this.customerRepo = customerRepo;
        this.audit = audit;
        this.restTemplate = restTemplate;
    }

    /**
     * 每日 02:00（北京时区）执行（cron：秒 分 时 日 月 周）。
     * 三项巡检 + 统一落 COMPLIANCE/INSPECT 审计（payload 含分级计数 + 清单）。
     */
    @Scheduled(cron = "${meiyun.compliance-inspection.cron:0 0 2 * * ?}", zone = "Asia/Shanghai")
    public void dailyInspection() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime warnThreshold = now.plusDays(DSAR_WARN_DAYS);
        OffsetDateTime consentExpiryThreshold = now.minusYears(CONSENT_EXPIRY_YEARS);

        // 三项巡检
        List<DsarRequest> warnList = dsarRepo.findOverdueBefore(warnThreshold, CLOSED);
        List<DsarRequest> criticalList = dsarRepo.findOverdueBefore(now, CLOSED);
        // CRITICAL 是 WARN 的子集，从 WARN 清单中剔除 CRITICAL 部分得到纯 WARN
        List<Long> criticalIds = criticalList.stream().map(DsarRequest::getId).toList();
        List<DsarRequest> pureWarn = warnList.stream()
                .filter(r -> !criticalIds.contains(r.getId()))
                .toList();
        List<Customer> consentExpired = customerRepo.findConsentExpired(consentExpiryThreshold);

        // 日志输出（按告警级别）
        if (!criticalList.isEmpty()) {
            log.error("合规巡检 CRITICAL：DSAR 已超期未响应 {} 单，工单号：{}",
                    criticalList.size(), criticalList.stream()
                            .map(DsarRequest::getRequestNo).toList());
        }
        if (!pureWarn.isEmpty()) {
            log.warn("合规巡检 WARN：DSAR 7 日内将超期 {} 单，工单号：{}",
                    pureWarn.size(), pureWarn.stream()
                            .map(DsarRequest::getRequestNo).toList());
        }
        if (!consentExpired.isEmpty()) {
            log.info("合规巡检 INFO：同意授权超 3 年未撤回 {} 客户，客户号：{}",
                    consentExpired.size(), consentExpired.stream()
                            .map(Customer::getCustomerId).toList());
        }

        // 巡检执行日一律按北京时区取日（容器/JVM 默认 UTC，直接 now.toLocalDate() 会在北京
        // 02:00（=UTC 前一日 18:00）取到前一自然日，与 cron zone=Asia/Shanghai 口径错位）。
        String inspectDate = now.atZoneSameInstant(BIZ_ZONE).toLocalDate().toString();

        // 统一落 COMPLIANCE/INSPECT 审计（即使 0 命中也落，证明巡检已执行）
        String payload = buildInspectionPayload(now, pureWarn, criticalList, consentExpired);
        audit.record("COMPLIANCE", "INSPECT-" + inspectDate, ACTOR, "INSPECT", payload);

        // WARN/CRITICAL 跨服务站内信联动（txn 不可用软降级，不阻断审计落库）
        String bizRef = "INSPECT-" + inspectDate;
        sendComplianceAlert("CRITICAL", criticalList, bizRef);
        sendComplianceAlert("WARN", pureWarn, bizRef);

        log.info("合规巡检完成：WARN={} CRITICAL={} CONSENT_EXPIRED={}（执行时间 {}）",
                pureWarn.size(), criticalList.size(), consentExpired.size(), now);
    }

    /**
     * 构造巡检审计 payload（合法 JSON，audit_log.payload 为 jsonb）：
     * 含执行时间 + 三级计数 + 工单号/客户号清单（清单上限 20 条防 payload 过大）。
     */
    private String buildInspectionPayload(OffsetDateTime now,
                                          List<DsarRequest> warn,
                                          List<DsarRequest> critical,
                                          List<Customer> consentExpired) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"inspectedAt\":\"").append(now.toString()).append("\"");
        sb.append(",\"warnCount\":").append(warn.size());
        sb.append(",\"criticalCount\":").append(critical.size());
        sb.append(",\"consentExpiredCount\":").append(consentExpired.size());
        // WARN 清单（上限 20）
        sb.append(",\"warnRequests\":[");
        for (int i = 0; i < Math.min(warn.size(), 20); i++) {
            if (i > 0) sb.append(',');
            DsarRequest r = warn.get(i);
            sb.append("{\"requestNo\":\"").append(esc(r.getRequestNo()))
                    .append("\",\"customerId\":\"").append(esc(r.getCustomerId()))
                    .append("\",\"type\":\"").append(esc(r.getType()))
                    .append("\",\"deadlineAt\":\"").append(r.getDeadlineAt() == null ? "" : r.getDeadlineAt().toString())
                    .append("\"}");
        }
        sb.append("]");
        // CRITICAL 清单（上限 20）
        sb.append(",\"criticalRequests\":[");
        for (int i = 0; i < Math.min(critical.size(), 20); i++) {
            if (i > 0) sb.append(',');
            DsarRequest r = critical.get(i);
            sb.append("{\"requestNo\":\"").append(esc(r.getRequestNo()))
                    .append("\",\"customerId\":\"").append(esc(r.getCustomerId()))
                    .append("\",\"type\":\"").append(esc(r.getType()))
                    .append("\",\"deadlineAt\":\"").append(r.getDeadlineAt() == null ? "" : r.getDeadlineAt().toString())
                    .append("\"}");
        }
        sb.append("]");
        // 同意过期清单（上限 20）
        sb.append(",\"consentExpiredCustomers\":[");
        for (int i = 0; i < Math.min(consentExpired.size(), 20); i++) {
            if (i > 0) sb.append(',');
            Customer c = consentExpired.get(i);
            sb.append("{\"customerId\":\"").append(esc(c.getCustomerId()))
                    .append("\",\"consentAt\":\"").append(c.getConsentAt() == null ? "" : c.getConsentAt().toString())
                    .append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 跨服务联动：将 WARN/CRITICAL 命中清单推给 txn 通知中心内部端点
     * {@code /api/txn/internal/compliance-alert}，txn 按级别解析 STORE_MGR / REGION_MGR+超管
     * 接收人落站内信（category=SYSTEM）。txn 不可用软降级为空（本轮不通知，下轮自愈）。
     */
    private void sendComplianceAlert(String level, List<DsarRequest> requests, String bizRef) {
        if (requests == null || requests.isEmpty()) {
            return;
        }
        List<String> requestNos = requests.stream().map(DsarRequest::getRequestNo).toList();
        String title = "CRITICAL".equals(level)
                ? "合规告警：DSAR 超期未响应 " + requests.size() + " 单"
                : "合规预警：DSAR 即将超期 " + requests.size() + " 单";
        String content = ("CRITICAL".equals(level)
                ? "以下 DSAR 请求已超过法定 30 日响应时限仍未办结，请尽快处理："
                : "以下 DSAR 请求将在 7 日内超出法定 30 日响应时限，请尽快处理：")
                + joinTop(requestNos) + "（共 " + requests.size() + " 单）。";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<String, Object> body = Map.of(
                    "level", level,
                    "title", title,
                    "content", content,
                    "link", "/m1-compliance",
                    "bizRef", bizRef);
            restTemplate.exchange(txnBaseUrl + "/api/txn/internal/compliance-alert",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception ex) {
            log.warn("合规巡检站内信联动失败（txn 不可用，软降级下轮自愈）：level={} {}", level, ex.getMessage());
        }
    }

    /** 工单号清单截取前 10 条顿号拼接（防内容过长超出 notification.content 上限）。 */
    private static String joinTop(List<String> list) {
        List<String> top = list.size() > 10 ? list.subList(0, 10) : list;
        return String.join("、", top);
    }
}
