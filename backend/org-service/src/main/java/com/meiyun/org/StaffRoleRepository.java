package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StaffRoleRepository extends JpaRepository<StaffRole, StaffRole.Key> {

    List<StaffRole> findByStaffId(String staffId);

    List<StaffRole> findByStaffIdIn(List<String> staffIds);

    void deleteByRoleCode(String roleCode);
}
