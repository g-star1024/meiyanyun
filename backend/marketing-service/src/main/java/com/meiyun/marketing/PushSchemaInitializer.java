package com.meiyun.marketing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * push_record 表结构启动自愈（p2-2 触达链路联调）。
 *
 * <p>背景：旧表 {@code push_type varchar(8)} 容不下微信公众号渠道码 {@code WECHAT_MP}（9 字符），
 * 真实发送该渠道会触发 PG「value too long for type character varying(8)」。JPA ddl-auto=update
 * 只新增列/表，不会收窄或扩宽既有列长度，因此升级部署后需显式 ALTER。</p>
 *
 * <p>另清理旧版实体遗留的 CHECK 约束 {@code push_record_push_type_check}（只允许中文旧值
 * 短信/小程序/App）：渠道合法性已由 Controller 白名单（SMS/WECOM/WECHAT_MP）兜底，
 * DB 旧约束会拒绝全部新渠道码，必须幂等删除。</p>
 *
 * <p>幂等：ALTER COLUMN TYPE / DROP CONSTRAINT IF EXISTS 重复执行无副作用；新库由 Hibernate
 * 按实体 length=16 直接建表且无此约束，本语句同样安全。</p>
 */
@Component
@Order(10)
public class PushSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PushSchemaInitializer.class);

    private final JdbcTemplate jdbc;

    public PushSchemaInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            jdbc.execute("ALTER TABLE push_record ALTER COLUMN push_type TYPE varchar(16)");
            // 旧版实体遗留 CHECK（只收 短信/小程序/App）：渠道合法性已由 Controller 白名单兜底，旧约束必须删除
            jdbc.execute("ALTER TABLE push_record DROP CONSTRAINT IF EXISTS push_record_push_type_check");
            log.info("push_record.push_type 已确认为 varchar(16) 且旧中文渠道 CHECK 约束已清理（承载 SMS/WECOM/WECHAT_MP）");
        } catch (Exception e) {
            // 表尚不存在（首次启动 Hibernate 还未建表）等场景：不阻断启动，实体 length=16 已保证新表正确
            log.warn("push_record 表结构自愈跳过：{}", e.getMessage());
        }
    }
}
