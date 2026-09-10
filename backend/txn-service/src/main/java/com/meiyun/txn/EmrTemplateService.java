package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.LoginUser;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Set;

/**
 * EMR 病历模板库服务（P5-B30）：套用候选查询 / 门店自建模板 / 停用。
 *
 * <p>铁律对齐病历主域：门店取 {@link DataScope} 当前登录本店（不信入参）；
 * 集团通用模板 store_code=NULL 全门店可见，门店模板仅本店可见可停用；全动作审计（bizType=EMR_TEMPLATE）。
 * 模板只在录入时复制七段文本，不与病历留任何外键关系，停用模板不影响已生成病历。</p>
 */
@Service
public class EmrTemplateService {

    private static final Set<String> TYPES = Set.of("FIRST_VISIT", "FOLLOW_UP", "TREATMENT", "PROCEDURE");

    private final EmrTemplateRepository repo;
    private final AuditRecorder audit;

    public EmrTemplateService(EmrTemplateRepository repo, AuditRecorder audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** 新建模板入参（七段文本）。 */
    public record CreateCmd(String name, String type,
                            String chiefComplaint, String presentIllness, String pastHistory,
                            String allergy, String diagnosis, String treatment, String prescription) {}

    /** 套用候选：启用模板（集团通用 + 本店自建）；type 非空时匹配「类型通用 + 指定类型」。 */
    @Transactional(readOnly = true)
    public Page<EmrTemplate> listActive(String type, Pageable pageable) {
        String storeCode = currentStore();
        String t = type == null || type.isBlank() ? null : type.trim();
        return repo.searchActive(storeCode, t, pageable);
    }

    /** 门店自建模板（自动盖本店码；集团模板只能由种子/集团后台产生）。 */
    @Transactional
    public EmrTemplate create(CreateCmd cmd) {
        String actor = DataScope.currentActor();
        String storeCode = currentStore();
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw badRequest("模板名称不能为空");
        }
        String type = normalizeType(cmd.type());
        EmrTemplate t = new EmrTemplate();
        t.setTemplateNo(nextNo());
        t.setName(cmd.name().trim());
        t.setType(type);
        t.setChiefComplaint(trimToNull(cmd.chiefComplaint()));
        t.setPresentIllness(trimToNull(cmd.presentIllness()));
        t.setPastHistory(trimToNull(cmd.pastHistory()));
        t.setAllergy(trimToNull(cmd.allergy()));
        t.setDiagnosis(trimToNull(cmd.diagnosis()));
        t.setTreatment(trimToNull(cmd.treatment()));
        t.setPrescription(trimToNull(cmd.prescription()));
        t.setStoreCode(storeCode);
        t.setEnabled(true);
        t.setCreatedBy(actor);
        EmrTemplate saved = repo.save(t);
        audit.record("EMR_TEMPLATE", saved.getTemplateNo(), actor, "CREATE",
                "{\"name\":\"" + esc(saved.getName()) + "\",\"type\":\"" + (type == null ? "" : type) + "\"}");
        return saved;
    }

    /** 停用模板：仅本店自建模板可停用；集团模板对门店只读。 */
    @Transactional
    public EmrTemplate disable(String templateNo) {
        EmrTemplate t = requireTemplate(templateNo);
        String storeCode = currentStore();
        if (t.getStoreCode() == null || !storeCode.equals(t.getStoreCode())) {
            throw notFound("数据不存在或无权操作");
        }
        t.setEnabled(false);
        EmrTemplate saved = repo.save(t);
        audit.record("EMR_TEMPLATE", templateNo, DataScope.currentActor(), "DISABLE", "{}");
        return saved;
    }

    // ==================== 内部 ====================

    private synchronized String nextNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long max;
        try {
            max = repo.maxSeqOfDay("EMT" + day + "-%");
        } catch (Exception e) {
            max = 0L;
        }
        return "EMT" + day + "-" + String.format("%06d", max + 1);
    }

    private EmrTemplate requireTemplate(String no) {
        EmrTemplate t = repo.findById(no).orElseThrow(() -> notFound("模板不存在或无权查看"));
        if (t.getStoreCode() != null && !DataScope.canReadStore(t.getStoreCode())) {
            throw notFound("模板不存在或无权查看");
        }
        return t;
    }

    private String currentStore() {
        LoginUser user = DataScope.current();
        String storeCode = user == null ? null : user.storeCode();
        if (storeCode == null || storeCode.isBlank()) {
            throw badRequest("当前登录人未归属门店，无法操作病历模板");
        }
        return storeCode;
    }

    private String normalizeType(String type) {
        if (type == null || type.isBlank()) return null;
        String t = type.trim();
        if (!TYPES.contains(t)) {
            throw badRequest("非法病历类型: " + t);
        }
        return t;
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private static ResponseStatusException notFound(String msg) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, msg);
    }
}
