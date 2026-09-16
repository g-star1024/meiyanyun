package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface StaffShiftRepository extends JpaRepository<StaffShift, Long> {

    /** 周视图：指定日期区间内（含端点）给定员工的班次，按日期、工号排序。 */
    List<StaffShift> findByStaffIdInAndShiftDateBetweenOrderByShiftDateAscStaffIdAsc(
            Collection<String> staffIds, LocalDate from, LocalDate to);

    /** 单日唯一键锚点（手工改班 upsert 用）。 */
    Optional<StaffShift> findByStaffIdAndShiftDate(String staffId, LocalDate shiftDate);
}
