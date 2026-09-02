package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, String>, JpaSpecificationExecutor<Staff> {

    List<Staff> findAllByOrderByStaffIdAsc();

    List<Staff> findByStoreCodeOrderByStaffIdAsc(String storeCode);

    List<Staff> findByRoleCodeOrderByStaffIdAsc(String roleCode);

    Optional<Staff> findByLoginName(String loginName);
}
