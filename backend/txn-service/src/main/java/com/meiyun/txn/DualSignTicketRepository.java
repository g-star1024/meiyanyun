package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface DualSignTicketRepository extends JpaRepository<DualSignTicket, String>, JpaSpecificationExecutor<DualSignTicket> {

    List<DualSignTicket> findAllByOrderByCreatedAtDesc();

    List<DualSignTicket> findByBizTypeOrderByCreatedAtDesc(String bizType);

    /**
     * 现金日结：按门店聚合指定日期「现金交接」类已完成双签工单金额（单位：分）。
     * 只统计 status='已完成' 的工单；按 signed_at2（第二签完成时间）落在当日过滤。
     */
    @Query(value = "select store_code as storeCode, coalesce(sum(amount),0) as totalAmount, " +
            "count(*) as ticketCount from dual_sign_ticket " +
            "where biz_type = '现金交接' and status = '已完成' " +
            "and cast(:date as date) is not null " +
            "and cast(signed_at2 at time zone 'Asia/Shanghai' as date) = cast(:date as date) " +
            "and (cast(:store as varchar) is null or store_code = cast(:store as varchar)) " +
            "group by store_code order by store_code",
            nativeQuery = true)
    List<CashSettleRow> cashSettleByDate(@Param("date") String date, @Param("store") String store);

    interface CashSettleRow {
        String getStoreCode();
        Long getTotalAmount();
        Long getTicketCount();
    }
}
