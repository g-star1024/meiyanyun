package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自动发赠金定时任务单测：有发放才落单条 GRANT_ISSUE/AUTO_GRANT 汇总审计；
 * 空转与故障轮不写审计（明细审计由 GrantService.issueByRule 负责）。
 */
@ExtendWith(MockitoExtension.class)
class AutoGrantJobTest {

    @Mock
    AutoGrantService autoGrantService;
    @Mock
    AuditRecorder audit;

    @Test
    void run_with_grants_records_single_summary_audit() {
        when(autoGrantService.scan()).thenReturn(new AutoGrantService.ScanResult(8, 2, null));
        new AutoGrantJob(autoGrantService, audit).run();

        verify(audit).record(org.mockito.ArgumentMatchers.eq("GRANT_ISSUE"),
                org.mockito.ArgumentMatchers.eq("AUTO-GRANT"),
                org.mockito.ArgumentMatchers.eq("SYSTEM"),
                org.mockito.ArgumentMatchers.eq("AUTO_GRANT"),
                org.mockito.ArgumentMatchers.eq("{\"scanned\":8,\"granted\":2}"));
    }

    @Test
    void run_with_zero_grants_records_no_summary_audit() {
        when(autoGrantService.scan()).thenReturn(new AutoGrantService.ScanResult(8, 0, null));
        new AutoGrantJob(autoGrantService, audit).run();

        verify(audit, never()).record(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void run_with_error_records_no_audit() {
        when(autoGrantService.scan())
                .thenReturn(new AutoGrantService.ScanResult(3, 0, "txn-service 已收款订单投影暂不可用"));
        new AutoGrantJob(autoGrantService, audit).run();

        verify(audit, never()).record(
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }
}
