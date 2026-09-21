package com.meiyun.store.reactivate;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReactivateLogRepository extends JpaRepository<ReactivateLog, Long> {

    List<ReactivateLog> findByRcIdOrderByIdDesc(Long rcId);
}
