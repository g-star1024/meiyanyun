package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberCardRepository extends JpaRepository<MemberCard, String> {
    List<MemberCard> findByCustomerId(String customerId);
    List<MemberCard> findByCustomerIdAndStatus(String customerId, String status);
}
