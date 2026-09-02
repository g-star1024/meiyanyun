package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 员工：岗位互斥与双签校验的 personId 来源。 */
@Entity
@Table(name = "staff")
@Getter @Setter @NoArgsConstructor
public class Staff {

    @Id
    @Column(name = "staff_id", length = 16)
    private String staffId;

    @Column(name = "staff_name", nullable = false, length = 32)
    private String staffName;

    @Column(name = "role_code", nullable = false, length = 24)
    private String roleCode;     // 主角色（一人多角色见 staff_role 关联表）

    @Column(name = "store_code", length = 16)
    private String storeCode;    // 区域/集团角色可为空

    /** 所属大区（华东/华北…）；区域经理有值，门店角色可空。REGION 数据域过滤依据。 */
    @Column(name = "region", length = 16)
    private String region;

    @Column(name = "medical_licensed", nullable = false)
    private boolean medicalLicensed;

    @Column(nullable = false, length = 8)
    private String status;       // 在职 | 离职

    /** 登录账号（默认同工号，唯一）；M7 登录态载体。 */
    @Column(name = "login_name", length = 32)
    private String loginName;

    /** PBKDF2 口令哈希（pbkdf2$iters$salt$hash）；不随 JSON 序列化外发。 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @Column(name = "password_hash", length = 128)
    private String passwordHash;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /** 关联角色（只读展示用，避免 N+1 由 Controller 批量组装）。 */
    @Transient
    private RoleDef role;
}
