package com.meiyun.customer;

import com.meiyun.security.AuthInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * 会员降级站内信告知（域①-B62 卡1，CARD-G）。
 *
 * <p>降级批处理后按客户逐条调用 txn 内部端点
 * {@code POST /api/txn/internal/level-downgrade-alert}，txn 按门店码收敛本店 STORE_MGR 落站内信
 * （category=SYSTEM、level=WARNING）。公海客户（storeCode 空）按定案「只审计不发信」，本处直接跳过。
 * RestTemplate + X-Internal-Token + try/catch 全软降级（仿 ComplianceInspectionJob）：
 * txn 不可用仅 warn，不影响降级主链路与审计，下轮/下次触发自愈。
 *
 * <p>幂等：bizRef={@code M+yyyyMM+"-"+customerId}，txn 侧幂等键 LEVEL:{bizRef}:{staffId}，
 * 同一客户同一降级月重复触发天然防重。手机后4位由主档读取（降级明细 item 不携带手机号，审计口径不需要）。
 */
@Component
public class LevelDowngradeNotifier {

    private static final Logger log = LoggerFactory.getLogger(LevelDowngradeNotifier.class);
    private static final String LINK = "/m3-customer";

    private final RestTemplate restTemplate;
    private final CustomerRepository customerRepo;

    @Value("${txn.service.url:http://localhost:8083}")
    private String txnBaseUrl;
    @Value("${meiyun.security.internal-token:meiyun-dev-internal-token-please-change-in-prod}")
    private String internalToken;

    public LevelDowngradeNotifier(RestTemplate restTemplate, CustomerRepository customerRepo) {
        this.restTemplate = restTemplate;
        this.customerRepo = customerRepo;
    }

    /**
     * 发送单客户降级预警。公海客户（storeCode 空）只跳过（调用方已落 LEVEL 审计）；
     * 任何异常软降级为 warn，不抛出。
     *
     * @param runMonth 跑批月 M（形如 2026-08）
     */
    public void notifyOne(CustomerService.DowngradeItem item, String runMonth) {
        if (item == null) return;
        if (item.storeCode() == null || item.storeCode().isBlank()) {
            log.info("公海客户降级只审计不发站内信：customerId={} {}→{}",
                    item.customerId(), item.fromLevel(), item.toLevel());
            return;
        }
        String phoneTail = customerRepo.findById(item.customerId())
                .map(Customer::getPhone)
                .filter(p -> p != null && p.length() >= 4)
                .map(p -> p.substring(p.length() - 4))
                .orElse("****");
        String monthKey = runMonth.replace("-", "");
        String bizRef = "M" + monthKey + "-" + item.customerId();
        String title = "会员等级预警：" + item.name() + "(手机后4" + phoneTail + ") 由"
                + item.fromLevel() + "降为" + item.toLevel();
        String content = "会员 " + item.name() + "（手机后4位 " + phoneTail + "）因连续3月消费未达"
                + item.fromLevel() + "保级线，已于 " + runMonth + " 自动降为" + item.toLevel()
                + "，请及时关注与回访。";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set(AuthInterceptor.INTERNAL_TOKEN_HEADER, internalToken);
            Map<String, Object> body = Map.of(
                    "storeCode", item.storeCode(),
                    "title", title,
                    "content", content,
                    "link", LINK,
                    "bizRef", bizRef);
            restTemplate.exchange(txnBaseUrl + "/api/txn/internal/level-downgrade-alert",
                    HttpMethod.POST, new HttpEntity<>(body, headers), Map.class);
        } catch (Exception ex) {
            log.warn("会员降级站内信联动失败（txn 不可用，软降级）：customerId={} {}",
                    item.customerId(), ex.getMessage());
        }
    }
}
