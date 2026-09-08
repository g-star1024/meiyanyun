package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface NotifyPreferenceRepository extends JpaRepository<NotifyPreference, Long> {

    /** 某员工全部类别偏好（通知中心偏好卡渲染）。 */
    List<NotifyPreference> findByStaffId(String staffId);

    /** 某员工某类别偏好（无记录 = 系统默认订阅开启）。 */
    Optional<NotifyPreference> findByStaffIdAndCategory(String staffId, String category);
}
