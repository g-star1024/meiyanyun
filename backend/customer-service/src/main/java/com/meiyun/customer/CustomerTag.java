package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_tag")
@Getter @Setter @NoArgsConstructor
public class CustomerTag {

    @Id
    @Column(name = "tag_id", length = 16)
    private String tagId;

    @Column(name = "tag_name", nullable = false, length = 32)
    private String tagName;

    @Column(nullable = false, length = 16)
    private String category;                // 消费/肤质/行为/价值/医疗
}
