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
 * 企业微信渠道适配器（棒⑧卡1 双模）：
 * ① 直连企微官方——配置窗口 NOTIFY_WECHAT_DIRECT 启用（或 env 兜底开启）时，
 *    GET gettoken → POST message/send 应用消息直发；收件人 userid 来自 org 员工联系方式快照
 *    （staff.wecom_userid）。access_token 按 expires_in 内存缓存，失效码（40014/42001）清缓存重试一次。
 * ② webhook 中继——直连未开启时维持原逻辑，POST 到可配置 dev 网关。
 *
 * <p>诚实降级铁律：直连开启但 corpId/agentId/corpsecret/员工 userid 任一缺失 → SKIPPED 并说明
 * 缺什么，绝不静默回落 webhook 中继，绝不伪造发送成功；用户不在可见范围等确定性拒绝 → DEAD。
 */
@Component
public class WechatChannelAdapter implements NotificationChannelAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String QYAPI = "https://qyapi.weixin.qq.com";

    /** env/@Value 兜底值；配置窗口（org external_integration）启用值优先，无需重启。 */
    @Value("${meiyun.notify.gateway.wechat-url:}")
    private String wechatUrl;
    @Value("${meiyun.notify.direct.wechat-enabled:false}")
    private boolean wechatDirectEnv;
    @Value("${meiyun.notify.direct.wechat-config:}")
    private String wechatDirectConfigEnv;
    @Value("${meiyun.notify.direct.wechat-secret:}")
    private String wechatDirectSecretEnv;

    private final RestTemplate restTemplate;
    private final IntegrationConfigClient integrationConfig;
    private final StaffContactClient staffContacts;

    /** access_token 缓存（7200s 有效期，提前 120s 判定失效）。 */
    private volatile String cachedToken;
    private volatile long tokenExpiresAt;

    public WechatChannelAdapter(RestTemplate restTemplate, IntegrationConfigClient integrationConfig,
                                StaffContactClient staffContacts) {
        this.restTemplate = restTemplate;
        this.integrationConfig = integrationConfig;
        this.staffContacts = staffContacts;
    }

    @Override
    public boolean supports(String channel) {
        return "WECHAT".equals(channel);
    }

    @Override
    public DeliveryResult send(Notification notification, String channel, NotifyPreference preference) {
        if (integrationConfig.resolveSwitch("NOTIFY_WECHAT_DIRECT", wechatDirectEnv)) {
            return sendDirect(notification, preference);
        }
        return sendViaWebhook(notification, preference);
    }

    /** 直连企微官方 API：参数/密钥/userid 任一缺失 → SKIPPED 诚实降级，不回落 webhook。 */
    private DeliveryResult sendDirect(Notification notification, NotifyPreference preference) {
        String staffId = preference == null ? null : preference.getStaffId();
        if (staffId == null || staffId.isBlank()) {
            return DeliveryResult.skipped("企微直连：缺收件人工号，不伪造收件地址");
        }
        StaffContactClient.Contact contact = staffContacts.contactOf(staffId);
        if (contact == null || contact.wecomUserid() == null || contact.wecomUserid().isBlank()) {
            return DeliveryResult.skipped("企微直连：员工 " + staffId + " 未登记企微 userid，SKIPPED 诚实降级不回落 webhook 中继");
        }
        String cfg = integrationConfig.resolveConfigJson("NOTIFY_WECHAT_DIRECT");
        if (cfg == null || cfg.isBlank()) {
            cfg = wechatDirectConfigEnv;
        }
        if (cfg == null || cfg.isBlank()) {
            return DeliveryResult.skipped("企微直连：NOTIFY_WECHAT_DIRECT 扩展参数未配置（需 corpId/agentId），SKIPPED 不回落中继");
        }
        final JsonNode node;
        try {
            node = JSON.readTree(cfg);
        } catch (Exception e) {
            return DeliveryResult.skipped("企微直连：NOTIFY_WECHAT_DIRECT 扩展参数不是合法 JSON，SKIPPED 不回落中继");
        }
        String corpId = text(node, "corpId");
        long agentId = node.path("agentId").asLong(0L);
        if (corpId == null || agentId <= 0) {
            return DeliveryResult.skipped("企微直连：扩展参数缺 corpId/agentId，SKIPPED 不回落中继");
        }
        String secret = integrationConfig.resolveSecret("NOTIFY_WECHAT_SECRET", wechatDirectSecretEnv);
        if (secret == null || secret.isBlank()) {
            return DeliveryResult.skipped("企微直连：corpsecret NOTIFY_WECHAT_SECRET 未配置，SKIPPED 不回落中继");
        }
        String token = resolveToken(corpId, secret);
        if (token == null) {
            return DeliveryResult.failed("企微直连：gettoken 失败（corpId/corpsecret 校验未过或网络异常）");
        }
        DeliveryResult r = postMessage(token, agentId, contact.wecomUserid(), notification);
        if (r != null) {
            return r;
        }
        // 首次命中 token 失效码：清缓存重取一次再发
        String fresh = resolveToken(corpId, secret);
        if (fresh == null) {
            return DeliveryResult.failed("企微直连：token 失效后重取失败");
        }
        DeliveryResult retry = postMessage(fresh, agentId, contact.wecomUserid(), notification);
        return retry == null
                ? DeliveryResult.failed("企微直连：token 刷新后重发仍命中失效码")
                : retry;
    }

    /**
     * 发送应用消息；返回 null 表示命中 token 失效码（40014/42001），交由调用方刷新重试。
     */
    private DeliveryResult postMessage(String token, long agentId, String touser, Notification notification) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("touser", touser);
            body.put("msgtype", "text");
            body.put("agentid", agentId);
            body.put("text", Map.of("content",
                    notification.getTitle() + "\n" + notification.getContent()));
            String resp = restTemplate.postForObject(
                    QYAPI + "/cgi-bin/message/send?access_token=" + token, body, String.class);
            if (resp == null || resp.isBlank()) {
                return DeliveryResult.failed("企微直连：message/send 返回空响应");
            }
            JsonNode node = JSON.readTree(resp);
            int errcode = node.path("errcode").asInt(-1);
            if (errcode == 0) {
                return DeliveryResult.sent();
            }
            if (errcode == 40014 || errcode == 42001) {
                cachedToken = null;
                tokenExpiresAt = 0L;
                return null;
            }
            String errmsg = node.path("errmsg").asText(String.valueOf(errcode));
            if (errcode == 60011 || errcode == 40056 || errcode == 40003) {
                return DeliveryResult.dead("企微直连确定性拒绝(" + errcode + "): " + errmsg);
            }
            return DeliveryResult.failed("企微直连发送失败(" + errcode + "): " + errmsg);
        } catch (Exception e) {
            return DeliveryResult.failed("企微直连调用异常: " + e.getMessage());
        }
    }

    /** access_token：缓存有效直接返回；否则 GET gettoken，失败返回 null（软降级，不抛主链路）。 */
    private String resolveToken(String corpId, String secret) {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiresAt) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && now < tokenExpiresAt) {
                return cachedToken;
            }
            try {
                String resp = restTemplate.getForObject(
                        QYAPI + "/cgi-bin/gettoken?corpid=" + corpId + "&corpsecret=" + secret,
                        String.class);
                if (resp == null || resp.isBlank()) {
                    return null;
                }
                JsonNode node = JSON.readTree(resp);
                if (node.path("errcode").asInt(0) != 0) {
                    return null;
                }
                String token = node.path("access_token").asText(null);
                if (token == null || token.isBlank()) {
                    return null;
                }
                long expiresIn = node.path("expires_in").asLong(7200L);
                cachedToken = token;
                tokenExpiresAt = now + Math.max(expiresIn - 120, 60) * 1000L;
                return cachedToken;
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** webhook 中继（原 dev 网关链路，直连未开启时不变）。 */
    private DeliveryResult sendViaWebhook(Notification notification, NotifyPreference preference) {
        String url = integrationConfig.resolveUrl("NOTIFY_WECHAT_GATEWAY", wechatUrl);
        if (url == null || url.isBlank()) {
            return DeliveryResult.skipped("dev 网关未配置 meiyun.notify.gateway.wechat-url 且配置窗口未启用企微网关，不发送真实企微消息");
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
            restTemplate.postForEntity(url, body, String.class);
            return DeliveryResult.sent();
        } catch (HttpClientErrorException e) {
            return DeliveryResult.dead("企微网关确定性拒绝(" + e.getStatusCode().value() + ")");
        } catch (Exception e) {
            return DeliveryResult.failed(e.getMessage());
        }
    }

    private static String text(JsonNode node, String field) {
        String v = node.path(field).asText(null);
        return (v == null || v.isBlank()) ? null : v.trim();
    }
}
