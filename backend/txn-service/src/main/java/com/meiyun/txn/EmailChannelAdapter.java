package com.meiyun.txn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * 邮件渠道适配器（棒⑧卡1 双模）：
 * ① 直连官方 SMTP——配置窗口 NOTIFY_EMAIL_DIRECT 启用（或 env 兜底开启）时，
 *    以运行期参数动态构建 JavaMailSender 直发；收件人邮箱来自 org 员工联系方式快照（staff.email）。
 * ② webhook 中继——直连未开启时维持原逻辑，POST 到可配置 dev 网关。
 *
 * <p>诚实降级铁律：直连开启但 SMTP 参数/密码/员工邮箱任一缺失 → SKIPPED 并说明缺什么，
 * 绝不静默回落 webhook 中继，绝不伪造发送成功；认证失败等确定性拒绝 → DEAD。
 */
@Component
public class EmailChannelAdapter implements NotificationChannelAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** env/@Value 兜底值；配置窗口（org external_integration）启用值优先，无需重启。 */
    @Value("${meiyun.notify.gateway.email-url:}")
    private String emailUrl;
    @Value("${meiyun.notify.direct.email-enabled:false}")
    private boolean emailDirectEnv;
    @Value("${meiyun.notify.direct.email-config:}")
    private String emailDirectConfigEnv;
    @Value("${meiyun.notify.direct.email-secret:}")
    private String emailDirectSecretEnv;

    private final RestTemplate restTemplate;
    private final IntegrationConfigClient integrationConfig;
    private final StaffContactClient staffContacts;

    public EmailChannelAdapter(RestTemplate restTemplate, IntegrationConfigClient integrationConfig,
                               StaffContactClient staffContacts) {
        this.restTemplate = restTemplate;
        this.integrationConfig = integrationConfig;
        this.staffContacts = staffContacts;
    }

    @Override
    public boolean supports(String channel) {
        return "EMAIL".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        if (integrationConfig.resolveSwitch("NOTIFY_EMAIL_DIRECT", emailDirectEnv)) {
            return sendDirect(notification, preference);
        }
        return sendViaWebhook(notification, preference);
    }

    /** 直连 SMTP：参数/密码/邮箱任一缺失 → SKIPPED 诚实降级，不回落 webhook。 */
    private DeliveryResult sendDirect(Notification notification, NotifyPreference preference) {
        String staffId = preference == null ? null : preference.getStaffId();
        if (staffId == null || staffId.isBlank()) {
            return DeliveryResult.skipped("邮件直连：缺收件人工号，不伪造收件地址");
        }
        StaffContactClient.Contact contact = staffContacts.contactOf(staffId);
        if (contact == null || contact.email() == null || contact.email().isBlank()) {
            return DeliveryResult.skipped("邮件直连：员工 " + staffId + " 未登记邮箱，SKIPPED 诚实降级不回落 webhook 中继");
        }
        String cfg = integrationConfig.resolveConfigJson("NOTIFY_EMAIL_DIRECT");
        if (cfg == null || cfg.isBlank()) {
            cfg = emailDirectConfigEnv;
        }
        if (cfg == null || cfg.isBlank()) {
            return DeliveryResult.skipped("邮件直连：NOTIFY_EMAIL_DIRECT 扩展参数未配置（需 host/username/from），SKIPPED 不回落中继");
        }
        final JsonNode node;
        try {
            node = JSON.readTree(cfg);
        } catch (Exception e) {
            return DeliveryResult.skipped("邮件直连：NOTIFY_EMAIL_DIRECT 扩展参数不是合法 JSON，SKIPPED 不回落中继");
        }
        String host = text(node, "host");
        String username = text(node, "username");
        String from = text(node, "from");
        if (host == null || username == null || from == null) {
            return DeliveryResult.skipped("邮件直连：扩展参数缺 host/username/from，SKIPPED 不回落中继");
        }
        String secret = integrationConfig.resolveSecret("NOTIFY_EMAIL_SECRET", emailDirectSecretEnv);
        if (secret == null || secret.isBlank()) {
            return DeliveryResult.skipped("邮件直连：SMTP 密码 NOTIFY_EMAIL_SECRET 未配置，SKIPPED 不回落中继");
        }
        int port = node.path("port").asInt(465);
        boolean ssl = node.path("ssl").asBoolean(true);
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            sender.setUsername(username);
            sender.setPassword(secret);
            Properties props = sender.getJavaMailProperties();
            props.put("mail.transport.protocol", "smtp");
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.connectiontimeout", "10000");
            props.put("mail.smtp.timeout", "10000");
            props.put("mail.smtp.writetimeout", "10000");
            if (ssl) {
                props.put("mail.smtp.ssl.enable", "true");
            } else {
                props.put("mail.smtp.starttls.enable", "true");
            }
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(contact.email());
            helper.setSubject(notification.getTitle());
            helper.setText(notification.getContent(), false);
            sender.send(message);
            return DeliveryResult.sent();
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("535") || msg.contains("Authentication") || msg.contains("auth")) {
                return DeliveryResult.dead("邮件直连：SMTP 认证确定性拒绝 " + msg);
            }
            return DeliveryResult.failed("邮件直连：SMTP 发送异常 " + msg);
        }
    }

    /** webhook 中继（原 dev 网关链路，直连未开启时不变）。 */
    private DeliveryResult sendViaWebhook(Notification notification, NotifyPreference preference) {
        String url = integrationConfig.resolveUrl("NOTIFY_EMAIL_GATEWAY", emailUrl);
        if (url == null || url.isBlank()) {
            return DeliveryResult.skipped("dev 网关未配置 meiyun.notify.gateway.email-url 且配置窗口未启用邮件网关，不发送真实邮件");
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
            restTemplate.postForEntity(url, body, String.class);
            return DeliveryResult.sent();
        } catch (HttpClientErrorException e) {
            return DeliveryResult.dead("邮件网关确定性拒绝(" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
