package com.meiyun.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B50 卡7（L134）：复检审计 payload.ip 必须落真实客户端 IP，不再写 "web" 占位。
 */
@ExtendWith(MockitoExtension.class)
class ComplianceServiceRecheckIpTest {

    @Mock ComplianceCheckRepository checkRepo;
    @Mock AuditService auditService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ComplianceCheck sampleCheck() {
        ComplianceCheck c = new ComplianceCheck();
        c.setId(7L);
        c.setCategory("QUALIFICATION");
        c.setTitle("医疗机构执业许可证");
        c.setRequirement("证照在有效期内");
        c.setStoreName("上海静安旗舰店");
        c.setStatus("PENDING");
        c.setLastCheckAt(OffsetDateTime.now().minusDays(3));
        c.setChecker("待检");
        c.setCreatedAt(OffsetDateTime.now().minusDays(10));
        c.setUpdatedAt(OffsetDateTime.now().minusDays(3));
        return c;
    }

    @Test
    void recheck_payloadCarriesRealClientIp() throws Exception {
        ComplianceCheck check = sampleCheck();
        when(checkRepo.findById(7L)).thenReturn(Optional.of(check));
        when(checkRepo.save(any(ComplianceCheck.class))).thenAnswer(inv -> inv.getArgument(0));

        ComplianceService service = new ComplianceService(checkRepo, auditService, objectMapper);
        service.recheck(7L, true, "材料齐全", "周岚", "203.0.113.7");

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(auditService).append(eq("COMPLIANCE"), eq("7"), eq("周岚"), eq("RECHECK"),
                payloadCap.capture());

        JsonNode payload = objectMapper.readTree(payloadCap.getValue());
        assertEquals("203.0.113.7", payload.get("ip").asText());
        assertEquals("PASS", payload.get("newStatus").asText());
        assertEquals("LOW", payload.get("risk").asText());
    }

    @Test
    void recheck_failStatusMarksHighRiskAndIp() throws Exception {
        ComplianceCheck check = sampleCheck();
        when(checkRepo.findById(7L)).thenReturn(Optional.of(check));
        when(checkRepo.save(any(ComplianceCheck.class))).thenAnswer(inv -> inv.getArgument(0));

        ComplianceService service = new ComplianceService(checkRepo, auditService, objectMapper);
        service.recheck(7L, false, null, "周岚", "192.168.3.21");

        ArgumentCaptor<String> payloadCap = ArgumentCaptor.forClass(String.class);
        verify(auditService).append(eq("COMPLIANCE"), eq("7"), eq("周岚"), eq("RECHECK"),
                payloadCap.capture());

        JsonNode payload = objectMapper.readTree(payloadCap.getValue());
        assertEquals("192.168.3.21", payload.get("ip").asText());
        assertEquals("FAIL", payload.get("newStatus").asText());
        assertEquals("HIGH", payload.get("risk").asText());
    }
}
