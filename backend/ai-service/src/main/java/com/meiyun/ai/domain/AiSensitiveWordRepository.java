package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiSensitiveWordRepository extends JpaRepository<AiSensitiveWord, Long> {

    List<AiSensitiveWord> findByEnabledTrue();

    List<AiSensitiveWord> findByCategoryAndEnabledTrue(String category);

    List<AiSensitiveWord> findAllByOrderByWordIdDesc();

    long countByEnabledTrue();

    Optional<AiSensitiveWord> findByWordAndCategory(String word, String category);
}
