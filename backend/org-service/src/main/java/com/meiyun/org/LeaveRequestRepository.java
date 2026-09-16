package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, Long>, JpaSpecificationExecutor<LeaveRequest> {

    List<LeaveRequest> findAllByOrderByAppliedAtDescIdDesc();

    long countByStatus(String status);

    Optional<LeaveRequest> findFirstByOrderByIdDesc();

    /**
     * 同员工区间重叠的在途/已批准请假（存在即拒绝新登记）：
     * 重叠条件 start <= 既有.end 且 end >= 既有.start。
     */
    List<LeaveRequest> findByStaffIdAndStatusInAndStartDateLessThanEqualAndEndDateGreaterThanEqual(
            String staffId, List<String> statuses, LocalDate start, LocalDate end);
}
