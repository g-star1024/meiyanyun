package com.meiyun.ai.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiSensitiveWordRepository extends JpaRepository<AiSensitiveWord, Long> {

    List<AiSensitiveWord> findByEnabledTrue();

    List<AiSensitiveWord> findByCategoryAndEnabledTrue(String category);
}
