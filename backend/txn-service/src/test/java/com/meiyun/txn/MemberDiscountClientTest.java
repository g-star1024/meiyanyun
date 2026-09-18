package com.meiyun.txn;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

/**
 * B62 卡2 会员折扣客户端单测：fail-closed 错误语义（5xx/断连→502、4xx 透传、空客户 400）
 * 与五级折后计价算法 priceLine（HALF_UP 逐行、普通不折、行自洽）。
 */
class MemberDiscountClientTest {

    private RestTemplate restTemplate;
    private MockRestServiceServer server;
    private MemberDiscountClient client;

    @BeforeEach
    void setUp() throws Exception {
        restTemplate = new RestTemplate();
        server = MockRestServiceServer.createServer(restTemplate);
        client = new MemberDiscountClient(restTemplate);
        setField("customerBaseUrl", "http://customer.test");
        setField("internalToken", "test-token");
    }

    private void setField(String name, String value) throws Exception {
        Field f = MemberDiscountClient.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(client, value);
    }

    @Test
    void customer_service_5xx_转为502且不降级() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/level-discount")))
                .andRespond(withServerError());
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.getForCustomer("SC001"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
        assertTrue(ex.getReason() != null && ex.getReason().contains("回滚"));
    }

    @Test
    void customer_service_4xx_原样透传() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/level-discount")))
                .andRespond(withStatus(HttpStatus.FORBIDDEN)
                        .body("{\"message\":\"禁止访问\"}"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.getForCustomer("SC001"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals("禁止访问", ex.getReason());
    }

    @Test
    void 网络不可达_转为502不静默原价成交() throws Exception {
        MemberDiscountClient raw = new MemberDiscountClient(new RestTemplate());
        Field url = MemberDiscountClient.class.getDeclaredField("customerBaseUrl");
        url.setAccessible(true);
        url.set(raw, "http://127.0.0.1:1");
        Field token = MemberDiscountClient.class.getDeclaredField("internalToken");
        token.setAccessible(true);
        token.set(raw, "test-token");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> raw.getForCustomer("SC001"));
        assertEquals(HttpStatus.BAD_GATEWAY, ex.getStatusCode());
    }

    @Test
    void 空客户号_400() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> client.getForCustomer(" "));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void 正常响应解析等级折扣() {
        server.expect(requestTo(org.hamcrest.Matchers.containsString("/internal/level-discount")))
                .andRespond(withSuccess(
                        "[{\"customerId\":\"SC001\",\"level\":\"黑卡\",\"tier\":\"BLACK\",\"discount\":0.80}]",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        MemberDiscountClient.MemberDiscount d = client.getForCustomer("SC001");
        assertEquals("黑卡", d.level());
        assertEquals("BLACK", d.tier());
        assertEquals(0, new BigDecimal("0.80").compareTo(d.discount()));
        assertTrue(d.hasDiscount());
    }

    @Test
    void priceLine_五级折扣HALF_UP自洽() {
        long[] price = {10000, 333};
        int[] qty = {2, 3};
        double[] rates = {1.00, 0.95, 0.90, 0.85, 0.80};
        long[] expectTotal = {20999, 19948, 18900, 17849, 16798};
        for (int i = 0; i < rates.length; i++) {
            long net = 0, orig = 0, disc = 0;
            for (int j = 0; j < price.length; j++) {
                MemberDiscountClient.PricedLine l =
                        MemberDiscountClient.priceLine(price[j], qty[j], BigDecimal.valueOf(rates[i]));
                assertEquals(l.netUnitPrice() * qty[j], l.netAmount());
                assertEquals(l.originalAmount() - l.netAmount(), l.discountAmount());
                net += l.netAmount();
                orig += l.originalAmount();
                disc += l.discountAmount();
            }
            assertEquals(expectTotal[i], net, "等级 " + rates[i] + " 折后合计");
            assertEquals(orig - net, disc);
        }
    }

    @Test
    void priceLine_null折扣按原价() {
        MemberDiscountClient.PricedLine l = MemberDiscountClient.priceLine(333, 3, null);
        assertEquals(333, l.netUnitPrice());
        assertEquals(999, l.netAmount());
        assertEquals(0, l.discountAmount());
    }
}
