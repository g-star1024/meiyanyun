package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * 营销设置兜底门店校验故障分层单测（JUnit5 + Mockito）：
 * 存在→保存通过；200 缺码（真不存在）→400；store-service 故障→503，不得误判为不存在。
 */
@ExtendWith(MockitoExtension.class)
class MarketingCfgServiceTest {

    @Mock
    MarketingCfgRepository cfgRepo;
    @Mock
    AuditRecorder audit;
    @Mock
    StoreNameResolver storeNameResolver;

    MarketingCfgService service;

    @BeforeEach
    void setUp() {
        service = new MarketingCfgService(cfgRepo, audit, storeNameResolver);
        lenient().when(cfgRepo.findById(1)).thenReturn(java.util.Optional.empty());
        lenient().when(cfgRepo.save(any(MarketingCfg.class))).thenAnswer(i -> i.getArgument(0));
    }

    private MarketingCfgService.ConfigCmd cmd(String fallbackStoreCode) {
        return new MarketingCfgService.ConfigCmd(
                2, true, "21:00", "09:00", true, 50000L,
                true, 1, List.of("SMS"), List.of("美团"), fallbackStoreCode);
    }

    private Map<String, String> nameMap(String code, String name) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(code, name);
        return m;
    }

    // ==================== B37 读视图 ConfigView ====================

    @Test
    void view_emptyRow_returns_nulls_and_empty_channel_lists() {
        MarketingCfgService.ConfigView v = service.view();

        assertNull(v.weeklyPushLimit());
        assertNull(v.quietHoursEnabled());
        assertNull(v.quietStart());
        assertNull(v.quietEnd());
        assertNull(v.holidayExempt());
        assertNull(v.largeCouponThresholdFen());
        assertNull(v.pushRequiresApproval());
        assertNull(v.approvalLevel());
        assertNull(v.writeoffFallbackStoreCode());
        assertEquals(List.of(), v.defaultPushChannels());
        assertEquals(List.of(), v.defaultAdChannels());
    }

    @Test
    void view_parses_stored_channel_json_into_lists() {
        MarketingCfg cfg = new MarketingCfg();
        cfg.setWeeklyPushLimit(2);
        cfg.setQuietHoursEnabled(true);
        cfg.setQuietStart("21:00");
        cfg.setQuietEnd("09:00");
        cfg.setHolidayExempt(true);
        cfg.setLargeCouponThresholdFen(50000L);
        cfg.setPushRequiresApproval(true);
        cfg.setApprovalLevel(1);
        cfg.setDefaultPushChannels("[\"SMS\",\"WECOM\"]");
        cfg.setDefaultAdChannels("[\"美团\",\"抖音\"]");
        cfg.setWriteoffFallbackStoreCode("ST-BJ-001");
        when(cfgRepo.findById(1)).thenReturn(java.util.Optional.of(cfg));

        MarketingCfgService.ConfigView v = service.view();

        assertEquals(2, v.weeklyPushLimit());
        assertTrue(v.quietHoursEnabled());
        assertEquals("21:00", v.quietStart());
        assertEquals("09:00", v.quietEnd());
        assertTrue(v.holidayExempt());
        assertEquals(50000L, v.largeCouponThresholdFen());
        assertTrue(v.pushRequiresApproval());
        assertEquals(1, v.approvalLevel());
        assertEquals(List.of("SMS", "WECOM"), v.defaultPushChannels());
        assertEquals(List.of("美团", "抖音"), v.defaultAdChannels());
        assertEquals("ST-BJ-001", v.writeoffFallbackStoreCode());
    }

    @Test
    void view_blank_or_broken_channel_json_falls_back_to_empty_lists() {
        MarketingCfg cfg = new MarketingCfg();
        cfg.setDefaultPushChannels("   ");
        cfg.setDefaultAdChannels("not-json");
        when(cfgRepo.findById(1)).thenReturn(java.util.Optional.of(cfg));

        MarketingCfgService.ConfigView v = service.view();

        assertEquals(List.of(), v.defaultPushChannels());
        assertEquals(List.of(), v.defaultAdChannels());
    }

    @Test
    void save_existingFallbackStorePasses() {
        when(storeNameResolver.resolveNamesRequired(List.of("ST-BJ-001")))
                .thenReturn(nameMap("ST-BJ-001", "北京朝阳店"));

        Map<String, Object> out = service.save(cmd("ST-BJ-001"));

        assertEquals(true, out.get("changed"));
        verify(cfgRepo).save(any(MarketingCfg.class));
    }

    @Test
    void save_blankFallbackStoreSkipsRemoteAndPasses() {
        Map<String, Object> out = service.save(cmd("  "));
        assertEquals(true, out.get("changed"));
        verifyNoInteractions(storeNameResolver);
    }

    @Test
    void save_missingCodeInOkResponseIs400() {
        when(storeNameResolver.resolveNamesRequired(anyList())).thenReturn(new LinkedHashMap<>());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(cmd("ST-XX-999")));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("兜底门店不存在"));
        verify(cfgRepo, never()).save(any());
    }

    @Test
    void save_remoteFailureIs503Not400() {
        when(storeNameResolver.resolveNamesRequired(anyList()))
                .thenThrow(new StoreServiceUnavailableException(
                        "store-service 门店主数据暂不可用：Connection refused",
                        new RuntimeException("Connection refused")));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.save(cmd("ST-BJ-001")));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, ex.getStatusCode());
        assertTrue(ex.getReason().contains("门店主数据服务暂不可用"));
        verify(cfgRepo, never()).save(any());
    }
}
