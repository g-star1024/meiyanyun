package com.meiyun.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 推送外发腿（棒⑧卡2）：PushService 主事务提交后消费 Spring 应用事件，做真实通道外发并回写
 * push_record 外发四列（status/channel_msg_id/error/delivered_at，V88）。
 *
 * <p>架构口径：
 * <ul>
 *   <li>外部 HTTP 不进推送主事务——AFTER_COMMIT 监听，主链路（落库/触点/审计）已提交，
 *       外发失败仅回写状态列，绝不影响主流程；</li>
 *   <li>dedup 60s 幂等早返回路径不发布事件，杜绝重复外发；</li>
 *   <li>双调用方（MarketingController 手动推送 / CareService 关怀触发）经 PushService.send
 *       同一出口，自动同享外发腿。</li>
 * </ul>
 *
 * <p>诚实降级铁律（同卡1「不伪造发送成功」）：
 * <ul>
 *   <li>SMS：直连开关未启用 / 手机号缺失 / 配置或密钥缺项 → SKIPPED 并写明原因；
 *       齐全则走阿里云 dysmsapi 真实外发，按回执写 SENT/FAILED/DEAD；</li>
 *   <li>WECOM/WECHAT_MP：客户侧企微需 external_userid 通路（customer 表无此列）、
 *       公众号需模板消息配置目录行——本卡保持 SKIPPED 仅落库，通路预留。</li>
 * </ul>
 */
@Service
public class PushDispatchService {

    private static final Logger log = LoggerFactory.getLogger(PushDispatchService.class);

    /** 短信直连开关/配置行（org integration_config，V36 已建，客户侧直接复用）。 */
    public static final String CFG_SMS_DIRECT = "NOTIFY_SMS_DIRECT";
    /** 短信直连密钥行（accessKeySecret）。 */
    public static final String CFG_SMS_SECRET = "NOTIFY_SMS_SECRET";

    private final PushRecordRepository pushRepo;
    private final IntegrationConfigClient configClient;
    private final CustomerContactClient contactClient;
    private final AliyunSmsSender smsSender;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${meiyun.notify.direct.sms-enabled:false}")
    private boolean smsDirectEnv;
    @Value("${meiyun.notify.direct.sms-config:}")
    private String smsConfigEnv;
    @Value("${meiyun.notify.direct.sms-secret:}")
    private String smsSecretEnv;

    public PushDispatchService(PushRecordRepository pushRepo,
                               IntegrationConfigClient configClient,
                               CustomerContactClient contactClient,
                               AliyunSmsSender smsSender) {
        this.pushRepo = pushRepo;
        this.configClient = configClient;
        this.contactClient = contactClient;
        this.smsSender = smsSender;
    }

    /** 推送落库事件（PushService.send 主事务内发布，AFTER_COMMIT 后由本类消费）。 */
    public record PushRecordedEvent(Long pushId, String customerId, String pushType, String content) {
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPushRecorded(PushRecordedEvent e) {
        try {
            dispatch(e);
        } catch (Exception ex) {
            // 外发腿任何异常不得外溢（主事务已提交）；状态列留空仅记日志
            log.warn("推送外发链路异常 pushId={}: {}", e.pushId(), ex.getMessage());
        }
    }

    private void dispatch(PushRecordedEvent e) {
        PushRecord rec = pushRepo.findById(e.pushId()).orElse(null);
        if (rec == null) {
            log.warn("推送外发找不到落库记录 pushId={}", e.pushId());
            return;
        }
        if ("SMS".equals(e.pushType())) {
            dispatchSms(rec, e);
        } else {
            markSkipped(rec, "客户侧" + e.pushType() + "外发通路未建"
                    + "（企微需客户 external_userid、公众号需模板消息配置目录行），本卡仅落库不外发");
        }
    }

    private void dispatchSms(PushRecord rec, PushRecordedEvent e) {
        if (!configClient.resolveSwitch(CFG_SMS_DIRECT, smsDirectEnv)) {
            markSkipped(rec, "短信直连开关未启用（NOTIFY_SMS_DIRECT），仅落库");
            return;
        }
        String phone = contactClient.resolvePhone(e.customerId());
        if (phone == null || phone.isBlank()) {
            markSkipped(rec, "客户手机号缺失或客户域不可用，无法短信外发");
            return;
        }
        String configJson = configClient.resolveConfigJson(CFG_SMS_DIRECT, smsConfigEnv);
        if (configJson == null || configJson.isBlank()) {
            markSkipped(rec, "短信直连配置缺失（NOTIFY_SMS_DIRECT config_json）");
            return;
        }
        SmsConfig cfg = parseConfig(configJson);
        if (cfg == null) {
            markSkipped(rec, "短信直连配置非法 JSON");
            return;
        }
        if (isBlank(cfg.accessKeyId()) || isBlank(cfg.signName()) || isBlank(cfg.templateCode())) {
            markSkipped(rec, "短信直连配置缺 accessKeyId/signName/templateCode");
            return;
        }
        String secret = configClient.resolveSecret(CFG_SMS_SECRET, smsSecretEnv);
        if (secret == null || secret.isBlank()) {
            markSkipped(rec, "短信直连密钥缺失（NOTIFY_SMS_SECRET）");
            return;
        }
        String templateParam;
        try {
            templateParam = objectMapper.writeValueAsString(Map.of(cfg.paramKey(), e.content()));
        } catch (Exception ex) {
            markSkipped(rec, "短信模板参数序列化失败");
            return;
        }
        AliyunSmsSender.SmsSendResult r = smsSender.send(cfg.endpoint(), cfg.accessKeyId(), secret,
                phone, cfg.signName(), cfg.templateCode(), templateParam);
        rec.setStatus(r.status());
        rec.setChannelMsgId(r.bizId());
        rec.setError(r.error());
        if ("SENT".equals(r.status())) {
            rec.setDeliveredAt(OffsetDateTime.now());
        }
        pushRepo.save(rec);
        log.info("短信外发结果 pushId={} status={} bizId={}", rec.getPushId(), r.status(), r.bizId());
    }

    private void markSkipped(PushRecord rec, String reason) {
        rec.setStatus("SKIPPED");
        rec.setError(reason);
        pushRepo.save(rec);
        log.info("推送外发跳过 pushId={}: {}", rec.getPushId(), reason);
    }

    /** 解析 NOTIFY_SMS_DIRECT config_json；paramKey 缺省 content，endpoint 缺省 dysmsapi.aliyuncs.com。 */
    private SmsConfig parseConfig(String json) {
        try {
            JsonNode n = objectMapper.readTree(json);
            String paramKey = n.path("paramKey").asText("content");
            String endpoint = n.path("endpoint").asText("dysmsapi.aliyuncs.com");
            return new SmsConfig(
                    n.path("accessKeyId").asText(null),
                    n.path("signName").asText(null),
                    n.path("templateCode").asText(null),
                    paramKey == null || paramKey.isBlank() ? "content" : paramKey,
                    endpoint == null || endpoint.isBlank() ? "dysmsapi.aliyuncs.com" : endpoint);
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record SmsConfig(String accessKeyId, String signName, String templateCode,
                             String paramKey, String endpoint) {
    }
}
