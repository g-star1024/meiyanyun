package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 落地页启动播种（P5-B88）：landing_page 表为空时幂等灌入 5 个演示落地页，
 * 与前端 m5Landing store seed 活规格逐字一致（新客88元体验页 12800/860、热玛吉 A/B 两版等）。
 * 页面 ID 用 LP-SEED-xxx（种子固定号，用户新建走 BizNoGenerator 的 LP 前缀）。
 *
 * <p><b>栈门控</b>：LP-SEED 固定号演示页仅与 seed 栈演示数据自洽，仅在种子库
 * （JDBC URL 含 meiyun_seed）播种，与 CampaignDataInitializer 同一门控约定；
 * 正式栈落地页由运营在落地页搭建页真实创建。
 */
@Component
@Order(40)
public class LandingDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LandingDataInitializer.class);

    private final LandingPageRepository landingRepo;
    private final String datasourceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LandingDataInitializer(LandingPageRepository landingRepo,
                                  @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.landingRepo = landingRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过落地页演示数据播种；正式栈落地页由运营真实搭建",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (landingRepo.count() > 0) {
            log.info("落地页已存在（{} 个），跳过播种", landingRepo.count());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<LandingPage> pages = new ArrayList<>();

        pages.add(page("LP-SEED-001", "新客88元体验页", "NEWBIE", "PUBLISHED",
                "新客专享 88 元体验", "到店即赠皮肤检测一次", "皮肤检测+小气泡",
                List.of("姓名", "手机", "意向项目"), 12800, 860, false, null, -1, now));
        pages.add(page("LP-SEED-002", "热玛吉抗衰专场", "PROJECT", "PUBLISHED",
                "热玛吉 FLX 紧致提拉", "正版仪器 院长定制", "热玛吉面部",
                List.of("姓名", "手机"), 8600, 420, true,
                List.of(
                        Map.of("name", "A 版（原版）", "visits", 4300, "leads", 180),
                        Map.of("name", "B 版（新文案）", "visits", 4300, "leads", 286)),
                -3, now));
        pages.add(page("LP-SEED-003", "双11狂欢主会场", "FESTIVAL", "PUBLISHED",
                "双11 礼遇焕新", "爆款项目限时直降", "水光年卡",
                List.of("姓名", "手机", "意向项目"), 24600, 1240, false, null, -5, now));
        pages.add(page("LP-SEED-004", "周三会员日", "MEMBER", "DRAFT",
                "会员日 双倍积分", "每周三专属福利", "会员日到店礼",
                List.of("姓名", "手机"), 0, 0, false, null, -10, now));
        pages.add(page("LP-SEED-005", "品牌故事页", "BRAND", "OFFLINE",
                "匠心医美 十年品牌", "正规机构 专业医师", "品牌宣传",
                List.of("姓名", "手机"), 3200, 86, false, null, -20, now));

        landingRepo.saveAll(pages);
        log.info("落地页播种完成：{} 个（已发布 {} / 草稿 {} / 已下线 {}）",
                pages.size(),
                pages.stream().filter(p -> "PUBLISHED".equals(p.getStatus())).count(),
                pages.stream().filter(p -> "DRAFT".equals(p.getStatus())).count(),
                pages.stream().filter(p -> "OFFLINE".equals(p.getStatus())).count());
    }

    private LandingPage page(String id, String name, String template, String status,
                             String headline, String subtitle, String project,
                             List<String> formFields, long visits, long leads, boolean abEnabled,
                             List<Map<String, Object>> variants, int createdOffsetDays, OffsetDateTime now) {
        LandingPage p = new LandingPage();
        p.setPageId(id);
        p.setPageName(name);
        p.setTemplate(template);
        p.setStatus(status);
        p.setHeadline(headline);
        p.setSubtitle(subtitle);
        p.setProject(project);
        p.setFormFields(toJson(formFields));
        p.setBlocks(toJson(LandingService.DEFAULT_BLOCKS));
        p.setVisits(visits);
        p.setLeads(leads);
        p.setAbEnabled(abEnabled);
        p.setVariants(variants == null ? "[]" : toJson(variants));
        p.setCreatedAt(now.plusDays(createdOffsetDays));
        p.setUpdatedAt(now.plusDays(createdOffsetDays));
        return p;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
