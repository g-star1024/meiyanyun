package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 触点快照统一旁路落点（P5-B98 / DESIGN-T2 §3-D4：仅存不算）。
 *
 * <p>五类触点共用：采集端点（LANDING_VISIT/LANDING_LEAD 经 {@link #recordLanding}）与
 * 既有写链路旁路（PUSH_SEND 于 PushService 落库点 / RETURNBACK 于 ExternalChannelController 落库点，
 * 经 {@link #record}）统一落 touch_event；POSTER_SCAN 待 C 端扫码入口（§7）。
 *
 * <p>幂等两层（D8 idemKey 范式）：
 * <ol>
 *   <li>应用层查重：client_token 非空先查 (client_token, touch_type)，命中即静默 dedup 返回 false（不抛不阻断主链路）；</li>
 *   <li>DB 层兜底：(client_token, touch_type) 部分唯一索引——并发双请求同过查重时后者撞约束，
 *       由采集端点捕 DataIntegrityViolationException 整事务回滚（touch_event+自增同滚，不重不计）返 dedup=true。
 *       旁路快照 client_token 恒 NULL（部分唯一索引不适用），无冲突路径。</li>
 * </ol>
 */
@Service
public class TouchEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(TouchEventRecorder.class);

    public static final String TYPE_LANDING_VISIT = "LANDING_VISIT";
    public static final String TYPE_LANDING_LEAD = "LANDING_LEAD";
    public static final String TYPE_POSTER_SCAN = "POSTER_SCAN";
    public static final String TYPE_PUSH_SEND = "PUSH_SEND";
    public static final String TYPE_RETURNBACK = "RETURNBACK";

    public static final String CHANNEL_LANDING = "LANDING";

    private final TouchEventRepository repo;
    private final LandingPageRepository landingPageRepository;

    public TouchEventRecorder(TouchEventRepository repo, LandingPageRepository landingPageRepository) {
        this.repo = repo;
        this.landingPageRepository = landingPageRepository;
    }

    /**
     * 通用旁路落点（PUSH_SEND / RETURNBACK；client_token 传 null 无唯一约束直插）。
     *
     * @return true=新落库；false=(client_token,touch_type) 查重命中静默 dedup
     */
    @Transactional
    public boolean record(String channel, String touchType, String refType, String refId,
                          String customerId, String clientToken, String payload) {
        if (clientToken != null && !clientToken.isBlank()
                && repo.findByClientTokenAndTouchType(clientToken, touchType).isPresent()) {
            log.info("触点快照幂等命中：type={} clientToken={} 视为重放不重复落库", touchType, clientToken);
            return false;
        }
        TouchEvent e = new TouchEvent();
        e.setChannel(channel);
        e.setTouchType(touchType);
        e.setRefType(refType);
        e.setRefId(refId);
        e.setCustomerId(customerId);
        e.setClientToken(clientToken);
        e.setPayload(payload);
        repo.save(e);
        return true;
    }

    /**
     * 落地页采集落点：同事务 touch_event 落库＋landing_page visits/leads 原子自增（D4）。
     * 并发撞唯一约束时整事务回滚并抛 DataIntegrityViolationException（由采集端点捕返 dedup=true）。
     *
     * @param touchType {@link #TYPE_LANDING_VISIT} 或 {@link #TYPE_LANDING_LEAD}
     * @return true=新落库并已自增；false=查重命中 dedup（不自增）
     */
    @Transactional
    public boolean recordLanding(String touchType, String pageId, String clientToken, String payload) {
        boolean inserted = record(CHANNEL_LANDING, touchType, "LANDING_PAGE", pageId, null, clientToken, payload);
        if (!inserted) {
            return false;
        }
        if (TYPE_LANDING_LEAD.equals(touchType)) {
            landingPageRepository.bumpLeads(pageId);
        } else {
            landingPageRepository.bumpVisits(pageId);
        }
        return true;
    }
}
