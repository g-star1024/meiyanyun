package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * txn → store 服务间客户端（B16 售卡）：售卡下单前经 store 内部端点取在售卡项模板，
 * 交易域不直读 catalog_product 表，定价/上下架/门店可见性一律由 store 域按自身语义裁决，拆库后零改动。
 *
 * <p>错误口径（与 CustomerCardClient / StoreConsumableClient 一致）：store 返回 4xx
 * （404 模板不存在或本店不可售 / 409 已下架 / 400 参数）→ 透传其状态码与中文 message
 * （下单事务回滚，不建售卡单）；网络异常 / 5xx → 502 中文（同样回滚，杜绝按错误价开单）。
 * 出参金额单位为分（priceFen），与订单金额口径一致。
 */
@Component
public class StoreCatalogClient {

    private static final Logger log = LoggerFactory.getLogger(StoreCatalogClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${store.service.url:http://127.0.0.1:8085}")
    private String storeBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public StoreCatalogClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 取在售模板：GET /api/stores/internal/catalog/{productCode}?storeCode=…。
     * 返回模板 Map（productCode/name/productType/category/sessions/validityDays/priceFen/
     * transferable/storeCode/status）；模板不存在或本店不可售 404、已下架 409 中文透传。
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getForSale(String productCode, String storeCode) {
        String code = productCode == null ? "" : productCode.trim();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String url = storeBaseUrl + "/api/stores/internal/catalog/"
                    + URLEncoder.encode(code, StandardCharsets.UTF_8)
                    + "?storeCode=" + URLEncoder.encode(nz(storeCode), StandardCharsets.UTF_8);
            Map<String, Object> resp = restTemplate.exchange(
                    url, HttpMethod.GET, new HttpEntity<>(headers), Map.class).getBody();
            if (resp == null) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "售卡取卡项失败：库存服务返回为空，请稍后重试（本笔开单已中止）");
            }
            return resp;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                // 业务拒绝（模板不存在/本店不可售 404、已下架 409）：原样透传中文，调用方据此中止开单
                log.info("售卡取模板被 store 拒绝 status={} productCode={} msg={}", status, code, msg);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("售卡取模板 store 服务端错误 status={} productCode={} body={}",
                    status, code, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "售卡取卡项失败：库存服务暂不可用，请稍后重试（本笔开单已中止，未下单）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("售卡取模板 store 调用异常 productCode={}: {}", code, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "售卡取卡项失败：无法连接库存服务，请稍后重试（本笔开单已中止，未下单）");
        }
    }

    /** 从 store 错误体 {"message":"中文"} 提取中文原因；解析失败回落通用文案。 */
    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "卡项模板不可售，请核对模板编码与门店后重试";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
