package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, RolePermission.Key> {

    List<RolePermission> findByRoleCodeIn(List<String> roleCodes);

    void deleteByRoleCode(String roleCode);
}
