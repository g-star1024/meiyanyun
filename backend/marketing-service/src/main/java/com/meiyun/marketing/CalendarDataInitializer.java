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

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 营销日历启动播种（P5-B88）：calendar_node / calendar_schedule 为空时幂等灌入
 * 23 个 2026 年 8-12 月节点 + 4 条排期，与前端 m5Calendar store seed 活规格逐字一致
 *（节点标题/类型/描述、排期名称/状态/预估营收/创建人全对齐）。
 * 节点 ID 用 CN-SEED-xxx、排期 ID 用 CS-SEED-xxx（种子固定号，用户新建走 BizNoGenerator 的 CS 前缀）。
 * 金额口径：estimatedRevenueCents bigint 存「分」（活规格为元，×100）。
 * 挂载券：暑期水光排期取券模板表首张券 ID（无券则空数组），与活规格「m1.coupons 首张」一致。
 *
 * <p><b>栈门控</b>：CN-/CS-SEED 固定号演示数据仅与 seed 栈自洽，仅在种子库
 *（JDBC URL 含 meiyun_seed）播种，与 CampaignDataInitializer 同一门控约定；
 * 正式栈节点/排期由运营在营销日历页真实维护。
 */
@Component
@Order(41)
public class CalendarDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalendarDataInitializer.class);

    private final CalendarNodeRepository nodeRepo;
    private final CalendarScheduleRepository scheduleRepo;
    private final CouponTemplateRepository couponRepo;
    private final String datasourceUrl;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CalendarDataInitializer(CalendarNodeRepository nodeRepo, CalendarScheduleRepository scheduleRepo,
                                   CouponTemplateRepository couponRepo,
                                   @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.nodeRepo = nodeRepo;
        this.scheduleRepo = scheduleRepo;
        this.couponRepo = couponRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过营销日历演示数据播种；正式栈节点/排期由运营真实维护",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (nodeRepo.count() > 0 || scheduleRepo.count() > 0) {
            log.info("营销日历已存在（节点 {} / 排期 {}），跳过播种", nodeRepo.count(), scheduleRepo.count());
            return;
        }

        OffsetDateTime now = OffsetDateTime.now();
        List<CalendarNode> nodes = new ArrayList<>();
        // 8 月
        nodes.add(node("CN-SEED-001", LocalDate.of(2026, 8, 1), "建军节", "festival", "致敬军人，专属福利", now));
        nodes.add(node("CN-SEED-002", LocalDate.of(2026, 8, 8), "8 月会员日", "member", "每月 8 号会员日，积分双倍", now));
        nodes.add(node("CN-SEED-003", LocalDate.of(2026, 8, 12), "国际青年日", "festival", "青年顾客专属焕肤套餐", now));
        nodes.add(node("CN-SEED-004", LocalDate.of(2026, 8, 15), "暑期水光自由卡", "campaign", "主推润致娃娃针次卡", now));
        nodes.add(node("CN-SEED-005", LocalDate.of(2026, 8, 19), "七夕情人节", "festival", "情侣同行，双人套餐", now));
        nodes.add(node("CN-SEED-006", LocalDate.of(2026, 8, 22), "新客首享体验周", "campaign", "新客 88 元体验项目", now));
        nodes.add(node("CN-SEED-007", LocalDate.of(2026, 8, 25), "全国护肤日预热", "campaign", "皮肤检测免费预约", now));
        nodes.add(node("CN-SEED-008", LocalDate.of(2026, 8, 28), "月末宠粉日", "campaign", "老客到店赠精华小样", now));
        // 9 月
        nodes.add(node("CN-SEED-009", LocalDate.of(2026, 9, 8), "9 月会员日", "member", "会员日专享满减", now));
        nodes.add(node("CN-SEED-010", LocalDate.of(2026, 9, 10), "教师节", "festival", "凭教师资格证享 8.5 折", now));
        nodes.add(node("CN-SEED-011", LocalDate.of(2026, 9, 15), "秋季抗衰专场", "campaign", "热玛吉/超声炮套餐", now));
        nodes.add(node("CN-SEED-012", LocalDate.of(2026, 9, 25), "中秋节", "festival", "中秋团圆礼盒", now));
        // 10 月
        nodes.add(node("CN-SEED-013", LocalDate.of(2026, 10, 1), "国庆黄金周", "festival", "10.1-10.7 全场满赠", now));
        nodes.add(node("CN-SEED-014", LocalDate.of(2026, 10, 8), "10 月会员日", "member", null, now));
        nodes.add(node("CN-SEED-015", LocalDate.of(2026, 10, 18), "重阳节", "festival", "孝心套餐，带父母同行", now));
        nodes.add(node("CN-SEED-016", LocalDate.of(2026, 10, 20), "双 11 预热", "campaign", "提前锁价，储值翻倍", now));
        // 11 月
        nodes.add(node("CN-SEED-017", LocalDate.of(2026, 11, 8), "11 月会员日", "member", null, now));
        nodes.add(node("CN-SEED-018", LocalDate.of(2026, 11, 11), "双 11 狂欢", "festival", "全年最低价，限时 24 小时", now));
        // 12 月
        nodes.add(node("CN-SEED-019", LocalDate.of(2026, 12, 8), "12 月会员日", "member", null, now));
        nodes.add(node("CN-SEED-020", LocalDate.of(2026, 12, 12), "双 12 年终庆", "campaign", null, now));
        nodes.add(node("CN-SEED-021", LocalDate.of(2026, 12, 24), "平安夜", "festival", null, now));
        nodes.add(node("CN-SEED-022", LocalDate.of(2026, 12, 25), "圣诞节", "festival", null, now));
        nodes.add(node("CN-SEED-023", LocalDate.of(2026, 12, 31), "门店 5 周年店庆", "campaign", "周年庆，全年最大力度", now));
        nodeRepo.saveAll(nodes);

        String firstCoupon = firstCouponJson();
        List<CalendarSchedule> schedules = new ArrayList<>();
        schedules.add(schedule("CS-SEED-001", "CN-SEED-002", LocalDate.of(2026, 8, 8),
                "8 月会员日·乔雅登满减", "乔雅登满 5000 减 800，会员双倍积分",
                List.of(), 200, LocalDate.of(2026, 8, 8), LocalDate.of(2026, 8, 10),
                List.of("WECOM", "WECHAT_MP"), "8 月会员日专属福利，乔雅登满 5000 减 800，仅此 3 天",
                "RUNNING", 18000000L, "白桥", now.plusDays(-10), now));
        schedules.add(schedule("CS-SEED-002", "CN-SEED-004", LocalDate.of(2026, 8, 15),
                "暑期水光自由卡", "润致娃娃针 3 次卡，赠送修复面膜 1 盒",
                null, 100, LocalDate.of(2026, 8, 15), LocalDate.of(2026, 8, 31),
                List.of("WECOM", "SMS"), "暑期水光自由卡，3 次超值套餐，抖音直播同步发售",
                "RUNNING", 38600000L, "白桥", now.plusDays(-20), now));
        schedules.get(1).setCouponIds(firstCoupon);
        schedules.add(schedule("CS-SEED-003", "CN-SEED-005", LocalDate.of(2026, 8, 19),
                "七夕·双人同行", "情侣双人到店，第二人半价",
                List.of(), 300, LocalDate.of(2026, 8, 19), LocalDate.of(2026, 8, 20),
                List.of("WECHAT_MP", "WECOM"), "七夕相约，双人同行第二人半价，赠鲜花礼盒",
                "SCHEDULED", 12000000L, "林微", now.plusDays(-5), now));
        schedules.add(schedule("CS-SEED-004", "CN-SEED-010", LocalDate.of(2026, 9, 10),
                "教师节感恩专场", "凭教师资格证 8.5 折，赠手部护理",
                List.of(), 150, LocalDate.of(2026, 9, 10), LocalDate.of(2026, 9, 12),
                List.of("SMS"), "感恩教师节，凭资格证享 8.5 折优惠",
                "DRAFT", 6000000L, "苏晴", now.plusDays(-2), now));
        scheduleRepo.saveAll(schedules);

        log.info("营销日历播种完成：节点 {} 个 / 排期 {} 条（进行中 {} / 待开始 {} / 草稿 {}，挂载券 {}）",
                nodes.size(), schedules.size(),
                schedules.stream().filter(s -> "RUNNING".equals(s.getStatus())).count(),
                schedules.stream().filter(s -> "SCHEDULED".equals(s.getStatus())).count(),
                schedules.stream().filter(s -> "DRAFT".equals(s.getStatus())).count(),
                firstCoupon);
    }

    private CalendarNode node(String id, LocalDate date, String title, String type, String desc,
                              OffsetDateTime now) {
        CalendarNode n = new CalendarNode();
        n.setNodeId(id);
        n.setNodeDate(date);
        n.setTitle(title);
        n.setNodeType(type);
        n.setNodeDesc(desc);
        n.setCreatedAt(now);
        return n;
    }

    private CalendarSchedule schedule(String id, String nodeId, LocalDate nodeDate, String name,
                                      String benefitDesc, List<String> couponIds, int pointsReward,
                                      LocalDate startDate, LocalDate endDate, List<String> channels,
                                      String copyText, String status, long estimatedRevenueCents,
                                      String createdBy, OffsetDateTime createdAt, OffsetDateTime now) {
        CalendarSchedule s = new CalendarSchedule();
        s.setScheduleId(id);
        s.setNodeId(nodeId);
        s.setNodeDate(nodeDate);
        s.setScheduleName(name);
        s.setBenefitDesc(benefitDesc);
        s.setCouponIds(toJson(couponIds == null ? List.of() : couponIds));
        s.setPointsReward(pointsReward);
        s.setStartDate(startDate);
        s.setEndDate(endDate);
        s.setChannels(toJson(channels));
        s.setCopyText(copyText);
        s.setStatus(status);
        s.setEstimatedRevenueCents(estimatedRevenueCents);
        s.setCreatedBy(createdBy);
        s.setCreatedAt(createdAt);
        s.setUpdatedAt(now);
        return s;
    }

    /** 取券模板表首张券 ID JSON 数组（无券则空数组），与 LiveDataInitializer 同一口径。 */
    private String firstCouponJson() {
        List<CouponTemplate> coupons = couponRepo.findAllByOrderByCreatedAtDesc();
        List<String> ids = new ArrayList<>();
        if (!coupons.isEmpty()) {
            ids.add(coupons.get(0).getCouponId());
        }
        return toJson(ids);
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
