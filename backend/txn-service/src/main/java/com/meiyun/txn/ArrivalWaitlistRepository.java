package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ArrivalWaitlistRepository
        extends JpaRepository<ArrivalWaitlist, String>, JpaSpecificationExecutor<ArrivalWaitlist> {

    /** 号源释放递补：同店 WAITING 最早登记一条（FIFO）。 */
    ArrivalWaitlist findFirstByStoreCodeAndStatusOrderByCreatedAtAsc(String storeCode, String status);

    /** 锚定客户活跃候补幂等：同客户在同店仍有 WAITING/NOTIFIED 单。 */
    List<ArrivalWaitlist> findByStoreCodeAndCustomerIdAndStatusIn(
            String storeCode, String customerId, List<String> statuses);

    /** 散客活跃候补幂等：同掩码手机号在同店仍有 WAITING/NOTIFIED 单。 */
    List<ArrivalWaitlist> findByStoreCodeAndPhoneAndStatusIn(
            String storeCode, String phone, List<String> statuses);

    /** 当日候补单号最大序号：WL + yyyyMMdd + - + 6 位，序号从第 12 位起（与 AH/AP/WD 同构）。 */
    @Query(value = "select coalesce(max(cast(substring(wl_no from 12) as bigint)), 0) " +
            "from arrival_waitlist where wl_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
