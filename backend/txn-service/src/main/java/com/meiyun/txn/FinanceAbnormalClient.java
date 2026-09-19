package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * txn → finance 服务间客户端（B63 卡1 L84）：异常账务调整（FIN_ADJUSTMENT）审批终审/驳回后，
 * 交易域不直写财务域账单表，一律经 finance 内部端点以系统身份（X-Internal-Token，perms=["*"]）
 * 回写审批结论，拆库后零改动。
 *
 * <p>错误口径（与 {@link StoreConsumableClient} 终审扣库一致）：finance 返回 4xx
 * （404 账单不存在 / 400 参数）→ 透传其状态码与中文 message（审批事务回滚，待办不置 APPROVED/REJECTED）；
 * 网络异常 / 5xx → 502 中文（同样回滚，杜绝「审批办结但账单状态未回写」）。
 * finance 侧以 billNo 幂等（非 PENDING_APPROVAL 态重复投递不覆盖终审结论），本客户端失败重试安全。
 */
@Component
public class FinanceAbnormalClient {

    private static final Logger log = LoggerFactory.getLogger(FinanceAbnormalClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${finance.service.url:http://127.0.0.1:8087}")
    private String financeBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public FinanceAbnormalClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 终审/驳回回调：POST /api/finance/internal/abnormal/approval-callback。
     *
     * @param billNo   异常账单号（AB...，finance 侧幂等键）
     * @param approved true=终审通过（账单 APPROVED，后续人工处置动账）；false=驳回（账单 REJECTED）
     * @param reviewer 终审/驳回操作人（留痕）
     * @param comment  审批意见 / 驳回原因
     */
    public void applyResult(String billNo, boolean approved, String reviewer, String comment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("billNo", nz(billNo));
        body.put("approved", approved);
        body.put("reviewer", nz(reviewer));
        body.put("comment", comment);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            restTemplate.postForEntity(
                    financeBaseUrl + "/api/finance/internal/abnormal/approval-callback",
                    new HttpEntity<>(body, headers), Map.class);
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                // 业务拒绝（账单不存在 404 / 参数 400）：原样透传中文，调用方据此中止终审/驳回
                log.info("异常账务审批回调被 finance 拒绝 status={} billNo={} msg={}", status, billNo, msg);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("异常账务审批回调 finance 服务端错误 status={} billNo={} body={}",
                    status, billNo, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "异常账务审批回写失败：财务服务暂不可用，请稍后重试（本笔审批已回滚，账单结论未变更）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("异常账务审批回调 finance 调用异常 billNo={}: {}", billNo, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "异常账务审批回写失败：无法连接财务服务，请稍后重试（本笔审批已回滚，账单结论未变更）");
        }
    }

    /** 从 finance 错误体 {"message":"中文"} 提取中文原因；解析失败回落通用文案。 */
    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "财务服务拒绝了本次审批结论回写，请核对账单后重试";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
