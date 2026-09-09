package com.meiyun.txn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 邮件渠道适配器：POST 到可配置 dev 网关 webhook（meiyun.notify.gateway.email-url）。
 * 未配置网关 → SKIPPED。真实邮件服务接入仅替换 webhook 指向，不引凭证。
 *
 * <p>收件人：org 员工目录当前没有邮箱字段，工号绝非邮箱地址，绝不把工号填进收件人；
 * webhook 体携带 recipientType=STAFF_ID + staffId 由 dev 网关映射邮箱；
 * 网关 4xx（确定性拒绝，如地址格式非法）→ DEAD。
 */
@Component
public class EmailChannelAdapter implements NotificationChannelAdapter {

    @Value("${meiyun.notify.gateway.email-url:}")
    private String emailUrl;

    private final RestTemplate restTemplate;

    public EmailChannelAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean supports(String channel) {
        return "EMAIL".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        if (emailUrl == null || emailUrl.isBlank()) {
            return DeliveryResult.skipped("dev 网关未配置 meiyun.notify.gateway.email-url，不发送真实邮件");
        }
        String staffId = preference == null ? null : preference.getStaffId();
        if (staffId == null || staffId.isBlank()) {
            return DeliveryResult.skipped("缺收件人工号，且 org 目录暂无邮箱映射，不伪造收件地址");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipientType", "STAFF_ID");
            body.put("staffId", staffId);
            body.put("title", notification.getTitle());
            body.put("content", notification.getContent());
            restTemplate.postForEntity(emailUrl, body, String.class);
            return DeliveryResult.sent();
        } catch (HttpClientErrorException e) {
            return DeliveryResult.dead("邮件网关确定性拒绝(" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }
}
