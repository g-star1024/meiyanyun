package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 双签工单：耗材领用 / 报损 / 现金交接（5 类补齐，不看金额一律双签）。
 * 退款 / 退卡 双签沿用 txn_refund / txn_card_cancel 原流程。
 */
@Entity
@Table(name = "dual_sign_ticket")
@Getter @Setter @NoArgsConstructor
public class DualSignTicket {

    @Id
    @Column(name = "ticket_no", length = 24)
    private String ticketNo;

    @Column(name = "biz_type", nullable = false, length = 8)
    private String bizType;            // 耗材领用 | 报损 | 现金交接

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    @Column(nullable = false, length = 128)
    private String title;

    @Column(nullable = false)
    private Long amount = 0L;          // 分；不看金额也双签

    @Column(nullable = false, length = 8)
    private String status = "待签核";

    @Column(length = 32)
    private String sign1;
    @Column(name = "sign1_role", length = 32)
    private String sign1Role;
    @Column(name = "signed_at1")
    private OffsetDateTime signedAt1;

    @Column(length = 32)
    private String sign2;
    @Column(name = "sign2_role", length = 32)
    private String sign2Role;
    @Column(name = "signed_at2")
    private OffsetDateTime signedAt2;

    @Column(length = 256)
    private String note;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
