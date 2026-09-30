package com.meiyun.audit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 棒③卡1：registerExemption 豁免登记契约（JUnit5 + Mockito）。
 *
 * <p>核心断言：①新登记落库豁免记录（快照=节点现值）且经 append 写 AUDIT_CHAIN_EXEMPT
 * 审计留痕（自洽）；②同一 audit_log_id 重复登记幂等——返回 duplicated=true，
 * 不重复落库、不重复写审计；③节点不存在抛 IllegalArgumentException 且零副作用。</p>
 */
@ExtendWith(MockitoExtension.class)
class AuditServiceExemptionTest {

    @Mock AuditRepository repository;
    @Mock AuditChainExemptionRepository exemptionRepository;
    @Mock JdbcTemplate jdbcTemplate;
    @InjectMocks AuditService service;

    @Test
    void newRegistration_savesSnapshotAndWritesAuditTrail() {
        AuditLog node = new AuditLog("TXN", "T258", "system", "SLA_ESCALATE",
                "{\"x\":1}", "p".repeat(64), "c".repeat(64));
        node.setId(258L);
        when(exemptionRepository.findByAuditLogId(258L)).thenReturn(Optional.empty());
        when(repository.findById(258L)).thenReturn(Optional.of(node));
        when(exemptionRepository.save(any(AuditChainExemption.class))).thenAnswer(inv -> {
            AuditChainExemption e = inv.getArgument(0);
            e.setId(7L);
            return e;
        });

        Map<String, Object> result = service.registerExemption(258L, "04-backlog L43 拍板豁免", "admin");

        assertFalse((Boolean) result.get("duplicated"));
        assertEquals(7L, result.get("id"));
        assertEquals(258L, result.get("auditLogId"));
        assertEquals("admin", result.get("registeredBy"));

        ArgumentCaptor<AuditChainExemption> exCap = ArgumentCaptor.forClass(AuditChainExemption.class);
        verify(exemptionRepository).save(exCap.capture());
        assertEquals("p".repeat(64), exCap.getValue().getPrevHash(), "快照 prev_hash 取节点现值");
        assertEquals("c".repeat(64), exCap.getValue().getCurHash(), "快照 cur_hash 取节点现值");
        assertEquals("04-backlog L43 拍板豁免", exCap.getValue().getReason());

        verify(jdbcTemplate).execute(anyString());
        ArgumentCaptor<AuditLog> trailCap = ArgumentCaptor.forClass(AuditLog.class);
        verify(repository).save(trailCap.capture());
        AuditLog trail = trailCap.getValue();
        assertEquals("AUDIT", trail.getBizType());
        assertEquals("EXEMPT:258", trail.getTxnNo());
        assertEquals("AUDIT_CHAIN_EXEMPT", trail.getAction());
        assertEquals("admin", trail.getActor());
        assertTrue(trail.getPayload().contains("\"auditLogId\":258"), "留痕 payload 含被豁免节点 id");
    }

    @Test
    void repeatedRegistration_returnsDuplicatedWithoutSideEffects() {
        AuditChainExemption existing = new AuditChainExemption(
                258L, "p".repeat(64), "c".repeat(64), "已登记", "admin");
        existing.setId(7L);
        when(exemptionRepository.findByAuditLogId(258L)).thenReturn(Optional.of(existing));

        Map<String, Object> result = service.registerExemption(258L, "重复登记", "admin");

        assertTrue((Boolean) result.get("duplicated"));
        assertEquals(7L, result.get("id"));
        verify(exemptionRepository, never()).save(any());
        verify(repository, never()).findById(any());
        verify(jdbcTemplate, never()).execute(anyString());
        verify(repository, never()).save(any(AuditLog.class));
    }

    @Test
    void missingNode_throwsWithoutSideEffects() {
        when(exemptionRepository.findByAuditLogId(999L)).thenReturn(Optional.empty());
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class,
                () -> service.registerExemption(999L, "节点不存在", "admin"));

        verify(exemptionRepository, never()).save(any());
        verify(jdbcTemplate, never()).execute(anyString());
    }
}
