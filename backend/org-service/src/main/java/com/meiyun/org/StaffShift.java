package com.meiyun.org;

import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 员工班次（周排班网格的真源）：一人一天一行，(staff_id, shift_date) 唯一。
 *
 * <p>全员排班（不限医生/角色）；房间/设备不排班，按营业窗处理。班次五态：
 * MORNING 上午 / MID 下午 / FULL 全天 / OFF 休息 / LEAVE 请假，其中 OFF/LEAVE 不可派单。
 * source=TEMPLATE 由周例生成批量铺底，OVERRIDE 为单日手工调整或请假联动覆盖。
 */
@Entity
@Table(name = "staff_shift", uniqueConstraints =
        @UniqueConstraint(name = "uk_staff_shift_date", columnNames = {"staff_id", "shift_date"}))
@Getter @Setter @NoArgsConstructor
public class StaffShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "staff_id", nullable = false, length = 16)
    private String staffId;

    @Column(name = "shift_date", nullable = false)
    @JsonFormat(pattern = "yyyy-MM-dd")
    private LocalDate shiftDate;

    /** 班次码：MORNING | MID | FULL | OFF | LEAVE。 */
    @Column(name = "shift_code", nullable = false, length = 8)
    private String shiftCode;

    /** 来源：TEMPLATE（周例铺底）| OVERRIDE（单日手工/请假联动）。 */
    @Column(nullable = false, length = 8)
    private String source;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
