package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** RBAC 角色矩阵（数据字典 §1.5）：data_scope 门店/区域/集团 三级数据权限。 */
@Entity
@Table(name = "role_def")
@Getter @Setter @NoArgsConstructor
public class RoleDef {

    @Id
    @Column(name = "role_code", length = 24)
    private String roleCode;

    @Column(name = "role_name", nullable = false, length = 32)
    private String roleName;

    @Column(name = "data_scope", nullable = false, length = 8)
    private String dataScope;    // 门店 | 区域 | 集团

    @Column(name = "role_sequence", nullable = false, length = 16)
    private String roleSequence; // 岗位序列（双签「不同岗位序列」校验用）

    @Column(nullable = false)
    private Boolean medical;     // 是否要求医疗执业资质

    @Column(length = 128)
    private String description;

    /** 状态：启用 | 停用（停用角色不可再授予员工；内置矩阵角色恒启用，不可停用）。 */
    @Column(length = 8)
    private String status;
}
