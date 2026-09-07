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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * txn → customer 服务间客户端（B4 储值实扣 / 退卡回写）：交易域不直写客户域卡台账，
 * 一律经 customer 内部端点以系统身份（X-Internal-Token，perms=["*"]）调用，拆库后零改动。
 *
 * <p>错误口径：customer 返回 4xx（404 卡不存在 / 400 参数或卡状态 / 409 冲突 / 422 余额不足）
 * → 透传其状态码与中文 message（前端直接可读，收款事务回滚，不写 order_payment）；
 * 网络异常 / 5xx → 502 中文（同样回滚，杜绝「订单收款成功但卡没扣」）。
 * customer 侧以 orderNo / cancelNo 为幂等键，本客户端失败重试安全（不双扣/不双写）。
 */
@Component
public class CustomerCardClient {

    private static final Logger log = LoggerFactory.getLogger(CustomerCardClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${customer.service.url:http://127.0.0.1:8082}")
    private String customerBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public CustomerCardClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /** 储值消费扣款：POST /api/customer/internal/cards/consume（行锁扣余额 + CONSUME 流水）。 */
    public void consume(String cardNo, String customerId, long amount, String orderNo) {
        Map<String, Object> body = Map.of(
                "cardNo", nz(cardNo),
                "customerId", nz(customerId),
                "amount", amount,
                "orderNo", nz(orderNo));
        post("/api/customer/internal/cards/consume", body, "储值扣款");
    }

    /**
     * 售卡开卡（B16，售卡订单收款收齐后同事务回调）：POST /api/customer/internal/cards/issue。
     * customer 域实例化 member_card（product_code/card_type/expires_at/sale_no/gift_balance 溯源）
     * 并写首笔 RECHARGE 正额流水（bizRef=orderNo）；以售卡订单号 sale_no 幂等，收款回调重试不重复开卡。
     * 客户不存在 404 / 参数非法 400 中文透传（收款事务整笔回滚，杜绝「收款办结但卡未开」）。
     *
     * @param orderNo      售卡订单 OD 单号（开卡幂等键 sale_no）
     * @param customerId   客户编号
     * @param storeCode    售出门店
     * @param productCode  模板编码 CD-/CS-
     * @param cardType     CARD 储值卡 / COURSE 疗程卡（快照）
     * @param cardItem     卡名（模板名快照）
     * @param totalTimes   总次数（储值卡=1）
     * @param validityDays 有效期天数（0=长期）
     * @param priceFen     售价分（首笔充值额 = 订单金额）
     * @param giftBalance  赠送金分（≥0，本批售卡固定 0）
     * @param operator     收银操作人（仅审计留痕，台账动账人记 system）
     */
    public void issueCard(String orderNo, String customerId, String storeCode,
                          String productCode, String cardType, String cardItem,
                          int totalTimes, int validityDays, long priceFen,
                          long giftBalance, String operator) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("orderNo", nz(orderNo));
        body.put("customerId", nz(customerId));
        body.put("storeCode", nz(storeCode));
        body.put("productCode", nz(productCode));
        body.put("cardType", nz(cardType));
        body.put("cardItem", nz(cardItem));
        body.put("totalTimes", totalTimes);
        body.put("validityDays", validityDays);
        body.put("priceFen", priceFen);
        body.put("giftBalance", giftBalance);
        body.put("operator", nz(operator));
        post("/api/customer/internal/cards/issue", body, "售卡开卡");
    }

    /** 退卡终审回写：POST /api/customer/internal/cards/refund（卡置「已退卡」、余额清零、REFUND 流水）。 */
    public void refundCard(String cardNo, String cancelNo) {
        Map<String, Object> body = Map.of(
                "cardNo", nz(cardNo),
                "cancelNo", nz(cancelNo));
        post("/api/customer/internal/cards/refund", body, "退卡回写");
    }

    /**
     * 订单退款回加储值（B4.1，退款终审 RF）：POST /api/customer/internal/cards/refund-order。
     * customer 经原订单 CONSUME 流水反查扣款卡，写 REFUND 正额流水并加回余额；refundNo 幂等、
     * 防超退、卡已退卡 422 均由 customer 侧裁决，4xx 中文透传（终审事务回滚，退款单不置 REFUNDED）。
     */
    public void refundForOrder(String refundNo, String orderNo, long amount) {
        Map<String, Object> body = Map.of(
                "refundNo", nz(refundNo),
                "orderNo", nz(orderNo),
                "amount", amount);
        post("/api/customer/internal/cards/refund-order", body, "退款回加储值");
    }

    /**
     * 疗程卡扣次划扣联动（B6 G1，划扣双签后回调）：POST /api/customer/internal/cards/writeoff。
     * customer 权威卡台账行锁扣 remain_times（amount&gt;0 同时扣 balance）并写 CONSUME 流水
     * （bizRef=writeoffId，纯扣次写 0 额流水作幂等锚点）；次数/余额不足 422、卡不存在 404、
     * 非在用 400、WO 冲突 409 均中文透传（调用方据此中止回滚，杜绝「划扣成立卡未扣」）；
     * 同 WO 单号重放幂等。backfill=true 为存量回填，终态卡 422 由调用方收集为差异清单。
     */
    public void writeoff(String cardNo, String writeoffId, int timesUsed, long amount,
                         String storeCode, boolean backfill) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("cardNo", nz(cardNo));
        body.put("writeoffId", nz(writeoffId));
        body.put("timesUsed", timesUsed);
        body.put("amount", amount);
        body.put("storeCode", nz(storeCode));
        body.put("backfill", backfill);
        post("/api/customer/internal/cards/writeoff", body, backfill ? "存量划扣回填" : "疗程划扣");
    }

