package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 短信渠道适配器（棒⑧卡1 双模）：
 * ① 直连官方 API——配置窗口 NOTIFY_SMS_DIRECT 启用（或 env 兜底开启）时，经阿里云 dysmsapi
 *    手写 RPC 签名直发；收件人手机号来自 org 员工联系方式快照（staff.phone）。
 * ② webhook 中继——直连未开启时维持原逻辑，POST 到可配置 dev 网关。
 *
 * <p>诚实降级铁律：直连开启但厂商参数/密钥/员工手机号任一缺失 → SKIPPED 并说明缺什么，
 * 绝不静默回落 webhook 中继（杜绝半直连），绝不伪造发送成功；网关/厂商确定性拒绝（4xx/isv.*）→ DEAD。
 */
@Component
public class SmsChannelAdapter implements NotificationChannelAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** env/@Value 兜底值；配置窗口（org external_integration）启用值优先，无需重启。 */
    @Value("${meiyun.notify.gateway.sms-url:}")
    private String smsUrl;
    @Value("${meiyun.notify.direct.sms-enabled:false}")
    private boolean smsDirectEnv;
    @Value("${meiyun.notify.direct.sms-config:}")
    private String smsDirectConfigEnv;
    @Value("${meiyun.notify.direct.sms-secret:}")
    private String smsDirectSecretEnv;

    private final RestTemplate restTemplate;
    private final IntegrationConfigClient integrationConfig;
    private final StaffContactClient staffContacts;
    private final AliyunSmsSender aliyunSms;

    public SmsChannelAdapter(RestTemplate restTemplate, IntegrationConfigClient integrationConfig,
                             StaffContactClient staffContacts, AliyunSmsSender aliyunSms) {
        this.restTemplate = restTemplate;
        this.integrationConfig = integrationConfig;
        this.staffContacts = staffContacts;
        this.aliyunSms = aliyunSms;
    }

    @Override
    public boolean supports(String channel) {
        return "SMS".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        if (integrationConfig.resolveSwitch("NOTIFY_SMS_DIRECT", smsDirectEnv)) {
            return sendDirect(notification, preference);
        }
        return sendViaWebhook(notification, preference);
    }

    /** 直连阿里云 dysmsapi：参数/密钥/手机号任一缺失 → SKIPPED 诚实降级，不回落 webhook。 */
    private DeliveryResult sendDirect(Notification notification, NotifyPreference preference) {
        String staffId = preference == null ? null : preference.getStaffId();
        if (staffId == null || staffId.isBlank()) {
            return DeliveryResult.skipped("短信直连：缺收件人工号，不伪造收件地址");
        }
        StaffContactClient.Contact contact = staffContacts.contactOf(staffId);
        if (contact == null || contact.phone() == null || contact.phone().isBlank()) {
            return DeliveryResult.skipped("短信直连：员工 " + staffId + " 未登记手机号，SKIPPED 诚实降级不回落 webhook 中继");
        }
        String cfg = integrationConfig.resolveConfigJson("NOTIFY_SMS_DIRECT");
        if (cfg == null || cfg.isBlank()) {
            cfg = smsDirectConfigEnv;
        }
        if (cfg == null || cfg.isBlank()) {
            return DeliveryResult.skipped("短信直连：NOTIFY_SMS_DIRECT 扩展参数未配置（需 accessKeyId/signName/templateCode），SKIPPED 不回落中继");
        }
        final JsonNode node;
        try {
            node = JSON.readTree(cfg);
        } catch (Exception e) {
            return DeliveryResult.skipped("短信直连：NOTIFY_SMS_DIRECT 扩展参数不是合法 JSON，SKIPPED 不回落中继");
        }
        String accessKeyId = text(node, "accessKeyId");
        String signName = text(node, "signName");
        String templateCode = text(node, "templateCode");
        if (accessKeyId == null || signName == null || templateCode == null) {
            return DeliveryResult.skipped("短信直连：扩展参数缺 accessKeyId/signName/templateCode，SKIPPED 不回落中继");
        }
        String secret = integrationConfig.resolveSecret("NOTIFY_SMS_SECRET", smsDirectSecretEnv);
        if (secret == null || secret.isBlank()) {
            return DeliveryResult.skipped("短信直连：密钥 NOTIFY_SMS_SECRET 未配置，SKIPPED 不回落中继");
        }
        String endpoint = text(node, "endpoint");
        String paramKey = text(node, "paramKey");
        if (paramKey == null) {
            paramKey = "content";
        }
        final String templateParam;
        try {
            templateParam = JSON.writeValueAsString(Map.of(paramKey,
                    notification.getTitle() + " " + notification.getContent()));
        } catch (Exception e) {
            return DeliveryResult.failed("短信直连：模板参数序列化异常 " + e.getMessage());
        }
        AliyunSmsSender.SmsSendResult r = aliyunSms.send(endpoint, accessKeyId, secret,
                contact.phone(), signName, templateCode, templateParam);
        return switch (r.status()) {
            case "SENT" -> DeliveryResult.sent();
            case "DEAD" -> DeliveryResult.dead(r.error());
            default -> DeliveryResult.failed(r.error());
        };
    }

    /** webhook 中继（原 dev 网关链路，直连未开启时不变）。 */
    private DeliveryResult sendViaWebhook(Notification notification, NotifyPreference preference) {
        String url = integrationConfig.resolveUrl("NOTIFY_SMS_GATEWAY", smsUrl);
        if (url == null || url.isBlank()) {
            return DeliveryResult.skipped("dev 网关未配置 meiyun.notify.gateway.sms-url 且配置窗口未启用短信网关，不发送真实短信");
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
            restTemplate.postForEntity(url, body, String.class);
            return DeliveryResult.sent();
        } catch (HttpClientErrorException e) {
            return DeliveryResult.dead("短信网关确定性拒绝(" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
