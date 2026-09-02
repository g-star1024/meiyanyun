package com.meiyun.store;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 门店主数据（DDL §1）。三层口径：全23 = 营业18 + 筹建3 + 关店2；营业18 = 直营8 + 联营10。
 * 废止常量：29 / 108 禁止作为门店数（142 从未作为门店数，C-01 关闭，不建工位维度）。
 */
@Entity
@Table(name = "store")
@Getter
@Setter
@NoArgsConstructor
public class Store {

    @Id
    @Column(name = "store_code", length = 16)
    private String storeCode;          // ST-SH-001

    @Column(name = "store_name", nullable = false, length = 64)
    private String storeName;

    @Column(nullable = false, length = 8)
    private String region;             // 华东/华南/华北/华中/西南/西北

    @Column(nullable = false, length = 4)
    private String nature;             // 直营/联营

    @Column(nullable = false, length = 8)
    private String status;             // 营业中/筹建中/已关店

    @Column(name = "open_date")
    private java.time.LocalDate openDate;
}
