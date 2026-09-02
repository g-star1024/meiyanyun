package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RoleDefRepository extends JpaRepository<RoleDef, String> {

    List<RoleDef> findAllByOrderByRoleCodeAsc();
}
