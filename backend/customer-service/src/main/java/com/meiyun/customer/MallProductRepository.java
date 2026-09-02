package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MallProductRepository extends JpaRepository<MallProduct, String> {

    List<MallProduct> findAllByOrderByCreatedAtDesc();

    List<MallProduct> findByStatusOrderByPointsPriceAsc(String status);
}
