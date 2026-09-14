package com.meiyun.store.sop;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface SopTemplateRepository extends JpaRepository<SopTemplate, Long> {

    Optional<SopTemplate> findByCode(String code);

    List<SopTemplate> findByCodeStartingWith(String prefix);

    @Query("select t from SopTemplate t where (cast(:status as string) is null or t.status = :status) order by t.id asc")
    List<SopTemplate> search(@Param("status") String status);
}
