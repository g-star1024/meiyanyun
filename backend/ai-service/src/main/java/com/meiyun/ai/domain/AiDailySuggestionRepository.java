package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiDailySuggestionRepository extends JpaRepository<AiDailySuggestion, Long> {

    List<AiDailySuggestion> findByReportIdOrderBySuggestionIdAsc(Long reportId);

    long countByAdoptedTrue();
}
