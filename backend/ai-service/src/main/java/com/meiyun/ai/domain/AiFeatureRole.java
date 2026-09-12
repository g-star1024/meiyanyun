package com.meiyun.ai.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;

@Entity
@Table(name = "ai_feature_role")
@IdClass(AiFeatureRole.PK.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AiFeatureRole {

    @Id
    @Column(name = "feature_code")
    private String featureCode;

    @Id
    @Column(name = "role_code")
    private String roleCode;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;

    public static class PK implements Serializable {
        private String featureCode;
        private String roleCode;

        public PK() {
        }

        public String getFeatureCode() {
            return featureCode;
        }

        public void setFeatureCode(String featureCode) {
            this.featureCode = featureCode;
        }

        public String getRoleCode() {
            return roleCode;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(featureCode, pk.featureCode) && Objects.equals(roleCode, pk.roleCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(featureCode, roleCode);
        }
    }
}
