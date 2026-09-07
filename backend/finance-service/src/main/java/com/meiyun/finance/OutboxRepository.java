package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {

    List<OutboxRecord> findByStatusOrderByCreatedAtDesc(String status);

    /**
     * B11 重算本月：删除该月系统结转产生的成本对账台账行（与 fund_entry/cost_allocation 同事务）。
     * 结转 outbox 的 txn_no 与 fund_entry.biz_ref 同格式（CCR规则号:yyyy-MM:门店，V8 扩宽至 64），
     * biz_type='COST' 且 channel 为空；人工成本录入（MANUAL/ADJUST）txn_no 为 COST 单号，
     * 不以 CCR 开头，不会被误删。
     *
     * <p>按 txn_no 中段目标月（:monthToken，yyyy-MM）匹配而非 created_at 窗口：outbox.created_at
     * 是结转执行时刻，跨月重算（如 9 月重跑 8 月）时执行时刻不在目标月窗口内会漏删；
     * txn_no 中段恒为目标月，与 cost_allocation 按 period_month 删除同理。可按门店收敛。
     */
    @Modifying
    @Query(value = "delete from outbox_record where biz_type = 'COST' " +
            "and txn_no like 'CCR%:' || :monthToken || ':%' " +
            "and (:storeCode = '' or txn_no like '%:' || :storeCode)", nativeQuery = true)
    int deleteSystemCarry(@Param("monthToken") String monthToken,
                          @Param("storeCode") String storeCode);
}