    /**
     * 划扣流水批量查询（B6 双账核对）：GET /api/customer/internal/cards/writeoff-ledgers。
     * 返回 WO 单号 → 流水（cardNo/changeType/amount/operator）；网络/5xx 异常 → 502 中文（核对中止，
     * 不容忍 customer 不可用时出「全部一致」的假结果）。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> writeoffLedgers(List<String> writeoffIds) {
        if (writeoffIds == null || writeoffIds.isEmpty()) return List.of();
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            String qs = writeoffIds.stream()
                    .filter(s -> s != null && !s.isBlank())
                    .map(s -> "bizRefs=" + URLEncoder.encode(s, StandardCharsets.UTF_8))
                    .collect(java.util.stream.Collectors.joining("&"));
            var resp = restTemplate.exchange(
                    customerBaseUrl + "/api/customer/internal/cards/writeoff-ledgers?" + qs,
                    HttpMethod.GET, new HttpEntity<>(headers), List.class);
            List<Map<String, Object>> body = resp.getBody();
            return body == null ? List.of() : body;
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            if (status >= 400 && status < 500) {
                log.info("划扣流水查询被 customer 拒绝 status={} body={}", status, e.getResponseBodyAsString());
                throw new ResponseStatusException(HttpStatus.valueOf(status),
                        extractMessage(e.getResponseBodyAsString()));
            }
            log.error("划扣流水查询 customer 服务端错误 status={}", status);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "双账核对失败：客户服务暂不可用，请稍后重试（本次未执行任何写入）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("划扣流水查询 customer 调用异常: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "双账核对失败：无法连接客户服务，请稍后重试（本次未执行任何写入）");
        }
    }

    private void post(String path, Map<String, Object> body, String label) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            restTemplate.postForEntity(customerBaseUrl + path, new HttpEntity<>(body, headers), Map.class);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                // 业务拒绝（余额不足 422 / 卡不存在 404 / 冲突 409 / 参数 400）：原样透传中文，调用方据此中止
                log.info("{}被 customer 拒绝 status={} msg={}", label, status, msg);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("{} customer 服务端错误 status={} body={}", label, status, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    label + "失败：客户服务暂不可用，请稍后重试（本笔操作已回滚，未扣款未记账）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("{} customer 调用异常: {}", label, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    label + "失败：无法连接客户服务，请稍后重试（本笔操作已回滚，未扣款未记账）");
        }
    }

    /** 从 customer 错误体 {"code":"...","message":"中文"} 提取中文原因；解析失败回落通用文案。 */
    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "客户服务拒绝了本次操作，请核对卡状态与余额后重试";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
