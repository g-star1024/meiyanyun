package com.meiyun.txn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 企业微信/公众号渠道适配器：POST 到可配置 dev 网关 webhook（meiyun.notify.gateway.wechat-url）。
 * 未配置网关 → SKIPPED。真实企微应用消息接入仅替换 webhook 指向，不引凭证。
 *
 * <p>收件人：org 员工目录当前只有工号，工号不保证是企微 userid，故不填伪收件地址，
 * webhook 体携带 recipientType=STAFF_ID + staffId 由 dev 网关映射企微账号；
 * 网关 4xx（确定性拒绝，如用户不在可见范围）→ DEAD。
 */
@Component
public class WechatChannelAdapter implements NotificationChannelAdapter {

    @Value("${meiyun.notify.gateway.wechat-url:}")
    private String wechatUrl;

    private final RestTemplate restTemplate;

    public WechatChannelAdapter(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    @Override
    public boolean supports(String channel) {
        return "WECHAT".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        if (wechatUrl == null || wechatUrl.isBlank()) {
            return DeliveryResult.skipped("dev 网关未配置 meiyun.notify.gateway.wechat-url，不发送真实企微消息");
        }
        String staffId = preference == null ? null : preference.getStaffId();
        if (staffId == null || staffId.isBlank()) {
            return DeliveryResult.skipped("缺收件人工号，且 org 目录暂无企微账号映射，不伪造收件地址");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("recipientType", "STAFF_ID");
            body.put("staffId", staffId);
            body.put("title", notification.getTitle());
            body.put("content", notification.getContent());
            restTemplate.postForEntity(wechatUrl, body, String.class);
            return DeliveryResult.sent();
        } catch (HttpClientErrorException e) {
            return DeliveryResult.dead("企微网关确定性拒绝(" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }
}
