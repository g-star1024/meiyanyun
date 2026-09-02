package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface CustomerRepository extends JpaRepository<Customer, String>, JpaSpecificationExecutor<Customer> {
    List<Customer> findByStoreCode(String storeCode);
    List<Customer> findByLevel(String level);
    List<Customer> findByStatus(String status);
    List<Customer> findByLevelAndStoreCode(String level, String storeCode);
}
