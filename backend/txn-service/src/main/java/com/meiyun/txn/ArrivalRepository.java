package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface ArrivalRepository
        extends JpaRepository<Arrival, String>, JpaSpecificationExecutor<Arrival> {

    /** 预约签到自动建登记幂等：同预约号已有登记（不限状态）即不重复建。 */
    List<Arrival> findByApptNo(String apptNo);

    /** 当日单号最大序号：AH + yyyyMMdd + - + 6 位，序号从第 12 位起（与 WD/WO/CP 同构）。 */
    @Query(value = "select coalesce(max(cast(substring(ah_no from 12) as bigint)), 0) " +
            "from arrival where ah_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);

    /** 门店当日排队号最大值（按到店时间窗口收敛），无记录返 0。 */
    @Query("select coalesce(max(a.queueNo), 0) from Arrival a " +
            "where a.storeCode = :storeCode and a.arrivedAt >= :start and a.arrivedAt < :end")
    int maxQueueNo(@Param("storeCode") String storeCode,
                   @Param("start") OffsetDateTime start,
                   @Param("end") OffsetDateTime end);
}
