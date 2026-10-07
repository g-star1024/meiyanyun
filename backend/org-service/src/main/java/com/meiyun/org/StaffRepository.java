package com.meiyun.org;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface StaffRepository extends JpaRepository<Staff, String>, JpaSpecificationExecutor<Staff> {

    List<Staff> findAllByOrderByStaffIdAsc();

    /** 库内 E 号段最大工号（like 'E%' 天然排除 SE 种子工号），供 CodeGen 序号式续号。 */
    @Query("select max(s.staffId) from Staff s where s.staffId like 'E%'")
    String maxEId();

    List<Staff> findByStoreCodeOrderByStaffIdAsc(String storeCode);

    List<Staff> findByRoleCodeOrderByStaffIdAsc(String roleCode);

    Optional<Staff> findByLoginName(String loginName);
}
