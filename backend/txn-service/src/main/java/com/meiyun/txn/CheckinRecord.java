package com.meiyun.txn;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * M2-09 会员到店核销登记单：扫码核销 / 预约到店 / 直接到店三类到店方式的一行。
 *
 * <p>登记时按手机号经 customer 内部端点反查锚定客户：命中本店客户或公海客户则落 customer_id；
 * 均不命中允许以「快照散客」建单（customer_id 为空，姓名/掩码手机号/项目为登记时快照）。
 *
 * <p>状态机：PENDING(待确认) → DONE(已核销)；PENDING → EXCEPTION(异常) → PENDING(解除)。
 * DONE 不可标异常、不可解除（与前端 mock 口径一致；EXCEPTION 允许改标其他异常原因）。
 * timeline 为 JSON 字符串数组（[{"by":"E005","text":"…","at":"…"}]），时间正序追加。
 */
@Entity
@Table(name = "checkin_record")
@Getter @Setter @NoArgsConstructor
public class CheckinRecord {

    @Id
    @Column(name = "ci_no", length = 24)
    private String ciNo;

    @Column(name = "store_code", nullable = false, length = 16)
    private String storeCode;

    /** 锚定客户编号；手机号未命中本店/公海客户时为空（快照散客）。 */
    @Column(name = "customer_id", length = 16)
    private String customerId;

    /** 客户姓名快照（散客为登记手填；锚定客户读模型以客户域姓名为准富化）。 */
    @Column(name = "customer_name", nullable = false, length = 32)
    private String customerName;

    /** 掩码手机号快照（138****2046），明文不落库。 */
    @Column(nullable = false, length = 16)
    private String phone;

    @Column(nullable = false, length = 64)
    private String project;

    /** 到店方式：SCAN(扫码核销) / APPOINTMENT(预约到店) / WALKIN(直接到店)。 */
    @Column(nullable = false, length = 16)
    private String method;

    /** 单据状态：PENDING / DONE / EXCEPTION。 */
    @Column(nullable = false, length = 16)
    private String status;

    /** 异常原因：NONE / NOT_SELF / ALREADY_DONE / NO_APPOINTMENT / INFO_MISMATCH。 */
    @Column(name = "exception_reason", nullable = false, length = 32)
    private String exceptionReason = "NONE";

    /** 异常补充说明。 */
    @Column(length = 256)
    private String note;

    /** 到店时间（列表排序与展示用）。 */
    @Column(name = "arrived_at", nullable = false)
    private OffsetDateTime arrivedAt;

    @Column(name = "checked_at")
    private OffsetDateTime checkedAt;

    /** 登记/操作人工号（JWT 登录人，不可信入参一律忽略）。 */
    @Column(nullable = false, length = 32)
    private String operator;

    /** 时间线 JSON 字符串数组。 */
    @Column(name = "timeline", length = 8192, nullable = false)
    private String timeline;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (arrivedAt == null) arrivedAt = OffsetDateTime.now();
        if (status == null) status = "PENDING";
        if (exceptionReason == null) exceptionReason = "NONE";
        if (timeline == null) timeline = "[]";
    }
}
