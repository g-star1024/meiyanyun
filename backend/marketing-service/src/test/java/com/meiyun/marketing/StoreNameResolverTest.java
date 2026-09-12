package com.meiyun.marketing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * StoreNameResolver 故障分层单测（JUnit5 + Mockito）：
 * 严格版 resolveNamesRequired 在远程故障时必须抛 StoreServiceUnavailableException（由写链路转 503），
 * 200 响应缺码保持正常返回（由调用方判 400）；宽松版 resolveNames 故障时仍降级空 Map（回显/放行路径不阻断）。
 */
@ExtendWith(MockitoExtension.class)
class StoreNameResolverTest {

    @Mock
    RestTemplate restTemplate;

    StoreNameResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new StoreNameResolver(restTemplate);
        ReflectionTestUtils.setField(resolver, "storeBaseUrl", "http://127.0.0.1:8085");
        ReflectionTestUtils.setField(resolver, "internalToken", "test-token");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<Map<String, String>> ok(Map<String, String> body) {
        return new ResponseEntity<>(body, org.springframework.http.HttpStatus.OK);
    }

    @Test
    void required_returnsMapWhenRemoteOk() {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("ST-BJ-001", "北京朝阳店");
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenReturn(ok(body));

        Map<String, String> out = resolver.resolveNamesRequired(List.of("ST-BJ-001"));

        assertEquals("北京朝阳店", out.get("ST-BJ-001"));
    }

    @Test
    void required_remoteFailureThrowsUnavailable() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        StoreServiceUnavailableException ex = assertThrows(StoreServiceUnavailableException.class,
                () -> resolver.resolveNamesRequired(List.of("ST-BJ-001")));
        assertTrue(ex.getMessage().contains("门店主数据暂不可用"));
        assertInstanceOf(ResourceAccessException.class, ex.getCause());
    }

    @Test
    void required_missingCodeInOkResponseIsNotFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class))).thenReturn(ok(new LinkedHashMap<>()));

        Map<String, String> out = resolver.resolveNamesRequired(List.of("ST-XX-999"));
        assertTrue(out.isEmpty());
    }

    @Test
    void lenient_remoteFailureDegradesToEmptyMap() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class),
                any(ParameterizedTypeReference.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        Map<String, String> out = resolver.resolveNames(List.of("ST-BJ-001"));
        assertNotNull(out);
        assertTrue(out.isEmpty());
    }

    @Test
    void blankInputSkipsRemoteCall() {
        assertTrue(resolver.resolveNamesRequired(List.of()).isEmpty());
        assertTrue(resolver.resolveNames(List.of("  ")).isEmpty());
        verifyNoInteractions(restTemplate);
    }
}
