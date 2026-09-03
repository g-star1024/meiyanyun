package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 直播团购启动播种（M5-05）：表为空时幂等灌入 7 个直播场次 + 5 条短视频（对齐前端活规格 m5Live.ts）。
 * 金额口径：dealAmount bigint 存「分」（活规格为元，×100）。
 * 场次 ID 用 LS-SEED-xxx、短视频 ID 用 SV-SEED-xxx（种子固定号，用户新单据走 BizNoGenerator）。
 * 挂载券：取券模板表首张券 ID（无券则空数组），与活规格「挂载 m1.coupons 首张」一致。
 */
@Component
@Order(32)
public class LiveDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LiveDataInitializer.class);

    private final LiveSessionRepository sessionRepo;
    private final ShortVideoRepository videoRepo;
    private final CouponTemplateRepository couponRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LiveDataInitializer(LiveSessionRepository sessionRepo, ShortVideoRepository videoRepo,
                               CouponTemplateRepository couponRepo) {
        this.sessionRepo = sessionRepo;
        this.videoRepo = videoRepo;
        this.couponRepo = couponRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (sessionRepo.count() > 0 || videoRepo.count() > 0) {
            log.info("直播场次/短视频已存在（场次 {} / 短视频 {}），跳过播种",
                    sessionRepo.count(), videoRepo.count());
            return;
        }

        String mountedCoupons = firstCouponJson();
        LocalDate today = LocalDate.now();
        OffsetDateTime now = OffsetDateTime.now();

        List<LiveSession> sessions = new ArrayList<>();
        sessions.add(session("LS-SEED-001", "暑期水光自由卡专场", "DOUYIN", "LIVE",
                today, 19, 30, 8620, 1840, 86, 128000, mountedCoupons,
                "润致娃娃针次卡，直播间专属加赠面膜一盒", "林微", now));
        sessions.add(session("LS-SEED-002", "新客体验日·皮肤检测", "WECHAT_CHANNEL", "LIVE",
                today, 14, 0, 3240, 680, 32, 18600, mountedCoupons,
                "新客 88 元体验，到店即赠皮肤检测", "苏晴", now));
        sessions.add(session("LS-SEED-003", "热玛吉抗衰院长答疑", "DOUYIN", "NOT_STARTED",
                today.plusDays(2), 20, 0, 0, 0, 0, 0, mountedCoupons,
                "正版仪器可验真，院长一对一定制方案", "白桥", now));
        sessions.add(session("LS-SEED-004", "会员日双倍积分直播", "WECHAT_CHANNEL", "NOT_STARTED",
                today.plusDays(5), 19, 0, 0, 0, 0, 0, mountedCoupons,
                "会员日积分翻倍，直播间专属福袋", "陈雅琳", now));
        sessions.add(session("LS-SEED-005", "光子嫩肤买3送1", "DOUYIN", "ENDED",
                today.minusDays(7), 19, 30, 12400, 2680, 124, 186000, mountedCoupons,
                "光子嫩肤年卡限时买3送1", "林微", now));
        sessions.add(session("LS-SEED-006", "保妥适拼团夜", "DOUYIN", "ENDED",
                today.minusDays(14), 20, 0, 6800, 1240, 58, 86400, mountedCoupons,
                "保妥适拼团 8.5 折", "白桥", now));
        sessions.add(session("LS-SEED-007", "乔雅登品鉴会", "WECHAT_CHANNEL", "ENDED",
                today.minusDays(21), 15, 0, 2180, 420, 18, 52400, mountedCoupons,
                "乔雅登全系品鉴，私享优惠", "苏晴", now));
        sessionRepo.saveAll(sessions);

        List<ShortVideo> videos = new ArrayList<>();
        videos.add(video("SV-SEED-001", "水光针全过程vlog", "DOUYIN",
                186000, 12400, 42, 38600, List.of("水光", "种草"), today.minusDays(5)));
        videos.add(video("SV-SEED-002", "热玛吉避坑指南", "XIAOHONGSHU",
                92000, 8600, 28, 52400, List.of("热玛吉", "科普"), today.minusDays(8)));
        videos.add(video("SV-SEED-003", "新客88元体验实拍", "DOUYIN",
                248000, 18600, 86, 24800, List.of("新客", "体验"), today.minusDays(12)));
        videos.add(video("SV-SEED-004", "光子嫩肤效果对比", "WECHAT_CHANNEL",
                34000, 2100, 12, 18600, List.of("光子", "效果"), today.minusDays(18)));
        videos.add(video("SV-SEED-005", "门店环境探店", "XIAOHONGSHU",
                56000, 4200, 8, 6800, List.of("探店", "环境"), today.minusDays(25)));
        videoRepo.saveAll(videos);

        log.info("直播团购播种完成：场次 {} 个 / 短视频 {} 条（挂载券 {}）",
                sessions.size(), videos.size(), mountedCoupons);
    }

    private String firstCouponJson() {
        List<CouponTemplate> coupons = couponRepo.findAllByOrderByCreatedAtDesc();
        List<String> ids = new ArrayList<>();
        if (!coupons.isEmpty()) {
            ids.add(coupons.get(0).getCouponId());
        }
        try {
            return objectMapper.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private LiveSession session(String id, String title, String platform, String status,
                                LocalDate day, int hour, int minute,
                                int viewers, int linkClicks, int dealCount, long dealAmountYuan,
                                String mountedCouponIds, String intro, String host, OffsetDateTime now) {
        LiveSession s = new LiveSession();
        s.setSessionId(id);
        s.setTitle(title);
        s.setPlatform(platform);
        s.setStatus(status);
        s.setStartTime(LocalDateTime.of(day, LocalTime.of(hour, minute)));
        s.setViewers(viewers);
        s.setLinkClicks(linkClicks);
        s.setDealCount(dealCount);
        s.setDealAmount(dealAmountYuan * 100);
        s.setMountedCouponIds(mountedCouponIds);
        s.setIntro(intro);
        s.setHost(host);
        s.setCreatedAt(now);
        return s;
    }

    private ShortVideo video(String id, String title, String platform,
                             int plays, int likes, int dealCount, long dealAmountYuan,
                             List<String> tags, LocalDate publishedAt) {
        ShortVideo v = new ShortVideo();
        v.setVideoId(id);
        v.setTitle(title);
        v.setPlatform(platform);
        v.setPlays(plays);
        v.setLikes(likes);
        v.setDealCount(dealCount);
        v.setDealAmount(dealAmountYuan * 100);
        try {
            v.setTags(objectMapper.writeValueAsString(tags));
        } catch (JsonProcessingException e) {
            v.setTags("[]");
        }
        v.setPublishedAt(publishedAt);
        return v;
    }
}
