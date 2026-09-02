package com.meiyun.finance;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/** 税务四税种（增值/城建/教育费附加/地方教育附加），全口径 542,450。 */
@Entity
@Table(name = "tax")
@Getter @Setter @NoArgsConstructor
public class Tax {

    @Id
    @Column(length = 16)
    private String cat;

    @Column(nullable = false)
    private Long base;

    @Column(nullable = false, precision = 5, scale = 4)
    private BigDecimal rate;

    @Column(nullable = false)
    private Long amount;
}
