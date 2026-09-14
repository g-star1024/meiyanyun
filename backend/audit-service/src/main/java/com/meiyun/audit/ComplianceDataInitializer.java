package com.meiyun.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 合规中心域种子（B49 卡9，DELIVERY-P5-B49 §卡9）：compliance_check 为空时幂等播种。
 * audit-service 首个 DataInitializer。
 *
 * <p>照前端 m1Compliance mock 全量 12 条检查项：六类（资质证照/知情同意/药品溯源/
 * 隐私合规/医疗广告/院感管理）× 四态（PASS×7 / WARN×2 / FAIL×2 / PENDING×1）全覆盖；
 * last_check_at 按 mock hoursAgo 换算，due_date 保留 mock 原值（含历史日期，整改期限语义）。
 *
 * <p>另播 6 条合规审计（SENSITIVE_VIEW/IMPERSONATE_START/PERMISSION_CHANGE/IMPERSONATE_END/
 * EXPORT_DATA/CONFIG_CHANGE）：必须经 {@link AuditService#append} 真实写入以保 SHA-256
 * 哈希链完整（不可直接 INSERT）；按旧→新顺序追加，页面按 id 倒序时最新 SENSITIVE_VIEW 在最前，
 * 与 mock 时间线顺序一致。ip/risk/target 落 payload jsonb 四键（audit_log 不扩列）。
 *
 * <p><b>栈门控</b>：与 BizTargetDataInitializer 同规——仅种子库（JDBC URL 含 meiyun_seed）
 * 播种；prod 启动跳过，数据由页面复检/检查录入。
 */
@Component
@Order(45)
public class ComplianceDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ComplianceDataInitializer.class);

    private final ComplianceCheckRepository checkRepo;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final String datasourceUrl;

    public ComplianceDataInitializer(ComplianceCheckRepository checkRepo, AuditService auditService,
                                     ObjectMapper objectMapper,
                                     @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.checkRepo = checkRepo;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过合规中心演示数据播种；如需演示数据请在页面录入",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (checkRepo.count() > 0) {
            log.info("合规检查项已存在（{} 条），跳过合规播种", checkRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<ComplianceCheck> rows = new ArrayList<>();
        rows.add(check("QUALIFICATION", "医疗机构执业许可证", "证照在有效期内且悬挂公示", "静安旗舰店",
                "PASS", 72, "陈野", "证照编号 PDY10034-...", "2027-03-15", null, now));
        rows.add(check("QUALIFICATION", "医师执业证书", "在岗医师均持有效执业证", "静安旗舰店",
                "PASS", 48, "陈野", "顾屿 110310...", null, null, now));
        rows.add(check("QUALIFICATION", "医师执业证书", "在岗医师均持有效执业证", "浦东诊所",
                "WARN", 96, "陈野", null, "2026-09-01", "新入职医师证件正在多点执业备案，限期7天", now));
        rows.add(check("CONSENT", "术前知情同意书签署", "所有侵入性项目100%签署纸质+电子同意书", "静安旗舰店",
                "PASS", 24, "苏晴", "本月签署率 100%（236/236）", null, null, now));
        rows.add(check("CONSENT", "术前知情同意书签署", "所有侵入性项目100%签署纸质+电子同意书", "徐汇社区店",
                "FAIL", 12, "苏晴", null, "2026-08-28", "发现 3 例热玛吉仅有电子签未留存纸质，要求3日内补签", now));
        rows.add(check("DRUG_TRACE", "注射类药品溯源", "肉毒/玻尿酸一物一码、全程可追溯", "静安旗舰店",
                "PASS", 36, "钱进", "本月扫码溯源 412 支，0 异常", null, null, now));
        rows.add(check("DRUG_TRACE", "注射类药品溯源", "肉毒/玻尿酸一物一码、全程可追溯", "浦东诊所",
                "WARN", 60, "钱进", null, null, "保妥适 1 批次温控记录缺失 2 小时，已上报供应商", now));
        rows.add(check("PRIVACY", "客户敏感信息访问审计", "手机号/身份证脱敏，访问需授权与留痕", "全集团",
                "PASS", 6, "周岚", "本周敏感字段解密 18 次，均有授权", null, null, now));
        rows.add(check("AD", "对外宣传素材合规", "医疗广告经审查、无绝对化用语/案例对比", "静安旗舰店",
                "PASS", 120, "白桥", "在投素材 24 份，均经法务审查", null, null, now));
        rows.add(check("AD", "对外宣传素材合规", "医疗广告经审查、无绝对化用语/案例对比", "徐汇社区店",
                "PENDING", 240, "白桥", null, null, "新增抖音投流素材 3 份待审", now));
        rows.add(check("INFECTION", "院感消毒记录", "治疗室每4小时消毒并记录、医疗器械高压灭菌", "静安旗舰店",
                "PASS", 8, "苏晴", "消毒记录完整，物表抽检合格", null, null, now));
        rows.add(check("INFECTION", "院感消毒记录", "治疗室每4小时消毒并记录、医疗器械高压灭菌", "浦东诊所",
                "FAIL", 4, "苏晴", null, "2026-08-26", "8月24日下午消毒记录漏登，灭菌指示卡留存不全", now));
        checkRepo.saveAll(rows);

        // 审计种子：旧→新顺序 append（页面按 id 倒序，最新 SENSITIVE_VIEW 在最前，贴 mock 时间线）
        audit("周岚", "CONFIG_CHANGE", "系统设置·双签阈值", "10.12.21.45",
                "L2 审批阈值由 50000 调整为 30000", "HIGH");
        audit("陈野", "EXPORT_DATA", "华东大区经营数据", "10.12.34.12",
                "导出 7 月经营报表 Excel，含成本字段", "LOW");
        audit("周岚", "IMPERSONATE_END", "苏晴（静安店长）", "10.12.21.45",
                "结束代操作，会话时长 47 分钟", "MEDIUM");
        audit("周岚", "PERMISSION_CHANGE", "角色「皮肤科护士」", "10.12.21.45",
                "新增字段权限：emr.treatment 只读", "MEDIUM");
        audit("周岚", "IMPERSONATE_START", "苏晴（静安店长）", "10.12.21.45",
                "以店长身份排查退款审批异常，理由：门店反馈审批按钮无响应（工单#T20260824）", "HIGH");
        audit("周岚", "SENSITIVE_VIEW", "客户 138****6677 病历", "10.12.21.45",
                "导出客户病历用于医疗纠纷举证，已获客户书面授权", "MEDIUM");
        log.info("合规中心播种完成：{} 条检查项（六类×四态全覆盖）+ 6 条合规审计（链式哈希）", rows.size());
    }

    private ComplianceCheck check(String category, String title, String requirement, String storeName,
                                  String status, int hoursAgo, String checker, String evidence,
                                  String dueDate, String remark, OffsetDateTime now) {
        ComplianceCheck c = new ComplianceCheck();
        c.setCategory(category);
        c.setTitle(title);
        c.setRequirement(requirement);
        c.setStoreName(storeName);
        c.setStatus(status);
        c.setLastCheckAt(now.minusHours(hoursAgo));
        c.setChecker(checker);
        c.setEvidence(evidence);
        if (dueDate != null) {
            c.setDueDate(LocalDate.parse(dueDate));
        }
        c.setRemark(remark);
        c.setCreatedAt(now);
        c.setUpdatedAt(now);
        return c;
    }

    private void audit(String actor, String action, String target, String ip, String detail, String risk) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("target", target);
        payload.put("ip", ip);
        payload.put("detail", detail);
        payload.put("risk", risk);
        try {
            auditService.append("COMPLIANCE", null, actor, action, objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计载荷序列化失败", e);
        }
    }
}
