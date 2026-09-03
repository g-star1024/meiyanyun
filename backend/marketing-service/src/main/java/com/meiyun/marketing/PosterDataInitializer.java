package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 裂变海报启动播种（M5-04）：表为空时幂等灌入 6 个海报模板 + 6 张已生成海报（对齐前端活规格）。
 * 金额口径：dealAmount bigint 存「分」（活规格为元，×100）；commissionRate 百分比×10（5% = 50）。
 * 模板 ID 用 PT-SEED-xxx、海报 ID 用 MP-SEED-xxx（种子固定号，用户新单据走 BizNoGenerator）。
 */
@Component
@Order(31)
public class PosterDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PosterDataInitializer.class);

    /** 默认分销佣金 5%（百分比×10 = 50）。 */
    private static final int DEFAULT_RATE = 50;

    private final PosterTemplateRepository templateRepo;
    private final PosterRecordRepository posterRepo;

    public PosterDataInitializer(PosterTemplateRepository templateRepo, PosterRecordRepository posterRepo) {
        this.templateRepo = templateRepo;
        this.posterRepo = posterRepo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (templateRepo.count() > 0 || posterRepo.count() > 0) {
            log.info("海报模板/记录已存在（模板 {} / 海报 {}），跳过播种",
                    templateRepo.count(), posterRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();

        List<PosterTemplate> tpls = new ArrayList<>();
        tpls.add(template("PT-SEED-001", "双11 狂欢大促", "FESTIVAL", "ENABLED", 128, "brand",
                "双11 狂欢季 礼遇焕新", "爆款项目限时直降，会员再享折上折", now));
        tpls.add(template("PT-SEED-002", "新客 88 元体验礼", "NEWBIE", "ENABLED", 96, "teal",
                "新客专享 88 元体验", "到店即赠皮肤检测一次，无隐形消费", now));
        tpls.add(template("PT-SEED-003", "热玛吉抗衰种草", "PROJECT", "ENABLED", 74, "purple",
                "热玛吉 FLX 紧致提拉", "正版仪器可验真，医师一对一定制方案", now));
        tpls.add(template("PT-SEED-004", "周三会员日", "MEMBER", "ENABLED", 210, "gold",
                "会员日 双倍积分", "每周三会员到店，积分翻倍兑好礼", now));
        tpls.add(template("PT-SEED-005", "老带新双赢礼", "REFERRAL", "ENABLED", 58, "orange",
                "邀请好友 各得 200 元", "好友到店成交，奖励自动到账", now));
        tpls.add(template("PT-SEED-006", "医美直播预约", "LIVE", "DISABLED", 32, "blue",
                "院长直播 在线答疑", "预约直播抽免单，限时福袋抢不停", now));
        templateRepo.saveAll(tpls);

        List<PosterRecord> posters = new ArrayList<>();
        int pseq = 0;
        pseq = addPoster(posters, pseq, tpls.get(0), "双11 狂欢季 礼遇焕新", "爆款项目限时直降，会员再享折上折",
                "水光嫩肤年卡", "林晚", 420, 286, 124, 58, 22, 68000, -12, now);
        pseq = addPoster(posters, pseq, tpls.get(1), "新客专享 88 元体验", "到店即赠皮肤检测一次，无隐形消费",
                "皮肤检测 + 小气泡", "王蕊", 360, 248, 96, 64, 18, 12600, -8, now);
        pseq = addPoster(posters, pseq, tpls.get(4), "邀请好友 各得 200 元", "好友到店成交，奖励自动到账",
                "通用项目券", "陈思", 210, 156, 72, 40, 14, 38400, -6, now);
        pseq = addPoster(posters, pseq, tpls.get(2), "热玛吉 FLX 紧致提拉", "正版仪器可验真，医师一对一定制方案",
                "热玛吉面部", "张敏", 180, 96, 38, 18, 6, 58800, -3, now);
        pseq = addPoster(posters, pseq, tpls.get(3), "会员日 双倍积分", "每周三会员到店，积分翻倍兑好礼",
                "会员日到店礼", "李娜", 520, 312, 88, 52, 12, 9600, -2, now);
        pseq = addPoster(posters, pseq, tpls.get(5), "院长直播 在线答疑", "预约直播抽免单，限时福袋抢不停",
                "直播预约", "王芳", 90, 64, 20, 4, 0, 0, -1, now);
        posterRepo.saveAll(posters);

        log.info("裂变海报播种完成：模板 {} 个 / 海报 {} 张", tpls.size(), posters.size());
    }

    private PosterTemplate template(String id, String name, String style, String status, int uses,
                                    String accent, String defaultTitle, String defaultSubtitle,
                                    OffsetDateTime now) {
        PosterTemplate t = new PosterTemplate();
        t.setTemplateId(id);
        t.setTemplateName(name);
        t.setStyle(style);
        t.setStatus(status);
        t.setUses(uses);
        t.setAccent(accent);
        t.setDefaultTitle(defaultTitle);
        t.setDefaultSubtitle(defaultSubtitle);
        t.setCreatedAt(now);
        return t;
    }

    private int addPoster(List<PosterRecord> posters, int seq, PosterTemplate t, String title, String subtitle,
                          String project, String referrer, int share, int scan, int lead, int visit, int deal,
                          long dealAmountYuan, int dayOffset, OffsetDateTime now) {
        PosterRecord p = new PosterRecord();
        p.setPosterId("MP-SEED-" + String.format("%03d", seq + 1));
        p.setTemplateId(t.getTemplateId());
        p.setTemplateName(t.getTemplateName());
        p.setStyle(t.getStyle());
        p.setAccent(t.getAccent());
        p.setTitle(title);
        p.setSubtitle(subtitle);
        p.setProject(project);
        p.setReferrerName(referrer);
        p.setStatus("PUBLISHED");
        p.setShare(share);
        p.setScan(scan);
        p.setLead(lead);
        p.setVisit(visit);
        p.setDeal(deal);
        p.setDealAmount(dealAmountYuan * 100);
        p.setCommissionRate(DEFAULT_RATE);
        p.setCreatedAt(now.plusDays(dayOffset));
        posters.add(p);
        return seq + 1;
    }
}
