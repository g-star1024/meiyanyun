package com.meiyun.store.inspection;

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
@Table(name = "inspection_item",
        indexes = @Index(name = "idx_ins_item_ins", columnList = "ins_id"))
@Getter
@Setter
@NoArgsConstructor
public class InspectionItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ins_id", nullable = false)
    private Long insId;

    @Column(name = "name", nullable = false, length = 64)
    private String name;

    @Column(name = "score", nullable = false)
    private Integer score = 0;

    @Column(name = "note", length = 255)
    private String note;
}
