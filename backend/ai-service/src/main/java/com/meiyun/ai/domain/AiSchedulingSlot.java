package com.meiyun.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

@Entity
@Table(name = "ai_scheduling_slot")
@Getter
@Setter
@NoArgsConstructor
public class AiSchedulingSlot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "slot_id")
    private Long slotId;

    @Column(name = "plan_id", nullable = false)
    private Long planId;

    /** 0=周一 … 6=周日 */
    @Column(name = "day_index", nullable = false)
    private Integer dayIndex;

    /** MORNING / MID / EVENING */
    @Column(name = "shift_code", nullable = false)
    private String shiftCode;

    /** 缺口槽为空串 */
    @Column(name = "staff_id", nullable = false)
    private String staffId = "";

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "role_code")
    private String roleCode;

    @Column(name = "role_name")
    private String roleName;

    @Column(name = "hours", nullable = false)
    private Integer hours = 6;

    @Column(name = "cost_fen", nullable = false)
    private Long costFen = 0L;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
