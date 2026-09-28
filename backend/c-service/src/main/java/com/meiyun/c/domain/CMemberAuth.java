package com.meiyun.c.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * C 端会员认证主体（V74 c_member_auth，C 端「独立会员体系」唯一新表）。
 * openid 全局唯一；customer_id 逻辑引用 B 端 customer 表业务主键（VARCHAR(16)，零物理 FK，照 V63-V66 先例）。
 */
@Getter
@Setter
@Entity
@Table(name = "c_member_auth")
public class CMemberAuth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 微信 openid（真实登录）或 dev_<手机号>（开发期快捷登录占位），全局唯一 */
    @Column(name = "openid", nullable = false, length = 64, unique = true)
    private String openid;

    @Column(name = "unionid", length = 64)
    private String unionid;

    /** 逻辑引用 B 端 customer.customer_id（VARCHAR(16) 业务编号，C-B3 绑定后回填） */
    @Column(name = "customer_id", length = 16)
    private String customerId;

    @Column(name = "phone", length = 20)
    private String phone;

    @Column(name = "nickname", length = 64)
    private String nickname;

    @Column(name = "avatar", length = 256)
    private String avatar;

    /** ACTIVE / DISABLED（B 端停用时联动，C-B3 起生效） */
    @Column(name = "status", nullable = false, length = 16)
    private String status = "ACTIVE";

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
