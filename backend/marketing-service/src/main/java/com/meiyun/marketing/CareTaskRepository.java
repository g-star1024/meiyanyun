package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CareTaskRepository extends JpaRepository<CareTask, Long> {
    List<CareTask> findAllByOrderByCreatedAtDesc();

    Optional<CareTask> findByCareNo(String careNo);

    /** 单据号生成：取当日同前缀最大号（参数如 CARE20260924-%）。 */
    Optional<CareTask> findTopByCareNoLikeOrderByCareNoDesc(String prefix);
}
