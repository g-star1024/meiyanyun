package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CalendarScheduleRepository extends JpaRepository<CalendarSchedule, String> {
    List<CalendarSchedule> findAllByOrderByCreatedAtDesc();

    List<CalendarSchedule> findByNodeIdOrderByCreatedAtDesc(String nodeId);

    /** 单据号生成：取当日同前缀最大号（参数如 CS20260923-%）。 */
    Optional<CalendarSchedule> findTopByScheduleIdLikeOrderByScheduleIdDesc(String prefix);

    /** 创建幂等：client_token 命中即返回已有行。 */
    Optional<CalendarSchedule> findByClientToken(String clientToken);

    /** Job：SCHEDULED 且已进活动窗口（start<=today<=end）→ 该启动。 */
    List<CalendarSchedule> findByStatusAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            String status, LocalDate start, LocalDate end);

    /** Job：指定状态且 end_date 已过 → 该结束（SCHEDULED 过期未启动 / RUNNING 到期）。 */
    List<CalendarSchedule> findByStatusAndEndDateBefore(String status, LocalDate date);
}
