package com.meiyun.customer;

import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * M3-B8 启动播种（公海池＋客诉管理 M3-19/20）：complaint 六条（TS20260825001-TS20260820006）
 * ＋ complaint_log 时间线逐状态构造，均与前端 complaint store 种子活规格逐字一致
 * （createdAt 为当前时刻前 0-10 小时逐条间隔 2 小时；登记人「夏沫（前台）」、
 * 受理/提交/驳回「苏晴（店长）」、结案「陈野（区域经理）」）。
 *
 * <p><b>栈门控</b>：演示数据仅与 seed 栈自洽，仅在种子库（JDBC URL 含 meiyun_seed）播种，
 * 与各 DataInitializer 同一门控约定；正式栈客诉由门店真实登记。公海池零新种子——
 * seed 库 customer 表已有 store_code IS NULL 存量数据（2 条）足验认领/分配链路。
 */
@Component
@Order(44)
public class M3B8DataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(M3B8DataInitializer.class);

    private static final String REGISTRAR = "夏沫（前台）";
    private static final String MANAGER = "苏晴（店长）";
    private static final String REGIONAL = "陈野（区域经理）";

    private final ComplaintRepository complaintRepo;
    private final ComplaintLogRepository logRepo;
    private final String datasourceUrl;

    public M3B8DataInitializer(ComplaintRepository complaintRepo, ComplaintLogRepository logRepo,
                               @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.complaintRepo = complaintRepo;
        this.logRepo = logRepo;
        this.datasourceUrl = datasourceUrl;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (datasourceUrl == null || !datasourceUrl.contains("meiyun_seed")) {
            log.info("非种子库（{}），跳过 M3-B8 演示数据播种；正式栈客诉由门店真实登记",
                    datasourceUrl == null || datasourceUrl.isBlank() ? "默认数据源" : datasourceUrl);
            return;
        }
        if (complaintRepo.count() > 0) {
            log.info("客诉记录已存在（{} 条），跳过播种", complaintRepo.count());
            return;
        }
        OffsetDateTime now = OffsetDateTime.now();

        // 王美丽·待受理·医疗风险（照 mock 种子第 1 条逐字）
        saveComplaint("TS20260825001", "C-400", "王美丽", "STORE", "HIGH", "MEDICAL", true,
                "光子嫩肤术后面部出现明显红肿，客户质疑能量参数设置过高，要求复诊与赔付。",
                "SO20260824001", "PENDING_ACCEPT", 300000L, "L1", null, null, now);

        // 陈思·处理中（照 mock 种子第 2 条逐字）
        saveComplaint("TS20260824002", "C-401", "陈思", "PHONE", "MEDIUM", "SERVICE", false,
                "预约到店等候超 40 分钟，前台未主动告知进度，客户体验不满。",
                null, "PROCESSING", 0L, "L1", null, null, now.minusHours(2));

        // 赵敏·待结案审批·有方案（照 mock 种子第 3 条逐字）
        saveComplaint("TS20260823003", "C-402", "赵敏", "ONLINE", "MEDIUM", "BILLING", false,
                "结算时被加收未告知的耗材费 ¥380，要求退还争议费用并补偿一次护理。",
                "SO20260820007", "PENDING_REVIEW", 128000L, "L1",
                "核实为前台未提前说明耗材费，退还 ¥380 耗材费并补偿一次价值 ¥1280 水光护理，已电话致歉。",
                null, now.minusHours(4));

        // 林晚·待结案审批·医疗风险·L2（照 mock 种子第 4 条逐字）
        saveComplaint("TS20260822004", "C-403", "林晚", "THIRD_PARTY", "HIGH", "OUTCOME", true,
                "热玛吉治疗后效果未达预期，客户通过平台投诉要求退一赔三，持续在社交平台发声。",
                "SO20260815003", "PENDING_REVIEW", 860000L, "L2",
                "经主诊医生复评效果在合理范围内，出于客情维护退还疗程余款 ¥8600，安排院长面谈，签署和解协议。",
                null, now.minusHours(6));

        // 周婷·已结案（照 mock 种子第 5 条逐字）
        saveComplaint("TS20260821005", "C-404", "周婷", "STORE", "LOW", "OTHER", false,
                "会员积分未及时到账，客户来电反映。",
                null, "CLOSED", 20000L, "L1",
                "系统延迟导致，手动补录积分并赠送 ¥200 护理券。",
                null, now.minusHours(8));

        // 吴桐·已驳回（照 mock 种子第 6 条逐字）
        saveComplaint("TS20260820006", "C-405", "吴桐", "PHONE", "LOW", "BILLING", false,
                "客户声称重复扣费，经查为两笔不同项目消费。",
                null, "REJECTED", 0L, "L1", null,
                "调取签购单与消费记录核实为两个独立项目，非重复扣费，已向客户解释并提供凭证。",
                now.minusHours(10));

        log.info("客诉记录播种：6 条（待受理 1/处理中 1/待结案审批 2/已结案 1/已驳回 1，医疗风险 2）");
    }

    private void saveComplaint(String complaintNo, String customerId, String customerName,
                               String source, String severity, String category, boolean medicalRisk,
                               String description, String relatedOrderNo, String status,
                               long compensationCents, String signTier, String resolution,
                               String rejectionReason, OffsetDateTime createdAt) {
        Complaint c = new Complaint();
        c.setComplaintNo(complaintNo);
        c.setCustomerId(customerId);
        c.setCustomerName(customerName);
        c.setSource(source);
        c.setSeverity(severity);
        c.setCategory(category);
        c.setMedicalRisk(medicalRisk);
        c.setDescription(description);
        c.setRelatedOrderNo(relatedOrderNo);
        c.setStoreCode("store-jingan");
        c.setStoreName("静安旗舰店");
        c.setStatus(status);
        c.setCompensationAmountCents(compensationCents);
        c.setSignTier(signTier);
        c.setResolution(resolution);
        c.setRejectionReason(rejectionReason);
        c.setCreatedAt(createdAt);
        c.setUpdatedAt(createdAt);
        if (!Complaint.STATUS_PENDING_ACCEPT.equals(status)) {
            c.setAcceptedByName(MANAGER);
            c.setAcceptedAt(createdAt);
        }
        if (resolution != null && (Complaint.STATUS_PENDING_REVIEW.equals(status)
                || Complaint.STATUS_CLOSED.equals(status) || Complaint.STATUS_REJECTED.equals(status))) {
            c.setSubmittedByName(MANAGER);
            c.setSubmittedAt(createdAt);
        }
        if (Complaint.STATUS_CLOSED.equals(status)) {
            c.setClosedByName(REGIONAL);
            c.setClosedAt(createdAt);
        }
        if (Complaint.STATUS_REJECTED.equals(status)) {
            c.setClosedByName(MANAGER);
            c.setClosedAt(createdAt);
        }
        Complaint saved = complaintRepo.save(c);

        saveLog(saved.getId(), createdAt, REGISTRAR, "登记投诉", null);
        if (!Complaint.STATUS_PENDING_ACCEPT.equals(status)) {
            saveLog(saved.getId(), createdAt, MANAGER, "受理投诉", null);
        }
        if (resolution != null && c.getSubmittedByName() != null) {
            saveLog(saved.getId(), createdAt, MANAGER, "提交处理方案",
                    "赔付 ¥" + (compensationCents / 100) + "（" + signTier + "）");
        }
        if (Complaint.STATUS_CLOSED.equals(status)) {
            saveLog(saved.getId(), createdAt, REGIONAL, "审批结案", null);
        }
        if (Complaint.STATUS_REJECTED.equals(status)) {
            saveLog(saved.getId(), createdAt, MANAGER, "驳回投诉", rejectionReason);
        }
    }

    private void saveLog(Long complaintId, OffsetDateTime at, String byName, String action, String note) {
        ComplaintLog l = new ComplaintLog();
        l.setComplaintId(complaintId);
        l.setAtTime(at);
        l.setByName(byName);
        l.setAction(action);
        l.setNote(note);
        logRepo.save(l);
    }
}
