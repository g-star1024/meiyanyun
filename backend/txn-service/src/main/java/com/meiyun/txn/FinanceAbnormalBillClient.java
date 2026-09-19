package com.meiyun.txn;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * txn → finance 异常账单<b>只读</b>聚合客户端（B63 卡2 L83）：统一异常中心归集财务域
 * fin_abnormal_bill 列表时使用，区别于 {@link FinanceAbnormalClient}（审批回写、失败抛错）。
 *
 * <p>软降级口径（与 {@link ApptRefNameResolver} 富化一致）：任何 4xx/5xx/网络/反序列化异常
 * 一律 {@code log.warn} 后返回空集合，<b>绝不外抛</b>——异常中心是只读聚合页，财务域暂不可用
 * 不应拖垮 BOM/核销两源的展示。时间字段按 ISO-8601 字符串手动解析，不依赖消息转换器对 JSR310 的注册。
 */
@Component
public class FinanceAbnormalBillClient {

    private static final Logger log = LoggerFactory.getLogger(FinanceAbnormalBillClient.class);

    private final RestTemplate restTemplate;

    @Value("${finance.service.url:http://127.0.0.1:8087}")
    private String financeBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public FinanceAbnormalBillClient(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * 拉取异常账单（系统身份，X-Internal-Token）。任何异常均软降级为空列表。
     *
     * @param status    财务原状态过滤（PENDING_APPROVAL/APPROVED/REJECTED/DISPOSED），可空
     * @param type      SHORT/LONG/WRONG，可空
     * @param storeCode 门店码，可空（可空时由 finance 系统身份返回全量，txn 侧逐行 DataScope 收敛）
     */
    @SuppressWarnings("unchecked")
    public List<AbnormalBill> listBills(String status, String type, String storeCode) {
        try {
            UriComponentsBuilder builder =
                    UriComponentsBuilder.fromHttpUrl(financeBaseUrl + "/api/finance/internal/abnormal/bills");
            if (status != null && !status.isBlank()) builder.queryParam("status", status.trim());
            if (type != null && !type.isBlank()) builder.queryParam("type", type.trim());
            if (storeCode != null && !storeCode.isBlank()) builder.queryParam("storeCode", storeCode.trim());
            HttpHeaders headers = new HttpHeaders();
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            ResponseEntity<List<Map<String, Object>>> resp = restTemplate.exchange(
                    builder.toUriString(), HttpMethod.GET, new HttpEntity<>(headers),
                    (Class<List<Map<String, Object>>>) (Class<?>) List.class);
            List<Map<String, Object>> body = resp.getBody();
            if (body == null) return Collections.emptyList();
            return body.stream().map(FinanceAbnormalBillClient::toBill).toList();
        } catch (Exception e) {
            log.warn("异常账单只读归集失败（降级为空，不影响 BOM/核销两源） status={} type={} store={}: {}",
                    status, type, storeCode, e.getMessage());
            return Collections.emptyList();
        }
    }

    private static AbnormalBill toBill(Map<String, Object> m) {
        return new AbnormalBill(
                str(m.get("billNo")), str(m.get("storeCode")), str(m.get("type")),
                str(m.get("direction")), m.get("amountFen") instanceof Number n ? n.longValue() : 0L,
                str(m.get("source")), str(m.get("reason")), str(m.get("status")),
                str(m.get("approvalNo")), str(m.get("createdBy")), str(m.get("reviewer")),
                time(m.get("createdAt")), time(m.get("approvedAt")), time(m.get("disposedAt")));
    }

    private static OffsetDateTime time(Object o) {
        if (o == null) return null;
        String s = String.valueOf(o);
        if (s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    /** finance 异常账单归集 DTO（与 InternalAbnormalBillView 契约对齐）。 */
    public record AbnormalBill(String billNo, String storeCode, String type, String direction,
                               long amountFen, String source, String reason, String status,
                               String approvalNo, String createdBy, String reviewer,
                               OffsetDateTime createdAt, OffsetDateTime approvedAt,
                               OffsetDateTime disposedAt) {
    }
}
