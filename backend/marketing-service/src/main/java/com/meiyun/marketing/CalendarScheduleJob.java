package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 日历排期日更批处理（P5-B88）：每日 04:00（Asia/Shanghai，cron 可外部化）推进排期状态机——
 * <ul>
 *   <li>SCHEDULED 且已进活动窗口（start&lt;=today&lt;=end）→ RUNNING（{@link CalendarService#startOne}）；</li>
 *   <li>SCHEDULED 且 end_date 已过（过期未启动）→ ENDED（{@link CalendarService#endOne}）；</li>
 *   <li>RUNNING 且 end_date 已过 → ENDED。</li>
 * </ul>
 *
 * <p>大事务毒化防护（复刻 ReferralExpireJob 范式）：Job 方法本身无 {@code @Transactional}，
 * 流转动作走 Service 单条独立事务；单条失败 try-catch 不中断同批（坏数据不毒化其余单）。
 * 营销排期为低体量域（数十条量级），全量列表扫描无需限批；
 * startOne/endOne 内部带状态前置校验，天然幂等，失败行保留原态留待下轮/人工。
 */
@Component
public class CalendarScheduleJob {

    private static final Logger log = LoggerFactory.getLogger(CalendarScheduleJob.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final CalendarService calendarService;
    private final CalendarScheduleRepository scheduleRepo;

    public CalendarScheduleJob(CalendarService calendarService, CalendarScheduleRepository scheduleRepo) {
        this.calendarService = calendarService;
        this.scheduleRepo = scheduleRepo;
    }

    @Scheduled(cron = "${meiyun.calendar-schedule.cron:0 0 4 * * *}", zone = "Asia/Shanghai")
    public void rollover() {
        LocalDate today = LocalDate.now(ZONE);
        int started = 0;
        int ended = 0;
        int failed = 0;

        for (CalendarSchedule s : scheduleRepo
                .findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual("SCHEDULED", today, today)) {
            try {
                if (calendarService.startOne(s.getScheduleId(), today)) {
                    started++;
                }
            } catch (Exception ex) {
                failed++;
                log.warn("排期启动单条失败 scheduleId={} name={}: {}",
                        s.getScheduleId(), s.getScheduleName(), ex.getMessage());
            }
        }

        for (String status : new String[]{"SCHEDULED", "RUNNING"}) {
            for (CalendarSchedule s : scheduleRepo.findByStatusAndEndDateBefore(status, today)) {
                try {
                    if (calendarService.endOne(s.getScheduleId(), today)) {
                        ended++;
                    }
                } catch (Exception ex) {
                    failed++;
                    log.warn("排期结束单条失败 scheduleId={} name={} status={}: {}",
                            s.getScheduleId(), s.getScheduleName(), status, ex.getMessage());
                }
            }
        }

        if (started > 0 || ended > 0 || failed > 0) {
            log.info("日历排期日更完成（{}）：启动 {} 条，结束 {} 条，失败 {} 条", today, started, ended, failed);
        }
    }
}
