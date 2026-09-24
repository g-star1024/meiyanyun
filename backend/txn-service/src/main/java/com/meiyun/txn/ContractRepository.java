package com.meiyun.txn;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ContractRepository extends JpaRepository<Contract, String>, JpaSpecificationExecutor<Contract> {

    /** 当日合同号最大序号（contract_no 形如 HT20260925-000001，序号从第 12 位起 6 位，仿 TxnRefundRepository 先例）。 */
    @Query(value = "select coalesce(max(cast(substring(contract_no from 12) as bigint)), 0) " +
           "from contract where contract_no like :prefix", nativeQuery = true)
    long maxSeqOfDay(@Param("prefix") String prefix);
}
