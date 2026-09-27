package com.meiyun.customer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IoTaskRepository extends JpaRepository<IoTask, Long> {

  Optional<IoTask> findTopByTaskNoLikeOrderByTaskNoDesc(String likePattern);

  Optional<IoTask> findByFileHash(String fileHash);

  List<IoTask> findAllByTypeOrderByCreatedAtDesc(String type);

  @Query("select coalesce(sum(t.totalCount), 0) from IoTask t where t.type = :type and t.createdAt >= :since")
  long sumTotalSince(@Param("type") String type, @Param("since") OffsetDateTime since);

  @Query("select count(t) from IoTask t where t.type = 'IMPORT' and t.status in ('PENDING', 'VALIDATING')")
  long countPendingImports();
}
