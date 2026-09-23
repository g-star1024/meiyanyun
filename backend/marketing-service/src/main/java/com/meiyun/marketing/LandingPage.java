package com.meiyun.marketing;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;

/**
 * 落地页（P5-B88）。
 *
 * 状态机（英文码落库，前端经 label 映射中文）：
 * DRAFT 草稿 → PUBLISHED 已发布 → OFFLINE 已下线；OFFLINE 可再发布回 PUBLISHED。
 * template 五类：NEWBIE 新客体验 / PROJECT 项目种草 / FESTIVAL 节日促销 / MEMBER 会员日 / BRAND 品牌宣传。
 * form_fields / blocks / variants 为 JSON 数组文本；visits/leads v1 为种子演示数，真实采集埋点留 v2（D4-A）。
 */
@Entity
@Table(name = "landing_page")
@Getter @Setter @NoArgsConstructor
public class LandingPage {

    @Id
    @Column(name = "page_id", length = 24)
    private String pageId;

    @Column(name = "page_name", nullable = false, length = 64)
    private String pageName;

    /** NEWBIE / PROJECT / FESTIVAL / MEMBER / BRAND。 */
    @Column(nullable = false, length = 16)
    private String template;

    /** DRAFT / PUBLISHED / OFFLINE。 */
    @Column(nullable = false, length = 10)
    private String status;

    @Column(length = 128)
    private String headline;

    @Column(length = 256)
    private String subtitle;

    @Column(length = 64)
    private String project;

    /** 表单字段 JSON 数组文本，如 ["姓名","手机","意向项目"]。 */
    @Column(name = "form_fields")
    private String formFields;

    /** 可视化组件块 JSON 数组文本：[{"id","type","label"}]，顺序即页面结构。 */
    @Column
    private String blocks;

    /** 访问量（v1 种子演示数，采集留 v2）。 */
    @Column(nullable = false)
    private Long visits;

    /** 留资量（v1 种子演示数，采集留 v2）。 */
    @Column(nullable = false)
    private Long leads;

    /** A/B 测试开关。 */
    @Column(name = "ab_enabled", nullable = false)
    private Boolean abEnabled;

    /** A/B 变体 JSON 数组文本：[{"name","visits","leads"}]。 */
    @Column
    private String variants;

    /** 创建幂等令牌（前端表单会话生成）。 */
    @Column(name = "client_token", length = 64)
    private String clientToken;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
