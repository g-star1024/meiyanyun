package com.meiyun.txn;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * 通知免打扰时段（域⑦）。注：NotifyPreference 实体当前无 quiet 字段，
 * 故免打扰采用 txn 全局配置（meiyun.notify.quiet.*）实现，避免改动既有 notify_preference 表。
 *
 * <p>语义：免打扰时段内，非紧急（level≠URGENT）通知的短信/企微/邮件渠道延后（DEFERRED），
 * 站内信(INBOX)始终送达；URGENT（如审批超时催办）不受免打扰影响。
 */
@Component
public class NotificationQuietConfig {

    @Value("${meiyun.notify.quiet.enabled:false}")
    private boolean enabled;

    @Value("${meiyun.notify.quiet.start:22:00}")
    private String start;

    @Value("${meiyun.notify.quiet.end:08:00}")
    private String end;

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** 业务时区：容器 JVM 默认 UTC，免打扰窗口必须按门店所在时区（北京时间）判定。 */
    private static final ZoneId BIZ_ZONE = ZoneId.of("Asia/Shanghai");

    public boolean isEnabled() {
        return enabled;
    }

    /** 当前是否处于免打扰时段（URGENT 级别不计入，始终返回 false）。 */
    public boolean inQuietWindow(String level) {
        if (!enabled || "URGENT".equals(level)) {
            return false;
        }
        try {
            LocalTime nowT = LocalTime.now(BIZ_ZONE);
            LocalTime startT = LocalTime.parse(start, HHMM);
            LocalTime endT = LocalTime.parse(end, HHMM);
            int now = nowT.getHour() * 60 + nowT.getMinute();
            int s = startT.getHour() * 60 + startT.getMinute();
            int e = endT.getHour() * 60 + endT.getMinute();
            if (s <= e) {
                return now >= s && now <= e;
            }
            // 跨午夜窗口（如 22:00–08:00）
            return now >= s || now <= e;
        } catch (Exception ex) {
            return false;
        }
    }
}
