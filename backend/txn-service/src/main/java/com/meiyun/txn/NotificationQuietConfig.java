package com.meiyun.txn;

import com.meiyun.txn.IntegrationConfigClient.QuietWindow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 通知免打扰时段（域⑦）——全局口径。
 *
 * <p>双层口径（P5-B60 卡4 / L39 收口）：
 * <ul>
 *   <li>管理端动态值：org 配置窗固定目录 NOTIFY_GLOBAL_QUIET（value_kind=QUIET_WINDOW），
 *       经 {@link IntegrationConfigClient} 60s 快照下发，行 enabled 即开关、config_json 携窗口；</li>
 *   <li>运维默认兜底：yml meiyun.notify.quiet.*（默认关闭 22:00-08:00），
 *       仅当快照行禁用/不存在/拉取失败超宽限时生效。</li>
 * </ul>
 *
 * <p>B60 另增员工个人级免打扰（notify_preference.quiet_* 三字段），窗口算法与本类共用
 * {@link #inWindow(LocalTime, String, String)}，FanoutJob 对全局/个人取「或」判定。
 *
 * <p>语义：免打扰时段内，非紧急（level≠URGENT）通知的短信/企微/邮件渠道延后（DEFERRED），
 * 站内信(INBOX)始终送达；URGENT（如审批超时催办）不受免打扰影响。
 */
@Component
public class NotificationQuietConfig {

    private static final Logger log = LoggerFactory.getLogger(NotificationQuietConfig.class);

    /** org 配置窗固定目录编码（与 IntegrationCatalog.NOTIFY_GLOBAL_QUIET 同名）。 */
    public static final String QUIET_CODE = "NOTIFY_GLOBAL_QUIET";

    @Value("${meiyun.notify.quiet.enabled:false}")
    private boolean enabled;

    @Value("${meiyun.notify.quiet.start:22:00}")
    private String start;

    @Value("${meiyun.notify.quiet.end:08:00}")
    private String end;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 业务时区：容器 JVM 默认 UTC，免打扰窗口必须按门店所在时区（北京时间）判定。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    private final IntegrationConfigClient configClient;

    public NotificationQuietConfig(IntegrationConfigClient configClient) {
        this.configClient = configClient;
    }

    /** 运维默认兜底开关（yml）；管理端动态启用状态以配置窗快照为准。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 当前是否处于全局免打扰时段（URGENT 级别不计入，始终返回 false）。 */
    public boolean inQuietWindow(String level) {
        if ("URGENT".equals(level)) {
            return false;
        }
        try {
            var dynamic = configClient.resolveQuietWindow(QUIET_CODE);
            if (dynamic.isPresent()) {
                QuietWindow w = dynamic.get();
                return inWindow(LocalTime.now(BIZ_ZONE), w.start(), w.end());
            }
        } catch (Exception ex) {
            log.warn("管理端免打扰快照取用异常，本次回退 yml 兜底：{}", ex.getMessage());
        }
        if (!enabled) {
            return false;
        }
        return inWindow(LocalTime.now(BIZ_ZONE), start, end);
    }

    /** 业务时区当前时刻（供个人级窗口同口径判定）。 */
    public static LocalTime bizNow() {
        return LocalTime.now(BIZ_ZONE);
    }

    /**
     * 跨午夜窗口判定（全局/个人共用）：start/end 为 HH:mm；start&gt;end 视为跨午夜
     * （如 22:00–08:00），start=end 视为空窗口不静默；格式非法返回 false（配置容错不阻断投递）。
     */
    public static boolean inWindow(LocalTime nowT, String start, String end) {
        if (start == null || end == null || start.isBlank() || end.isBlank()) {
            return false;
        }
        try {
            LocalTime startT = LocalTime.parse(start, HHMM);
            LocalTime endT = LocalTime.parse(end, HHMM);
            int now = nowT.getHour() * 60 + nowT.getMinute();
            int s = startT.getHour() * 60 + startT.getMinute();
            int e = endT.getHour() * 60 + endT.getMinute();
            if (s == e) {
                return false;
            }
            if (s < e) {
                return now >= s && now <= e;
            }
            // 跨午夜窗口（如 22:00–08:00）
            return now >= s || now <= e;
        } catch (Exception ex) {
            return false;
        }
    }
}
