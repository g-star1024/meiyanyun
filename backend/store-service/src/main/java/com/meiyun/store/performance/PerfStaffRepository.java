package com.meiyun.store.performance;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface PerfStaffRepository extends JpaRepository<PerfStaff, Long>,
        JpaSpecificationExecutor<PerfStaff> {
}
