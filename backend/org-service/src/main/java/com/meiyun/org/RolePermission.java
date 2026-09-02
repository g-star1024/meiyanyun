package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/** 角色-权限关联：某角色拥有某权限码（多对多落库）。 */
@Entity
@Table(name = "role_permission")
@IdClass(RolePermission.Key.class)
@Getter @Setter @NoArgsConstructor
public class RolePermission {

    @Id
    @Column(name = "role_code", length = 24)
    private String roleCode;

    @Id
    @Column(name = "permission_code", length = 64)
    private String permissionCode;

    public RolePermission(String roleCode, String permissionCode) {
        this.roleCode = roleCode;
        this.permissionCode = permissionCode;
    }

    /** 联合主键。 */
    @Getter @Setter @NoArgsConstructor
    public static class Key implements Serializable {
        private String roleCode;
        private String permissionCode;

        public Key(String roleCode, String permissionCode) {
            this.roleCode = roleCode;
            this.permissionCode = permissionCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Key key)) return false;
            return Objects.equals(roleCode, key.roleCode) && Objects.equals(permissionCode, key.permissionCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(roleCode, permissionCode);
        }
    }
}
