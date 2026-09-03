package com.meiyun.marketing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PosterTemplateRepository extends JpaRepository<PosterTemplate, String> {

    Optional<PosterTemplate> findTopByTemplateIdLikeOrderByTemplateIdDesc(String like);
}
