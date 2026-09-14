package com.meiyun.audit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/**
 * 中继单行事务边界。独立 bean 承载：REQUIRES_NEW 若与调度方法同类会因自调用绕过代理而失效。
 */
@Component
public class AuditOutboxRelayTx {

    static final int MAX_RETRY = 5;

    private final JdbcTemplate jdbcTemplate;
    private final AuditService auditService;

    public AuditOutboxRelayTx(JdbcTemplate jdbcTemplate, AuditService auditService) {
        this.jdbcTemplate = jdbcTemplate;
        this.auditService = auditService;
    }

    /** 回投一行：append 与标 SENT 同事务，要么都成功要么都回滚，不会重复追加。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void relayOne(Long id) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT id, biz_type, txn_no, actor, action, payload FROM audit_outbox"
                        + " WHERE id=? AND status='PENDING'", id);
        AuditLog saved = auditService.append(
                (String) row.get("biz_type"), (String) row.get("txn_no"),
                (String) row.get("actor"), (String) row.get("action"), (String) row.get("payload"));
        jdbcTemplate.update(
                "UPDATE audit_outbox SET status='SENT', audit_id=?, sent_at=now(), last_error=NULL WHERE id=?",
                saved.getId(), id);
    }

    /** 记一次失败：retry_count+1，达到上限置 DEAD（可监测、可人工重投）。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailure(Long id, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (msg.length() > 480) {
            msg = msg.substring(0, 480);
        }
        jdbcTemplate.update(
                "UPDATE audit_outbox SET retry_count=retry_count+1, last_error=?,"
                        + " status=CASE WHEN retry_count+1>=" + MAX_RETRY + " THEN 'DEAD' ELSE 'PENDING' END"
                        + " WHERE id=? AND status<>'SENT'",
                msg, id);
    }
}
