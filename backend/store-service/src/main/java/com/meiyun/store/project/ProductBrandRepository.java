package com.meiyun.store.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductBrandRepository extends JpaRepository<ProductBrand, Long> {

    Optional<ProductBrand> findByBrandCode(String brandCode);

    List<ProductBrand> findAllByOrderByBrandCode();
}
