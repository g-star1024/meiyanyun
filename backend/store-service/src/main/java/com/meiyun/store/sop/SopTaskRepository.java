package com.meiyun.store.sop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface SopTaskRepository extends JpaRepository<SopTask, Long> {

    @Query("select t from SopTask t where (cast(:storeCode as string) is null or t.storeCode = :storeCode) and (cast(:status as string) is null or t.status = :status) order by t.id asc")
    List<SopTask> search(@Param("storeCode") String storeCode, @Param("status") String status);
}
