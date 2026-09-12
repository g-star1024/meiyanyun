package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface GrantDeductionRepository extends JpaRepository<GrantDeduction, Long> {

    /** 同单幂等反查：一次抵扣的多行流水共用 biz_ref，重放时原样返回既有流水不双扣。 */
    List<GrantDeduction> findByBizRefOrderByIdAsc(String bizRef);

    boolean existsByBizRef(String bizRef);

    /** 客户抵扣明细（对账/客服回溯）。 */
    List<GrantDeduction> findByCustomerIdOrderByIdDesc(String customerId);
}
