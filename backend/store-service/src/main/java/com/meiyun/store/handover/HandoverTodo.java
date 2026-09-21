package com.meiyun.store.handover;

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
@Table(name = "handover_todo",
        indexes = @Index(name = "idx_ht_ho", columnList = "ho_id"))
@Getter
@Setter
@NoArgsConstructor
public class HandoverTodo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ho_id", nullable = false)
    private Long hoId;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "content", nullable = false, length = 128)
    private String content;

    @Column(name = "urgent", nullable = false)
    private Boolean urgent = false;

    @Column(name = "done", nullable = false)
    private Boolean done = false;

    @jakarta.persistence.PrePersist
    void prePersist() {
        if (urgent == null) urgent = false;
        if (done == null) done = false;
    }
}
