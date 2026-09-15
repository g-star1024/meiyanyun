package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.LocalDate;
import java.util.List;

public interface DispatchAssignmentRepository
        extends JpaRepository<DispatchAssignment, Long>, JpaSpecificationExecutor<DispatchAssignment> {

    /** 某门店某日的活跃占用（已排期/进行中），用于时间轴渲染、待派单去重、时段冲突检测。 */
    List<DispatchAssignment> findByStoreCodeAndBizDateAndStatusInOrderByStartTimeAsc(
            String storeCode, LocalDate bizDate, List<String> statuses);

    /** 某张预约是否已有活跃占用（幂等：已派单的预约不重复派）。 */
    boolean existsByApptNoAndStatusIn(String apptNo, List<String> statuses);

    /** 同资源同日活跃占用（时段冲突检测在 Service 内做字符串区间比较）。 */
    List<DispatchAssignment> findByStoreCodeAndBizDateAndResourceTypeAndResourceIdAndStatusIn(
            String storeCode, LocalDate bizDate, String resourceType, String resourceId, List<String> statuses);
}
