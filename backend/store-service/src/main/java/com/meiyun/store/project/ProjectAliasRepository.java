package com.meiyun.store.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ProjectAliasRepository extends JpaRepository<ProjectAlias, Long>,
        JpaSpecificationExecutor<ProjectAlias> {

    /** 门店级别名精确查（两级命中第一级） */
    Optional<ProjectAlias> findByAliasAndStoreCode(String alias, String storeCode);

    /** 全局别名精确查（两级命中第二级；store_code IS NULL 的 DB 层等价写法） */
    Optional<ProjectAlias> findByAliasAndStoreCodeIsNull(String alias);

    List<ProjectAlias> findByStatusOrderByAliasAscIdAsc(String status);

    List<ProjectAlias> findAllByOrderByAliasAscIdAsc();
}
