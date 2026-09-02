package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrgUnitRepository extends JpaRepository<OrgUnit, String> {

    List<OrgUnit> findAllByOrderBySortNoAsc();

    List<OrgUnit> findByOrgTypeOrderBySortNoAsc(String orgType);

    List<OrgUnit> findByParentCodeOrderBySortNoAsc(String parentCode);

    List<OrgUnit> findByRegionOrderBySortNoAsc(String region);
}
