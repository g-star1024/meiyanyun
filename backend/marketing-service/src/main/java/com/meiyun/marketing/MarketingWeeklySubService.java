package com.meiyun.marketing;

import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 营销周报订阅读写（P5-B94 D7）。
 *
 * <p>幂等口径（对齐 MarketingCfgService「未变 changed=false 不审计」惯例）：
 * 无行即未订阅——POST enabled=false 且无行时状态本一致，不建行不审计；
 * 有行同值重复 POST 同样 changed=false 零审计（staff_no UK 天然防重）。
 * 每次实际翻转落审计 MARKETING_WEEKLY_SUB + SUBSCRIBE/UNSUBSCRIBE。
 */
@Service
public class MarketingWeeklySubService {

    private static final Logger log = LoggerFactory.getLogger(MarketingWeeklySubService.class);

    private final MarketingWeeklySubRepository subRepo;
    private final AuditRecorder audit;

    public MarketingWeeklySubService(MarketingWeeklySubRepository subRepo, AuditRecorder audit) {
        this.subRepo = subRepo;
        this.audit = audit;
    }

    /** 本人订阅态：{enabled, lastSentWeek}，无行回落 enabled=false。 */
    public Map<String, Object> view(String staffNo) {
        MarketingWeeklySub sub = subRepo.findByStaffNo(staffNo).orElse(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", sub != null && sub.isEnabled());
        m.put("lastSentWeek", sub == null ? null : sub.getLastSentWeek());
        return m;
    }

    /** 本人订阅翻转（upsert）：{enabled, changed, lastSentWeek}，实际翻转才审计。 */
    public Map<String, Object> set(String staffNo, boolean enabled) {
        MarketingWeeklySub sub = subRepo.findByStaffNo(staffNo).orElse(null);
        boolean changed;
        if (sub == null) {
            if (!enabled) {
                changed = false;
            } else {
                sub = new MarketingWeeklySub();
                sub.setStaffNo(staffNo);
                sub.setEnabled(true);
                subRepo.save(sub);
                changed = true;
            }
        } else if (sub.isEnabled() == enabled) {
            changed = false;
        } else {
            sub.setEnabled(enabled);
            subRepo.save(sub);
            changed = true;
        }
        if (changed) {
            audit.record("MARKETING_WEEKLY_SUB", "SUB-" + staffNo, DataScope.currentActor(),
                    enabled ? "SUBSCRIBE" : "UNSUBSCRIBE", "{\"enabled\":" + enabled + "}");
            log.info("营销周报订阅翻转：staffNo={} enabled={}", staffNo, enabled);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", enabled);
        m.put("changed", changed);
        m.put("lastSentWeek", sub == null ? null : sub.getLastSentWeek());
        return m;
    }
}
