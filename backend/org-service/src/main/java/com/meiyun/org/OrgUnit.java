package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 组织单元：集团 → 六大区 → 23 门店 三级树。 */
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
    private String orgType;      // 集团 | 区域 | 门店

    @Column(name = "parent_code", length = 16)
    private String parentCode;

    @Column(name = "store_code", length = 16)
    private String storeCode;

    @Column(length = 8)
    private String region;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;
}
