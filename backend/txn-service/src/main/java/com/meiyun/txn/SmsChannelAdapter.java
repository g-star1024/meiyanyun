package com.meiyun.txn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短信渠道适配器：把通知 POST 到可配置 dev 网关 webhook（meiyun.notify.gateway.sms-url）。
 * 未配置网关 → SKIPPED（诚实标注，不伪造发送成功）。真实运营商接入仅替换 webhook 指向，不引凭证。
 *
 * <p>收件地址：org 员工目录当前没有手机号字段，NotifyPreference 只持有工号——绝不把工号填进
 * 短信收件人字段伪造发送。webhook 体携带 recipientType=STAFF_ID + staffId 供 dev 网关自行做
 * 「工号→手机号」目录映射；网关 4xx（确定性拒绝，如号段非法）→ DEAD，不做无意义重试。
 */
@Component
public class SmsChannelAdapter implements NotificationChannelAdapter {

    @Value("${meiyun.notify.gateway.sms-url:}")
    private String smsUrl;

    private final RestTemplate restTemplate;

    public SmsChannelAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean supports(String channel) {
        return "SMS".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        if (smsUrl == null || smsUrl.isBlank()) {
            return DeliveryResult.skipped("dev 网关未配置 meiyun.notify.gateway.sms-url，不发送真实短信");
        }
        String staffId = preference == null ? null : preference.getStaffId();
        if (staffId == null || staffId.isBlank()) {
            return DeliveryResult.skipped("缺收件人工号，且 org 目录暂无手机号映射，不伪造收件地址");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipientType", "STAFF_ID");
            body.put("staffId", staffId);
            body.put("title", notification.getTitle());
            body.put("content", notification.getContent());
            restTemplate.postForEntity(smsUrl, body, String.class);
            return DeliveryResult.sent();
        } catch (HttpClientErrorException e) {
            return DeliveryResult.dead("短信网关确定性拒绝(" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }
}
