package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface MemberCardRepository extends JpaRepository<MemberCard, String>, JpaSpecificationExecutor<MemberCard> {
    List<MemberCard> findByCustomerIdOrderByCreatedAtDesc(String customerId);

    List<MemberCard> findByStoreCodeOrderByCreatedAtDesc(String storeCode);

    List<MemberCard> findAllByOrderByCreatedAtDesc();
}
