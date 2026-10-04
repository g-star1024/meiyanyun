package com.meiyun.customer;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CustomerTagRepository extends JpaRepository<CustomerTag, String> {

    boolean existsByTagName(String tagName);

    Optional<CustomerTag> findByTagName(String tagName);

    /** 库内 TG### 最大编号（定长 3 位序号，字符串 max 与数值序一致；STG 种子标签不参与取号）。 */
    @Query("select max(t.tagId) from CustomerTag t where t.tagId like 'TG%'")
    String maxTgId();
}
