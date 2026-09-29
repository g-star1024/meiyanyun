package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiT4GpuQuotaRepository extends JpaRepository<AiT4GpuQuota, Long> {

    Optional<AiT4GpuQuota> findByCode(String code);

    boolean existsByCode(String code);

    /** uk(department, project, period) 冲突预检（分配前给中文 400，避免 DB 约束 500） */
    boolean existsByDepartmentAndProjectAndPeriod(String department, String project, String period);

    /** 配额列表全量直返（配额量级小，前端 quotas 为数组契约，不分页） */
    List<AiT4GpuQuota> findAllByOrderByQuotaIdAsc();
}
