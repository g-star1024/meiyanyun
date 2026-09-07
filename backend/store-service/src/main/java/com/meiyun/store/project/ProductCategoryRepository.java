package com.meiyun.store.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {

    Optional<ProductCategory> findByCategoryCode(String categoryCode);

    List<ProductCategory> findByBrandIdOrderBySortAscIdAsc(Long brandId);

    List<ProductCategory> findAllByOrderByCategoryCode();

    List<ProductCategory> findByParentId(Long parentId);

    long countByBrandId(Long brandId);
}
