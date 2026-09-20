package com.meiyun.store.workorder;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface WorkOrderRepository extends JpaRepository<WorkOrder, Long>,
        JpaSpecificationExecutor<WorkOrder> {

    Optional<WorkOrder> findByWoNo(String woNo);

    @Query(value = "select coalesce(max(cast(substring(wo_no from 13) as bigint)),0) "
            + "from work_order where wo_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
