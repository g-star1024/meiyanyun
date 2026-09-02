package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerTagRelRepository extends JpaRepository<CustomerTagRel, CustomerTagRel.Key> {
    List<CustomerTagRel> findByCustomerId(String customerId);
    List<CustomerTagRel> findByCustomerIdIn(List<String> customerIds);
}
