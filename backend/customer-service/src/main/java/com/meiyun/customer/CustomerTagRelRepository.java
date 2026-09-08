package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CustomerTagRelRepository extends JpaRepository<CustomerTagRel, CustomerTagRel.Key> {
    List<CustomerTagRel> findByCustomerId(String customerId);
    List<CustomerTagRel> findByCustomerIdIn(List<String> customerIds);

    boolean existsByCustomerIdAndTagId(String customerId, String tagId);

    @Modifying
    @Query("delete from CustomerTagRel r where r.customerId = :customerId and r.tagId = :tagId")
    int deleteByCustomerIdAndTagId(@Param("customerId") String customerId, @Param("tagId") String tagId);

    @Modifying
    @Query("delete from CustomerTagRel r where r.tagId = :tagId")
    int deleteByTagId(@Param("tagId") String tagId);

    /** 全量标签覆盖人数（一次 group by 取回，供标签列表带 customerCount，避免逐标签 count）。 */
    @Query("select r.tagId, count(r.customerId) from CustomerTagRel r group by r.tagId")
    List<Object[]> countGroupByTag();

    /** 至少打过一个标签的去重客户数（标签覆盖人数，汇总卡用）。 */
    @Query("select count(distinct r.customerId) from CustomerTagRel r")
    long countDistinctCustomers();
}
