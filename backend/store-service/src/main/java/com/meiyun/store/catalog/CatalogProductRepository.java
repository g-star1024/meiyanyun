package com.meiyun.store.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {

    List<CatalogProduct> findAllByOrderByProductCodeAsc();

    List<CatalogProduct> findByStoreCodeOrderByProductCodeAsc(String storeCode);

    /** 按商品编码查模板（编码由后端全局递增生成，实际唯一）；B16 售卡取在售模板用。 */
    Optional<CatalogProduct> findFirstByProductCode(String productCode);

    boolean existsByStoreCodeAndProductCode(String storeCode, String productCode);

    boolean existsByStoreCodeAndName(String storeCode, String name);

    boolean existsByStoreCodeAndNameAndIdNot(String storeCode, String name, Long id);
}
