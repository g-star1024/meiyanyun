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
