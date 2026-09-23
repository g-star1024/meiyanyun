package com.meiyun.customer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.meiyun.customer.audit.AuditRecorder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 转介绍单条业务动作（P5-B86 卡2，DESIGN-P5-B86 §4.4）。
 * expireOne 单条独立事务，由无事务的 {@link ReferralExpireJob} 限批遍历调用，单条失败不毒化整批。
 */
@Service
public class ReferralService {

    /** 可到期态：仅 PENDING/CONFIRMED 参与过期扫描（VISITED 后转成交跟踪不再到期，DESIGN §4.1 状态机）。 */
    static final List<String> EXPIRABLE_STATUSES = List.of("PENDING", "CONFIRMED");
    /** 定时任务无登录人，审计 actor 统一记 SYSTEM（对齐 ComplianceInspectionJob 范式）。 */
    private static final String ACTOR = "SYSTEM";

    private final ReferralRepository referralRepo;
    private final AuditRecorder audit;

    private final ObjectMapper om = new ObjectMapper().registerModule(new JavaTimeModule());

    public ReferralService(ReferralRepository referralRepo, AuditRecorder audit) {
        this.referralRepo = referralRepo;
        this.audit = audit;
    }

    /**
     * 过期单条转介绍（单条一事务）。非可到期态或未到期返回 false，不重复置态/审计（空跑幂等）。
     * D3-A：置 EXPIRED 后部分唯一索引 uk_referral_referee_active 自动释放绑定，被推荐人可被重新推荐。
     */
    @Transactional
    public boolean expireOne(String referralId, OffsetDateTime now) {
        Referral r = referralRepo.findById(referralId).orElse(null);
        if (r == null || !EXPIRABLE_STATUSES.contains(r.getStatus()) || !r.getExpireAt().isBefore(now)) {
            return false;
        }
        r.setStatus("EXPIRED");
        r.setExpiredAt(now);
        referralRepo.save(r);
        audit.record("REFERRAL", r.getReferralId(), ACTOR, "EXPIRE", json(r));
        return true;
    }

    private String json(Object o) {
        try {
            return om.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
