package com.meiyun.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditChainExemptionRepository extends JpaRepository<AuditChainExemption, Long> {

    /** 按断链节点 id 批量取豁免（verifyChain 分离已豁免/新增用）。 */
    List<AuditChainExemption> findByAuditLogIdIn(Collection<Long> auditLogIds);

    /** 幂等预检：同一节点重复登记直接返回已存在记录。 */
    Optional<AuditChainExemption> findByAuditLogId(Long auditLogId);

    /** 豁免清单全量（登记顺序）。 */
    List<AuditChainExemption> findAllByOrderByIdAsc();
}
