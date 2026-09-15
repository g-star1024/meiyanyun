package com.meiyun.security;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClientIpTest {

    private static HttpServletRequest fakeRequest(Map<String, String> headers, String remoteAddr) {
        Map<String, String> h = new HashMap<>(headers);
        return (HttpServletRequest) Proxy.newProxyInstance(
                ClientIpTest.class.getClassLoader(),
                new Class<?>[]{HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getHeader".equals(method.getName())) {
                        return h.get((String) args[0]);
                    }
                    if ("getRemoteAddr".equals(method.getName())) {
                        return remoteAddr;
                    }
                    Class<?> rt = method.getReturnType();
                    if (rt == boolean.class) return false;
                    if (rt == int.class || rt == long.class) return 0;
                    return null;
                });
    }

    @Test
    void xffFirstEntryWins() {
        HttpServletRequest req = fakeRequest(Map.of(
                "X-Forwarded-For", "203.0.113.7, 10.0.0.1",
                "X-Real-IP", "10.0.0.1"), "172.16.0.3");
        assertEquals("203.0.113.7", ClientIp.resolve(req));
    }

    @Test
    void xffFirstEntryTrimmed() {
        HttpServletRequest req = fakeRequest(Map.of(
                "X-Forwarded-For", "  203.0.113.8 , 10.0.0.1"), "172.16.0.3");
        assertEquals("203.0.113.8", ClientIp.resolve(req));
    }

    @Test
    void fallsBackToXRealIp() {
        HttpServletRequest req = fakeRequest(Map.of("X-Real-IP", "198.51.100.9"), "172.16.0.3");
        assertEquals("198.51.100.9", ClientIp.resolve(req));
    }

    @Test
    void fallsBackToRemoteAddrWhenDirect() {
        HttpServletRequest req = fakeRequest(Map.of(), "192.168.3.21");
        assertEquals("192.168.3.21", ClientIp.resolve(req));
    }

    @Test
    void blankXffFallsThrough() {
        HttpServletRequest req = fakeRequest(Map.of(
                "X-Forwarded-For", "  ",
                "X-Real-IP", "198.51.100.10"), "172.16.0.3");
        assertEquals("198.51.100.10", ClientIp.resolve(req));
    }

    @Test
    void allMissingReturnsUnknown() {
        HttpServletRequest req = fakeRequest(Map.of(), null);
        assertEquals("unknown", ClientIp.resolve(req));
    }
}
