package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 组织单元：集团 → 区域 → 门店 → 部门 四级树（部门为门店下的可写扩展层，B33）。 */
@Entity
@Table(name = "org_unit")
@Getter @Setter @NoArgsConstructor
public class OrgUnit {

    @Id
    @Column(name = "org_code", length = 16)
    private String orgCode;

    @Column(name = "org_name", nullable = false, length = 64)
    private String orgName;

    @Column(name = "org_type", nullable = false, length = 8)
    private String orgType;      // 集团 | 区域 | 门店 | 部门

    @Column(name = "parent_code", length = 16)
    private String parentCode;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(length = 8)
    private String region;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "leader_name", length = 32)
    private String leaderName;

    @Column
    private Integer headcount;

    /** 启用 | 停用；存量三级节点由应用侧归一为启用。 */
    @Column(length = 8)
    private String status;

    @Column(name = "inactive_reason", length = 255)
    private String inactiveReason;

    @Column(length = 255)
    private String remark;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;
}
