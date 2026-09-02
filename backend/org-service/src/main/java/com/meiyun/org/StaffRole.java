package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/** 员工-角色关联：一人可多角色（权限取并集、数据域取最大）；staff.role_code 为主角色。 */
@Entity
@Table(name = "staff_role")
@IdClass(StaffRole.Key.class)
@Getter @Setter @NoArgsConstructor
public class StaffRole {

    @Id
    @Column(name = "staff_id", length = 16)
    private String staffId;

    @Id
    @Column(name = "role_code", length = 24)
    private String roleCode;

    public StaffRole(String staffId, String roleCode) {
        this.staffId = staffId;
        this.roleCode = roleCode;
    }

    /** 联合主键。 */
    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private String staffId;
        private String roleCode;

        public Key(String staffId, String roleCode) {
            this.staffId = staffId;
            this.roleCode = roleCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(staffId, key.staffId) && Objects.equals(roleCode, key.roleCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(staffId, roleCode);
        }
    }
}
