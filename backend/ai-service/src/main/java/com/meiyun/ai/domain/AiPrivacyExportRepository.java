package com.meiyun.ai.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiPrivacyExportRepository extends JpaRepository<AiPrivacyExport, Long> {

    Page<AiPrivacyExport> findAllByOrderByExportIdDesc(Pageable pageable);
}
