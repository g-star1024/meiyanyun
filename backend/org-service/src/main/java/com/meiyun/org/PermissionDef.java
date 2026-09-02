package com.meiyun.org;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** 权限字典：资源-动作权限码定义（RBAC 唯一真源，启动幂等播种）。 */
@Entity
@Table(name = "permission_def")
@Getter @Setter @NoArgsConstructor
public class PermissionDef {

    /** 权限码，如 cashier:sign / customer:phone:decrypt。 */
    @Id
    @Column(name = "permission_code", length = 64)
    private String permissionCode;

    /** 资源段，如 cashier / customer / finance。 */
    @Column(name = "resource_code", nullable = false, length = 48)
    private String resourceCode;

    /** 动作段，如 view / create / sign / phone:decrypt。 */
    @Column(name = "action_code", nullable = false, length = 32)
    private String actionCode;

    @Column(length = 128)
    private String description;

    public PermissionDef(String permissionCode) {
        this.permissionCode = permissionCode;
        int first = permissionCode.indexOf(':');
        this.resourceCode = first > 0 ? permissionCode.substring(0, first) : permissionCode;
        this.actionCode = first > 0 ? permissionCode.substring(first + 1) : "view";
    }
}
