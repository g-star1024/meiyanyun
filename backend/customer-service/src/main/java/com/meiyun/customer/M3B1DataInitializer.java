package com.meiyun.customer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3-B1 启动播种（设置 M3-18＋NPS M3-12）：m3_settings 单例行（22 键默认）＋
 * m3_settings_change_log 三条＋nps_record 八条（NR0001-NR0008），均与前端
 * m3settings / nps store 种子活规格逐字一致（category 按 score 推导、
 * period 取各自提交日 ISO 周、createdAt 为当前时刻前 1-7 天）。
 *
 * <p><b>栈门控</b>：演示数据仅与 seed 栈自洽，仅在种子库（JDBC URL 含 meiyun_seed）播种，
 * 与各 DataInitializer 同一门控约定；正式栈设置由运营在 M3-18 页维护、NPS 回执由真实提交产生。
 */
@Component
@Order(42)
public class M3B1DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(M3B1DataInitializer.class);

    private final M3SettingsRepository settingsRepo;
    private final M3SettingsChangeLogRepository logRepo;
    private final NpsRecordRepository npsRepo;
    private final String datasourceUrl;

    public M3B1DataInitializer(M3SettingsRepository settingsRepo, M3SettingsChangeLogRepository logRepo,
                               NpsRecordRepository npsRepo,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.settingsRepo = settingsRepo;
        this.logRepo = logRepo;
        this.npsRepo = npsRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过 M3-B1 演示数据播种；正式栈设置/NPS 由运营真实维护",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        seedSettings();
        seedNps();
    }

    /** m3_settings 单例行＋变更日志三条（各自 count>0 跳过，幂等）。 */
    private void seedSettings() {
        if (settingsRepo.count() == 0) {
            M3Settings s = new M3Settings();
            s.setId(M3SettingsService.SINGLETON_ID);
            s.setSettings(M3SettingsService.defaultSettings());
            s.setUpdatedBy("系统初始化");
            settingsRepo.save(s);
            log.info("M3 设置播种：m3_settings 单例行（22 键默认）");
        }
        if (logRepo.count() > 0) {
            return;
        }
        saveLog("初始化客户域设置", "系统初始化",
                OffsetDateTime.of(2026, 3, 1, 9, 0, 0, 0, ZoneOffset.UTC), Map.of(), Map.of());
        saveLog("沉睡阈值调整为 90 天", "陈野（区域经理）",
                OffsetDateTime.of(2026, 6, 15, 14, 20, 0, 0, ZoneOffset.UTC),
                Map.of("dormantDays", 180), Map.of("dormantDays", 90));
        saveLog("开启手机号导出脱敏", "陈野（区域经理）",
                OffsetDateTime.of(2026, 7, 2, 10, 5, 0, 0, ZoneOffset.UTC),
                Map.of("maskPhoneInExport", false), Map.of("maskPhoneInExport", true));
        log.info("M3 设置变更日志播种：3 条（初始化/沉睡阈值调整/开启导出脱敏）");
    }

    private void saveLog(String action, String actor, OffsetDateTime at,
                         Map<String, Object> before, Map<String, Object> after) {
        M3SettingsChangeLog l = new M3SettingsChangeLog();
        l.setAction(action);
        l.setActor(actor);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("before", before);
        payload.put("after", after);
        l.setPayload(payload);
        l.setCreatedAt(at);
        logRepo.save(l);
    }

    /** nps_record 八条（count>0 跳过；createdAt 前 1-7 天，period 取各自提交日 ISO 周）。 */
    private void seedNps() {
        if (npsRepo.count() > 0) {
            log.info("NPS 记录已存在（{} 条），跳过播种", npsRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        saveNps("NR0001", "陈美玲", 10, "热玛吉紧致", List.of("效果显著", "服务贴心", "环境舒适"),
                "王医生手法专业，做完半侧脸明显提升，下次还来！", now.minusDays(1), "FOLLOWED", "已电话回访");
        saveNps("NR0002", "赵雨晴", 9, "水光补水", List.of("皮肤变好", "不疼"),
                "护士很温柔，补水效果不错，推荐闺蜜一起来。", now.minusDays(2), "FOLLOWED", null);
        saveNps("NR0003", "孙佳宁", 8, "光子嫩肤", List.of("流程顺畅"),
                "整体还行，就是等了一会儿，希望下次能更快。", now.minusDays(2), "PENDING", null);
        saveNps("NR0004", "林婉清", 7, "小气泡清洁", List.of("一般"),
                "清洁力度一般，和想象有差距，价格略贵。", now.minusDays(3), "PENDING", null);
        saveNps("NR0005", "周雅琴", 6, "射频紧肤", List.of("效果不明显", "等待久"),
                "做完一次没感觉有变化，等了快 40 分钟才轮到。", now.minusDays(4), "PENDING", null);
        saveNps("NR0006", "吴思涵", 3, "玻尿酸填充", List.of("疼痛", "态度差", "退款"),
                "注射时非常疼，咨询师一直推销加项目，体验很差，要求退款！", now.minusDays(5), "PENDING", null);
        saveNps("NR0007", "郑晓彤", 10, "超声刀", List.of("专业", "效果好"),
                "李医生耐心讲解，做完轮廓清晰很多，值得。", now.minusDays(6), "FOLLOWED", null);
        saveNps("NR0008", "黄丽萍", 5, "皮秒祛斑", List.of("反黑", "恢复慢"),
                "做完两周还有红印，担心反黑，希望尽快联系我。", now.minusDays(7), "PENDING", null);
        log.info("NPS 记录播种：8 条（NR0001-NR0008，推荐者 3/被动者 2/贬损者 3，待跟进 5）");
    }

    private void saveNps(String recordNo, String customer, int score, String service, List<String> tags,
                         String comment, OffsetDateTime createdAt, String followStatus, String followNote) {
        NpsRecord r = new NpsRecord();
        r.setRecordNo(recordNo);
        r.setCustomerId(null);
        r.setCustomerName(customer);
        r.setScore(score);
        r.setCategory(score >= 9 ? "PROMOTER" : score >= 7 ? "PASSIVE" : "DETRACTOR");
        r.setService(service);
        r.setTags(tags);
        r.setComment(comment);
        r.setPeriod(NpsService.isoWeek(createdAt.toLocalDate()));
        r.setFollowStatus(followStatus);
        r.setFollowNote(followNote);
        r.setCreatedAt(createdAt);
        r.setUpdatedAt(createdAt);
        npsRepo.save(r);
    }
}
