package com.meiyun.finance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FinBudgetRepository extends JpaRepository<FinBudget, Long> {

    List<FinBudget> findByBudgetYearOrderByBudgetIdAsc(Integer budgetYear);

    Optional<FinBudget> findByBudgetYearAndSubjectCode(Integer budgetYear, String subjectCode);
}
