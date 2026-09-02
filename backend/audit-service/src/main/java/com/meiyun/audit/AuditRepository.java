package com.meiyun.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AuditRepository extends JpaRepository<AuditLog, Long> {

    /** 取链尾（用于计算下一条的 prev_hash）。 */
    Optional<AuditLog> findFirstByOrderByCreatedAtDesc();

    /** 按写入顺序取全链（用于巡检校验）。 */
    List<AuditLog> findAllByOrderByIdAsc();
}
