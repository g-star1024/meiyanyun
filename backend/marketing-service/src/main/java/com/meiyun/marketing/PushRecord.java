package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/** 推送触达记录（周频限 3 条的审计依据）。 */
@Entity
@Table(name = "push_record")
@Getter @Setter @NoArgsConstructor
public class PushRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "push_id")
    private Long pushId;

    @Column(name = "customer_id", nullable = false, length = 16)
    private String customerId;

    // length=16：承载 SMS/WECOM/WECHAT_MP 三渠道英文码（WECHAT_MP 为 9 字符，旧表 varchar(8) 落库会超长）
    @Column(name = "push_type", nullable = false, length = 16)
    private String pushType;

    @Column(nullable = false, length = 256)
    private String content;

    /**
     * 幂等键：客户+渠道+内容的 SHA-256 前 16 位，60 秒窗口内重复提交直接返回已落库记录
     * （防双击/重试导致的重复触达）；由 PushSchemaInitializer 补列 + 唯一索引自愈。
     */
    @Column(name = "dedup_key", length = 32)
    private String dedupKey;

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;

    // ---- 棒⑧卡2 外发腿四列（V88；历史行 NULL = 外发腿上线前纯落库，不追标） ----
    /** 外发状态：SENT/FAILED/DEAD/SKIPPED；NULL=纯落库历史行。 */
    @Column(name = "status", length = 16)
    private String status;

    /** 通道回执 ID（短信 BizId 等）。 */
    @Column(name = "channel_msg_id", length = 64)
    private String channelMsgId;

    /** 失败/跳过原因（诚实口径，不伪造成功）。 */
    @Column(name = "error", length = 512)
    private String error;

    /** 外发成功时间（仅 SENT 写）。 */
    @Column(name = "delivered_at")
    private OffsetDateTime deliveredAt;
}
