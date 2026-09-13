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
@Table(name = "ai_chat_message")
@Getter
@Setter
@NoArgsConstructor
public class AiChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "message_id")
    private Long messageId;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    /** customer 顾客 / ai AI 回复 / staff 人工座席 */
    @Column(name = "sender", nullable = false)
    private String sender = "customer";

    @Column(name = "content", nullable = false)
    private String content = "";

    @Column(name = "invoke_log_id")
    private Long invokeLogId;

    @Column(name = "model_code")
    private String modelCode;

    @Column(name = "total_tokens")
    private Integer totalTokens;

    /** 费用，单位：分 */
    @Column(name = "cost_fen", nullable = false)
    private Long costFen = 0L;

    @Column(name = "latency_ms")
    private Long latencyMs;

    @Column(name = "staff_id")
    private String staffId;

    @Column(name = "staff_name")
    private String staffName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
