package com.meiyun.store.project;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;
import java.util.Optional;

public interface ProductSkuRepository extends JpaRepository<ProductSku, Long>,
        JpaSpecificationExecutor<ProductSku> {

    Optional<ProductSku> findBySku(String sku);

    List<ProductSku> findByBrandIdOrderBySku(Long brandId);

    List<ProductSku> findByCategoryId(Long categoryId);

    long countByBrandId(Long brandId);

    long countByBrandIdAndStatus(Long brandId, String status);
}
