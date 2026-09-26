package com.meiyun.store.requisition;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "requisition_item",
        indexes = @Index(name = "idx_rq_item_rq", columnList = "rq_id"))
@Getter
@Setter
@NoArgsConstructor
public class RequisitionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rq_id", nullable = false)
    private Long rqId;

    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "spec", length = 32)
    private String spec;

    @Column(name = "qty", nullable = false)
    private int qty;

    @Column(name = "unit", nullable = false, length = 8)
    private String unit;

    @Column(name = "sku_code", length = 32)
    private String skuCode;
}
