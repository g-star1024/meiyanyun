package com.meiyun.txn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * 棒⑤卡3 L161 免费护理核销客户端单测：fail-closed 错误语义（5xx/断连→502、4xx 透传中文、
 * 空项目列表零远程调用）与正常核销请求体三要素（customerId/orderNo/projectNames）。
 * 棒⑥卡4 L181 增逆向 refundForOrder 覆盖（返还行数解析、无流水 0、5xx→502 退款未终审文案）。
 */
class BenefitClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private BenefitClient client;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new BenefitClient(restTemplate);
        setField("customerBaseUrl", "http://customer.test");
        setField("internalToken", "test-token");
    }

    private void setField(String name, String value) throws Exception {
        Field f = BenefitClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(client, value);
    }

    @Test
    void 空项目列表_零远程调用直接返回() {
        assertDoesNotThrow(() -> client.consumeForOrder("C0001", "TX001", List.of()));
        assertDoesNotThrow(() -> client.consumeForOrder("C0001", "TX001", null));
        server.verify(); // 无任何 expectation = 未发起远程调用
    }

    @Test
    void customer_5xx_转为502且不降级() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/benefits/order-consume")))
                .andRespond(withServerError());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.consumeForOrder("C0001", "TX001", List.of("水光针")));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("回滚"));
    }

    @Test
    void customer_422_次数不足原样透传中文() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/benefits/order-consume")))
                .andRespond(withStatus(HttpStatus.UNPROCESSABLE_ENTITY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"UNPROCESSABLE\",\"message\":\"免费护理次数不足：项目「水光针」本期 2 次已用完\"}"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.consumeForOrder("C0001", "TX001", List.of("水光针")));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, ex.getStatusCode());
        assertEquals("免费护理次数不足：项目「水光针」本期 2 次已用完", ex.getReason());
    }

    @Test
    void 网络不可达_转为502不静默落单() throws Exception {
        BenefitClient raw = new BenefitClient(new RestTemplate());
        Field url = BenefitClient.class.getDeclaredField("customerBaseUrl");
        url.setAccessible(true);
        url.set(raw, "http://127.0.0.1:1");
        Field token = BenefitClient.class.getDeclaredField("internalToken");
        token.setAccessible(true);
        token.set(raw, "test-token");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> raw.consumeForOrder("C0001", "TX001", List.of("水光针")));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    @Test
    void 正常核销_请求体三要素齐全() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/benefits/order-consume")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"customerId\":\"C0001\""),
                        org.hamcrest.Matchers.containsString("\"orderNo\":\"TX20261002001\""),
                        org.hamcrest.Matchers.containsString("水光针"),
                        org.hamcrest.Matchers.containsString("玻尿酸"))))
                .andRespond(withSuccess("{\"ok\":true}", MediaType.APPLICATION_JSON));
        assertDoesNotThrow(() -> client.consumeForOrder("C0001", "TX20261002001", List.of("水光针", "玻尿酸")));
        server.verify();
    }

    @Test
    void 返还_正常解析冲正行数() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/benefits/order-refund")))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"orderNo\":\"TX20261003001\"")))
                .andRespond(withSuccess("{\"ok\":true,\"orderNo\":\"TX20261003001\",\"reversedCount\":2}",
                        MediaType.APPLICATION_JSON));
        assertEquals(2, client.refundForOrder("TX20261003001"));
        server.verify();
    }

    @Test
    void 返还_无核销流水返回0() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/benefits/order-refund")))
                .andRespond(withSuccess("{\"ok\":true,\"reversedCount\":0}", MediaType.APPLICATION_JSON));
        assertEquals(0, client.refundForOrder("TX20261003002"));
        server.verify();
    }

    @Test
    void 返还_5xx_转为502退款未终审() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/benefits/order-refund")))
                .andRespond(withServerError());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.refundForOrder("TX20261003003"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("退款未终审"));
    }
}
