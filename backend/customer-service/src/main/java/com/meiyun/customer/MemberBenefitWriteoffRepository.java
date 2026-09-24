package com.meiyun.customer;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberBenefitWriteoffRepository extends JpaRepository<MemberBenefitWriteoff, Long> {

    /** 幂等反查：client_request_id 命中返既有流水，重复提交/双击/重放不重复扣次。 */
    Optional<MemberBenefitWriteoff> findByClientRequestId(String clientRequestId);

    /** 360 读模型：客户核销流水倒序（近 20 条，Pageable 截断）。 */
    List<MemberBenefitWriteoff> findByCustomerIdOrderByWriteoffIdDesc(String customerId, Pageable pageable);

    /** 当日核销单号最大序号（writeoff_no 形如 BW20260924-000001，序号从第 12 位起 6 位；与 RC/MC/OD/PM 各单号同口径）。 */
    @Query(value = "select coalesce(max(cast(substring(writeoff_no from 12) as bigint)), 0) "
            + "from member_benefit_writeoff where writeoff_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
