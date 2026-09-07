package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface StaffCompConfigRepository extends JpaRepository<StaffCompConfig, String> {

    List<StaffCompConfig> findByStatusOrderByStaffIdAsc(String status);

    /** 员工当前生效配置（一人一 ACTIVE，应用层保证）。 */
    Optional<StaffCompConfig> findByStaffIdAndStatus(String staffId, String status);

    List<StaffCompConfig> findByStoreCodeAndStatus(String storeCode, String status);

    /**
     * 薪酬配置号当日最大序号（comp_id 形如 SC20260906-000001：2 位前缀 + 8 位日期 + 1 个连字符，序号从第 12 位起 6 位）。
     */
    @Query(value = "select coalesce(max(cast(substring(comp_id from 12) as bigint)), 0) " +
           "from staff_comp_config where comp_id like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
