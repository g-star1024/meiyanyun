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
@Table(name = "ai_chat_session")
@Getter
@Setter
@NoArgsConstructor
public class AiChatSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "session_id")
    private Long sessionId;

    /** 业务编号 CS+yyyyMMdd+4 位序列 */
    @Column(name = "session_no", nullable = false)
    private String sessionNo;

    @Column(name = "customer_name", nullable = false)
    private String customerName = "";

    /** ai=AI 接待 / human=人工接待 */
    @Column(name = "channel", nullable = false)
    private String channel = "ai";

    @Column(name = "last_message", nullable = false)
    private String lastMessage = "";

    /** customer / ai / staff */
    @Column(name = "last_sender", nullable = false)
    private String lastSender = "customer";

    @Column(name = "unread_count", nullable = false)
    private Integer unreadCount = 0;

    @Column(name = "transferred", nullable = false)
    private Boolean transferred = false;

    @Column(name = "transferred_at")
    private OffsetDateTime transferredAt;

    @Column(name = "transferred_by")
    private String transferredBy;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "store_code", nullable = false)
    private String storeCode = "";

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private OffsetDateTime updatedAt;
}
