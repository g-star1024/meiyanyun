package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.List;

public interface CheckinRecordRepository
        extends JpaRepository<CheckinRecord, String>, JpaSpecificationExecutor<CheckinRecord> {

    /** 登记幂等：本店当日、同掩码手机号、仍待确认（PENDING）的单（arrived_at 倒序，取最新一条）。 */
    List<CheckinRecord> findByStoreCodeAndPhoneAndStatusAndArrivedAtGreaterThanEqualOrderByArrivedAtDesc(
            String storeCode, String phone, String status, OffsetDateTime dayStart);


    /** 当日单号最大序号：CI + yyyyMMdd + - + 6 位，序号从第 12 位起（与 WD/WO/AP 同构）。 */
    @Query(value = "select coalesce(max(cast(substring(ci_no from 12) as bigint)), 0) " +
            "from checkin_record where ci_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
