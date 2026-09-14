package com.meiyun.store.sop;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SopTemplateStepRepository extends JpaRepository<SopTemplateStep, Long> {

    List<SopTemplateStep> findByTemplateIdOrderByStepNoAsc(Long templateId);
}
