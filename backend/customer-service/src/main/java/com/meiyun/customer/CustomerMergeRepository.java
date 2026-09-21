package com.meiyun.customer;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CustomerMergeRepository extends JpaRepository<CustomerMerge, String> {

    /** 幂等短路：按幂等键查已有单据（前端重放/网络重试直接返回原单）。 */
    Optional<CustomerMerge> findByIdemKey(String idemKey);

    /** 服务层守卫：同一 loser 只允许一条 MERGED（终态不可逆）。 */
    boolean existsByMergedIdAndStatus(String mergedId, String status);

    /** 单号取号：查当日最大 merge_id（MG+8位日期 前缀，防重号铁律，禁内存自增）；无返回 null。 */
    @Query("select max(m.mergeId) from CustomerMerge m where m.mergeId like concat(:prefix, '%')")
    String maxIdForDate(@Param("prefix") String prefix);

    /** 候选发现排除：双向 (A,B)/(B,A) 已落指定状态（NOT_DUPLICATE）的 pair。 */
    @Query("""
            select count(m) > 0 from CustomerMerge m
            where m.status = :status
              and ((m.masterId = :a and m.mergedId = :b) or (m.masterId = :b and m.mergedId = :a))
            """)
    boolean existsPairWithStatus(@Param("a") String a, @Param("b") String b, @Param("status") String status);

    Page<CustomerMerge> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<CustomerMerge> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
