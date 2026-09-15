package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReportTemplateRepository extends JpaRepository<ReportTemplate, String> {

    List<ReportTemplate> findAllByOrderByIdAsc();
}
