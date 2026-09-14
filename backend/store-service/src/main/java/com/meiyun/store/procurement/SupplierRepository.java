package com.meiyun.store.procurement;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** 供应商档案仓库（集团级全局） */
public interface SupplierRepository extends JpaRepository<Supplier, Long> {

    Optional<Supplier> findByCode(String code);
}
