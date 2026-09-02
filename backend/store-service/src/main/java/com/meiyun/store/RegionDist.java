package com.meiyun.store;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 六大区分布（DDL §1，ID-2：Σ open=18 / Σ own=8 / Σ total=23）。
 */
@Entity
@Table(name = "region_dist")
@Getter
@Setter
@NoArgsConstructor
public class RegionDist {

    @Id
    @Column(length = 8)
    private String region;

    @Column(name = "open_cnt", nullable = false)
    private Integer openCnt;     // 营业中

    @Column(name = "own_cnt", nullable = false)
    private Integer ownCnt;      // 直营

    @Column(name = "joint_cnt", nullable = false)
    private Integer jointCnt;    // 联营

    @Column(name = "building_cnt", nullable = false)
    private Integer buildingCnt; // 筹建中

    @Column(name = "closed_cnt", nullable = false)
    private Integer closedCnt;   // 已关店

    @Column(nullable = false)
    private Integer total;       // 全口径
}
