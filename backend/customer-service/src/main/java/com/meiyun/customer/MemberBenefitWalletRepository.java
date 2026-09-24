package com.meiyun.customer;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MemberBenefitWalletRepository extends JpaRepository<MemberBenefitWallet, Long> {

    /** 懒发放/核销找当期钱包（UK 自然键）。 */
    Optional<MemberBenefitWallet> findByCustomerIdAndPeriodAndBenefitType(
            String customerId, String period, String benefitType);

    /**
     * 核销扣次同事务行锁找钱包：SELECT ... FOR UPDATE，
     * 并发核销串行化，防 used_times 更新丢失（仿 MemberCardRepository.findForUpdate）。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from MemberBenefitWallet w where w.walletId = :walletId")
    Optional<MemberBenefitWallet> findForUpdate(@Param("walletId") Long walletId);

    /** 360 读模型：客户钱包按周期倒序（当期在前）。 */
    List<MemberBenefitWallet> findByCustomerIdOrderByPeriodDesc(String customerId);
}
