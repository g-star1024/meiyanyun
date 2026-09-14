package com.meiyun.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 审计 outbox 中继（B48 卡1）：把各业务服务直发失败落箱的审计回投 audit_log。
 * 单行独立事务（AuditOutboxRelayTx REQUIRES_NEW）：坏行不堵队列；
 * append 与标 SENT 在同一事务提交，天然幂等不产生重复审计。
 */
@Component
public class AuditOutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(AuditOutboxRelay.class);
    private static final int BATCH_SIZE = 50;

    private final JdbcTemplate jdbcTemplate;
    private final AuditOutboxRelayTx relayTx;
    private final AuditService auditService;

    public AuditOutboxRelay(JdbcTemplate jdbcTemplate, AuditOutboxRelayTx relayTx, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.relayTx = relayTx;
        this.auditService = auditService;
    }

    @Scheduled(fixedDelay = 30_000, initialDelay = 15_000)
    public void relay() {
        List<Long> ids = jdbcTemplate.queryForList(
                "SELECT id FROM audit_outbox WHERE status = 'PENDING' ORDER BY id LIMIT " + BATCH_SIZE, Long.class);
        for (Long id : ids) {
            try {
                relayTx.relayOne(id);
            } catch (Exception e) {
                log.error("审计 outbox 中继失败 id={} : {}", id, e.getMessage());
                relayTx.markFailure(id, e);
            }
        }
    }

    /** 对账监测：状态计数 + 按来源服务聚合 + 最近失败明细。 */
    @Transactional(readOnly = true)
    public Map<String, Object> stats() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pending", countByStatus("PENDING"));
        result.put("dead", countByStatus("DEAD"));
        result.put("sentLast24h", jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_outbox WHERE status='SENT' AND sent_at > now() - interval '24 hours'",
                Long.class));
        result.put("bySource", jdbcTemplate.queryForList(
                "SELECT source_service, status, COUNT(*) AS cnt FROM audit_outbox GROUP BY 1, 2 ORDER BY 1, 2"));
        result.put("recentFailures", jdbcTemplate.queryForList(
                "SELECT id, source_service, biz_type, txn_no, retry_count, last_error, created_at FROM audit_outbox"
                        + " WHERE status='DEAD' OR retry_count > 0 ORDER BY id DESC LIMIT 5"));
        return result;
    }

    /** outbox 列表（status 限定 PENDING/SENT/DEAD 时过滤，最新 100 条）。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        if (status != null && List.of("PENDING", "SENT", "DEAD").contains(status)) {
            return jdbcTemplate.queryForList(
                    "SELECT * FROM audit_outbox WHERE status = ? ORDER BY id DESC LIMIT 100", status);
        }
        return jdbcTemplate.queryForList("SELECT * FROM audit_outbox ORDER BY id DESC LIMIT 100");
    }

    /** 死信人工重投：DEAD→PENDING 并记一条审计（经办人=当前操作员）。 */
    @Transactional
    public boolean retryOne(Long id, String actor) {
        int n = jdbcTemplate.update(
                "UPDATE audit_outbox SET status='PENDING', retry_count=0, last_error=NULL"
                        + " WHERE id=? AND status='DEAD'", id);
        if (n == 0) {
            return false;
        }
        auditService.append("AUDIT_OUTBOX", String.valueOf(id), actor, "RETRY", "{\"outboxId\":" + id + "}");
        return true;
    }

    private Long countByStatus(String status) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_outbox WHERE status=?", Long.class, status);
        return n == null ? 0L : n;
    }
}
