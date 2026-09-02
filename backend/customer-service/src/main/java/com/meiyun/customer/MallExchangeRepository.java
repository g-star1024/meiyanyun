package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MallExchangeRepository extends JpaRepository<MallExchange, String> {

    List<MallExchange> findAllByOrderByCreatedAtDesc();

    List<MallExchange> findByStatusOrderByCreatedAtDesc(String status);

    List<MallExchange> findByCustomerIdOrderByCreatedAtDesc(String customerId);
}
