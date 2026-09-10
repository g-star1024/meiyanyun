package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 排队智能候补名单（P5-B29 卡①）：满约/无号时登记候补，号源释放后自动递补通知。
 *
 * <p>状态机：
 * <pre>
 *   WAITING（候补中）→ NOTIFIED（已递补通知，待客户到场）→ FULFILLED（已到场转正式到店登记）
 *   WAITING / NOTIFIED → CANCELLED（取消候补）
 * </pre>
 * 客户可锚定 customer_id（按手机号反查客户目录自动锚定，或显式传入）；未命中允许散客快照
 * （姓名/掩码手机/项目为登记快照），但 FULFILLED 前必须先建档锚定——正式到店登记强制客户存在。
 * timeline 为 JSON 字符串数组（[{"by":"E005","text":"…","at":"…"}]），时间正序追加。
 */
@Entity
@Table(name = "arrival_waitlist", indexes = {
        @Index(name = "idx_waitlist_store_status", columnList = "store_code,status,created_at")
})
@Getter @Setter @NoArgsConstructor
public class ArrivalWaitlist {

    @Id
    @Column(name = "wl_no", length = 24)
    private String wlNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 锚定客户编号；手机号未命中本店/公海客户时为空（散客快照，fulfill 前须补锚定）。 */
    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名快照（锚定客户读模型以客户域姓名为准富化）。 */
    @Column(name = "customer_name", nullable = false, length = 32)
    private String customerName;

    /** 掩码手机号快照（138****2046），明文不落库；同时为散客幂等键。 */
    @Column(nullable = false, length = 16)
    private String phone;

    @Column(nullable = false, length = 64)
    private String project;

    /** 期望到店日期（可空，空=尽快/当日）。 */
    @Column(name = "expect_date")
    private LocalDate expectDate;

    /** 状态：WAITING / NOTIFIED / FULFILLED / CANCELLED。 */
    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "notified_at")
    private OffsetDateTime notifiedAt;

    /** fulfill 成功后生成的正式到店登记号（AH），用于勾连追溯。 */
    @Column(name = "ah_no", length = 24)
    private String ahNo;

    /** 登记/操作人工号（JWT 登录人，不信入参）。 */
    @Column(nullable = false, length = 32)
    private String operator;

    @Column(length = 256)
    private String note;

    @Column(name = "timeline", length = 8192, nullable = false)
    private String timeline;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (status == null) status = "WAITING";
        if (timeline == null) timeline = "[]";
    }
}
