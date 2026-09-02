package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 预收沉淀池（940万 = 待核销 658 + 可退 188 + 待结转 94，恒等式由 DB CHECK 约束）。 */
@Entity
@Table(name = "prepay_pool")
@Getter @Setter @NoArgsConstructor
public class PrepayPool {

    @Id
    @Column(name = "pool_id")
    private Integer poolId;

    @Column(nullable = false)
    private Long total;

    @Column(name = "pending_consume", nullable = false)
    private Long pendingConsume;   // 待核销 658万

    @Column(nullable = false)
    private Long refundable;       // 可退 188万

    @Column(name = "earned_pending", nullable = false)
    private Long earnedPending;    // 待结转 94万
}
