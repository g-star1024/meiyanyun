package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/** 员工-角色关联：一人可多角色（权限取并集、数据域取最大）；staff.role_code 为主角色。
 *  兼岗范围 org_code：''=全局（通吃所有大区/门店），否则挂 org_unit 节点码（大区/门店节点）。 */
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

    @Id
    @Column(name = "org_code", length = 16)
    private String orgCode = "";

    public StaffRole(String staffId, String roleCode) {
        this(staffId, roleCode, "");
    }

    public StaffRole(String staffId, String roleCode, String orgCode) {
        this.staffId = staffId;
        this.roleCode = roleCode;
        this.orgCode = orgCode == null ? "" : orgCode;
    }

    /** 联合主键（员工＋角色＋兼岗范围）。 */
    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private String staffId;
        private String roleCode;
        private String orgCode = "";

        public Key(String staffId, String roleCode) {
            this(staffId, roleCode, "");
        }

        public Key(String staffId, String roleCode, String orgCode) {
            this.staffId = staffId;
            this.roleCode = roleCode;
            this.orgCode = orgCode == null ? "" : orgCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(staffId, key.staffId) && Objects.equals(roleCode, key.roleCode)
                    && Objects.equals(orgCode, key.orgCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(staffId, roleCode, orgCode);
        }
    }
}
