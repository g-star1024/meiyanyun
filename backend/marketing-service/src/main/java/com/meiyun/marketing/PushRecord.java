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

    @Column(name = "sent_at", nullable = false)
    private OffsetDateTime sentAt;
}
