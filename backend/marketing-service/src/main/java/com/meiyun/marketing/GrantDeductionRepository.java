package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GrantDeductionRepository extends JpaRepository<GrantDeduction, Long> {

    /** 同单幂等反查：一次抵扣的多行流水共用 biz_ref，重放时原样返回既有流水不双扣。 */
    List<GrantDeduction> findByBizRefOrderByIdAsc(String bizRef);

    boolean existsByBizRef(String bizRef);

    /** 客户抵扣明细（对账/客服回溯）。 */
    List<GrantDeduction> findByCustomerIdOrderByIdDesc(String customerId);

    /**
     * B39 退款回加封顶：按原订单号 + REFUND 类型汇总累计已回加额（按赠金行分组），
     * 防同一订单多次部分退款把同一张券回加超过其原抵扣额。
     */
    @Query("select d.grantId, coalesce(sum(d.amountFen),0) from GrantDeduction d " +
            "where d.originBizRef = :orderNo and d.changeType = 'REFUND' group by d.grantId")
    List<Object[]> sumRefundedByOriginGroupByGrant(@Param("orderNo") String orderNo);
}
