package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 核销链三段（扫码 842 笔：正常 774 + 异常 9 + 待处理 59）。 */
@Entity
@Table(name = "coupon_writeoff_chain")
@Getter @Setter @NoArgsConstructor
public class CouponWriteoffChain {

    @Id
    @Column(name = "chain_id", length = 24)
    private String chainId;

    @Column(nullable = false, length = 8)
    private String segment;

    @Column(nullable = false)
    private Integer cnt;

    @Column(nullable = false, length = 8)
    private String period;
}
