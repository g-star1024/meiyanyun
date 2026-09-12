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
@Table(name = "ai_provider")
@Getter
@Setter
@NoArgsConstructor
public class AiProvider {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "provider_id")
    private Long providerId;

    @Column(name = "provider_code", nullable = false, unique = true)
    private String providerCode;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    /** AES-GCM 密文 Base64；禁日志、禁回显。 */
    @Column(name = "api_key_cipher")
    private String apiKeyCipher;

    @Column(name = "api_key_mask")
    private String apiKeyMask;

    @Column(name = "protocol", nullable = false)
    private String protocol = "OPENAI";

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = false;

    @Column(name = "updated_by")
    private String updatedBy;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
