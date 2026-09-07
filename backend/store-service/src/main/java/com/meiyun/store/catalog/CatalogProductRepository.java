package com.meiyun.store.catalog;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CatalogProductRepository extends JpaRepository<CatalogProduct, Long> {

    List<CatalogProduct> findAllByOrderByProductCodeAsc();

    List<CatalogProduct> findByStoreCodeOrderByProductCodeAsc(String storeCode);

    boolean existsByStoreCodeAndProductCode(String storeCode, String productCode);

    boolean existsByStoreCodeAndName(String storeCode, String name);

    boolean existsByStoreCodeAndNameAndIdNot(String storeCode, String name, Long id);
}
