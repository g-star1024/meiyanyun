package com.meiyun.store.reactivate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reactivate_log",
        indexes = @Index(name = "idx_rc_log_rc", columnList = "rc_id"))
@Getter
@Setter
@NoArgsConstructor
public class ReactivateLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rc_id", nullable = false)
    private Long rcId;

    @Column(name = "action_by", nullable = false, length = 64)
    private String actionBy;

    @Column(name = "action_at", nullable = false)
    private OffsetDateTime actionAt;

    @Column(name = "action", nullable = false, length = 128)
    private String action;

    @Column(name = "channel", length = 8)
    private String channel;

    @Column(name = "result", length = 255)
    private String result;
}
