package com.meiyun.customer;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface CustomerRepository extends JpaRepository<Customer, String>, JpaSpecificationExecutor<Customer> {
    List<Customer> findByStoreCode(String storeCode);
    List<Customer> findByLevel(String level);
    List<Customer> findByStatus(String status);
    List<Customer> findByLevelAndStoreCode(String level, String storeCode);

    /** 取号：正式库客户编号 M+3 位序号，查当前最大客户号（防重号，禁内存自增）；库为空返回 null。 */
    @Query("select max(c.customerId) from Customer c where c.customerId like 'M%'")
    String maxMId();

    /** 撞单识别：同门店同手机号视为同一客户（门店内手机号唯一语义）。 */
    Optional<Customer> findFirstByStoreCodeAndPhone(String storeCode, String phone);

    /** 撞单识别：无门店（公海）场景按手机号全局查重。 */
    Optional<Customer> findFirstByStoreCodeIsNullAndPhone(String phone);
}
