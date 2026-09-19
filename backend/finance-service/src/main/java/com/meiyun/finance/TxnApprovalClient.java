package com.meiyun.finance;

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
 * finance → txn 服务间客户端（B63 卡1 L84）：异常账务处置单登记后，以系统身份
 * （X-Internal-Token）同步提交 txn 审批中心（bizType=FIN_ADJUSTMENT）。
 *
 * <p>错误口径（与 txn StoreConsumableClient 一致）：txn 4xx → 透传状态码与中文 message
 * （登记事务回滚，账单不留「已登记但无审批单」）；网络异常 / 5xx → 502 中文（同样回滚）。
 * txn 侧以 billNo 为 bizNo 幂等，重试安全（重复提交回返原审批单号）。
 */
@Component
public class TxnApprovalClient {

    private static final Logger log = LoggerFactory.getLogger(TxnApprovalClient.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RestTemplate restTemplate;

    @Value("${txn.service.url:http://127.0.0.1:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public TxnApprovalClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 同步提交异常账务调整审批：POST /api/txn/internal/finance/abnormal-approvals。
     *
     * @return txn 审批单号（AP...）
     */
    @SuppressWarnings("unchecked")
    public String submitAbnormalApproval(String billNo, String storeCode, String type,
                                         long amountFen, String reason, String applicant) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("billNo", billNo);
        body.put("storeCode", storeCode);
        body.put("type", type);
        body.put("amountFen", amountFen);
        body.put("reason", reason);
        body.put("applicant", applicant);
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<String, Object> resp = restTemplate.postForEntity(
                    txnBaseUrl + "/api/txn/internal/finance/abnormal-approvals",
                    new HttpEntity<>(body, headers), Map.class).getBody();
            if (resp != null) {
                Object no = resp.get("approvalNo");
                if (no != null && !String.valueOf(no).isBlank()) return String.valueOf(no);
            }
            log.error("异常账务审批提交 txn 返回缺少 approvalNo billNo={}", billNo);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "审批提交失败：审批中心应答异常，请稍后重试（登记已回滚）");
        } catch (HttpStatusCodeException e) {
            int status = e.getStatusCode().value();
            String msg = extractMessage(e.getResponseBodyAsString());
            if (status >= 400 && status < 500) {
                log.info("异常账务审批提交被 txn 拒绝 status={} billNo={} msg={}", status, billNo, msg);
                throw new ResponseStatusException(HttpStatus.valueOf(status), msg);
            }
            log.error("异常账务审批提交 txn 服务端错误 status={} billNo={} body={}",
                    status, billNo, e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "审批提交失败：审批中心暂不可用，请稍后重试（登记已回滚）");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("异常账务审批提交 txn 调用异常 billNo={}: {}", billNo, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "审批提交失败：无法连接审批中心，请稍后重试（登记已回滚）");
        }
    }

    private static String extractMessage(String body) {
        if (body != null && body.contains("\"message\"")) {
            try {
                String m = MAPPER.readTree(body).path("message").asText(null);
                if (m != null && !m.isBlank()) return m;
            } catch (Exception ignored) {
                // 落到兜底文案
            }
        }
        return "审批中心拒绝了本次提交，请核对后重试";
    }
}
