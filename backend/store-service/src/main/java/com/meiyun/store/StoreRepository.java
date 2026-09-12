package com.meiyun.store;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StoreRepository extends JpaRepository<Store, String> {

    /**
     * 门店码字典序首家（store_code 升序）。
     * 门店主数据无创建时间字段，store_code 是唯一稳定排序键，
     * 供 /internal/first 兜底门店接口形成跨重启可复现的确定口径。
     */
    Optional<Store> findFirstByOrderByStoreCodeAsc();
}
