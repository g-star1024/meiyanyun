package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface CustomerLevelHistoryRepository extends JpaRepository<CustomerLevelHistory, Long> {

    /** 客户最近一次进入指定等级的记录（保护期起算锚点）。 */
    Optional<CustomerLevelHistory> findFirstByCustomerIdAndToLevelOrderByChangedAtDesc(
            String customerId, String toLevel);

    List<CustomerLevelHistory> findByCustomerIdOrderByChangedAtDesc(String customerId);

    /** 降级批处理：一次取多客户的全部历史，内存过滤保护期锚点，避免 N+1。 */
    List<CustomerLevelHistory> findByCustomerIdIn(Collection<String> customerIds);

    /** 已有任意变更历史（含 LEVEL_INIT）的客户编号集合——LEVEL_INIT 补种差集判定用。 */
    @Query("select distinct h.customerId from CustomerLevelHistory h where h.customerId in :customerIds")
    List<String> findExistingCustomerIds(@Param("customerIds") Collection<String> customerIds);
}
