package com.meiyun.store.daily;

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
@Table(name = "daily_todo",
        indexes = @Index(name = "idx_dt_dr", columnList = "dr_id"))
@Getter
@Setter
@NoArgsConstructor
public class DailyTodo {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "dr_id", nullable = false)
    private Long drId;

    @Column(name = "content", nullable = false, length = 128)
    private String content;

    @Column(name = "kind", nullable = false, length = 16)
    private String kind;

    @Column(name = "done", nullable = false)
    private Boolean done = false;

    @Column(name = "urgent", nullable = false)
    private Boolean urgent = false;
}
