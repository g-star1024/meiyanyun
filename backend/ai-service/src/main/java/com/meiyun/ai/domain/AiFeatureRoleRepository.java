package com.meiyun.ai.domain;

import com.meiyun.ai.domain.AiFeatureRole.PK;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AiFeatureRoleRepository extends JpaRepository<AiFeatureRole, PK> {
    List<AiFeatureRole> findByFeatureCode(String featureCode);
    List<AiFeatureRole> findByRoleCode(String roleCode);
}
