package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FinSubjectEnableRepository extends JpaRepository<FinSubjectEnable, Long> {

    List<FinSubjectEnable> findAllByOrderByEnableIdAsc();

    Optional<FinSubjectEnable> findByCode(String code);
}
