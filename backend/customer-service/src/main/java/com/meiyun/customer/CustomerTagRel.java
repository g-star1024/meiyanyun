package com.meiyun.customer;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/**
 * 客户-标签关联（复合主键：customer_id + tag_id）。
 */
@Entity
@Table(name = "customer_tag_rel")
@IdClass(CustomerTagRel.Key.class)
@Getter @Setter @NoArgsConstructor
public class CustomerTagRel {

    @Id
    @Column(name = "customer_id", length = 16)
    private String customerId;

    @Id
    @Column(name = "tag_id", length = 16)
    private String tagId;

    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private String customerId;
        private String tagId;
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key)) return false;
            Key k = (Key) o;
            return Objects.equals(customerId, k.customerId) && Objects.equals(tagId, k.tagId);
        }
        @Override public int hashCode() { return Objects.hash(customerId, tagId); }
    }
}
