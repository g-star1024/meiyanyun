package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * 海报裂变写链路（M5-04）：模板启停 / 生成海报。
 *
 * <p>写接口四件套：① 参数校验（风格白名单、模板须存在且启用、标题/项目/推荐人必填、
 * 标题文案合规）；② 幂等（启停目标态未变返回 false 不审计）；③ 全动作审计
 * （bizType=POSTER_TEMPLATE / POSTER，payload JSON）；④ 中文错误。
 * 金额口径：dealAmount bigint 存「分」；commissionRate 百分比×10（5% = 50）。
 */
@Service
public class PosterService {

    public static final List<String> STYLES =
            List.of("FESTIVAL", "NEWBIE", "PROJECT", "MEMBER", "REFERRAL", "LIVE");
    public static final List<String> ACCENTS =
            List.of("brand", "teal", "orange", "purple", "blue", "gold");

    /** 默认分销佣金比例 5%（百分比×10 = 50）。 */
    public static final int DEFAULT_COMMISSION_RATE = 50;

    private final PosterTemplateRepository templateRepo;
    private final PosterRecordRepository posterRepo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final ForbiddenWordService forbiddenWordService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PosterService(PosterTemplateRepository templateRepo, PosterRecordRepository posterRepo,
                         BizNoGenerator noGen, AuditRecorder audit,
                         ForbiddenWordService forbiddenWordService) {
        this.templateRepo = templateRepo;
        this.posterRepo = posterRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.forbiddenWordService = forbiddenWordService;
    }

    // ==================== 查询 ====================

    public List<PosterTemplate> listTemplates() {
        return templateRepo.findAll();
    }

    public List<PosterRecord> listPosters() {
        return posterRepo.findAllByOrderByCreatedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 模板启用/停用切换（ENABLED↔DISABLED 翻转；每次实际翻转都审计）。 */
    @Transactional
    public boolean toggleTemplate(String templateId) {
        PosterTemplate t = mustGetTemplate(templateId);
        String target = "ENABLED".equals(t.getStatus()) ? "DISABLED" : "ENABLED";
        t.setStatus(target);
        templateRepo.save(t);
        audit("POSTER_TEMPLATE", "TOGGLE", templateId, Map.of(
                "name", t.getTemplateName(), "style", t.getStyle(), "status", target));
        return true;
    }

    /** 生成海报：模板须存在且为启用态；生成后模板 uses +1；新海报漏斗/成交初始为 0。 */
    @Transactional
    public PosterRecord createPoster(PosterCmd cmd) {
        String templateId = cmd.templateId() == null ? "" : cmd.templateId().trim();
        String title = cmd.title() == null ? "" : cmd.title().trim();
        String subtitle = cmd.subtitle() == null ? "" : cmd.subtitle().trim();
        String project = cmd.project() == null ? "" : cmd.project().trim();
        String referrerName = cmd.referrerName() == null ? "" : cmd.referrerName().trim();
        PosterTemplate t = templateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择海报模板"));
        if (!"ENABLED".equals(t.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该模板已停用，请启用后再生成海报");
        }
        if (title.isEmpty() || title.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "海报主标题不可为空且长度不超过 64 字");
        }
        if (subtitle.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "海报副标题长度不超过 128 字");
        }
        if (project.isEmpty() || project.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写主推项目");
        }
        if (referrerName.isEmpty() || referrerName.length() > 32) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择分销推荐人");
        }
        List<String> hits = forbiddenWordService.check(title + "\n" + subtitle + "\n" + project);
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "营销合规拦截：命中违禁词 " + String.join("、", hits));
        }
        int rate = cmd.commissionRate() == null ? DEFAULT_COMMISSION_RATE : cmd.commissionRate();
        if (rate < 0 || rate > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "佣金比例不合法（0~100，百分比×10）");
        }

        PosterRecord p = new PosterRecord();
        p.setPosterId(noGen.next("MP", like -> posterRepo
                .findTopByPosterIdLikeOrderByPosterIdDesc(like).map(PosterRecord::getPosterId).orElse(null)));
        p.setTemplateId(t.getTemplateId());
        p.setTemplateName(t.getTemplateName());
        p.setStyle(t.getStyle());
        p.setAccent(t.getAccent());
        p.setTitle(title);
        p.setSubtitle(subtitle.isEmpty() ? null : subtitle);
        p.setProject(project);
        p.setReferrerName(referrerName);
        p.setStatus("PUBLISHED");
        p.setShare(0);
        p.setScan(0);
        p.setLead(0);
        p.setVisit(0);
        p.setDeal(0);
        p.setDealAmount(0L);
        p.setCommissionRate(rate);
        p.setCreatedAt(OffsetDateTime.now());
        PosterRecord saved = posterRepo.save(p);

        t.setUses(t.getUses() == null ? 1 : t.getUses() + 1);
        templateRepo.save(t);

        audit("POSTER", "CREATE", saved.getPosterId(), Map.of(
                "templateId", t.getTemplateId(), "templateName", t.getTemplateName(),
                "title", title, "project", project, "referrerName", referrerName,
                "commissionRate", rate));
        return saved;
    }

    // ==================== 内部方法 ====================

    private PosterTemplate mustGetTemplate(String templateId) {
        return templateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "海报模板不存在：" + templateId));
    }

    private void audit(String bizType, String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record(bizType, txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record(bizType, txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /** 生成海报命令（commissionRate 可空，缺省 5%；百分比×10）。 */
    public record PosterCmd(String templateId, String title, String subtitle, String project,
                            String referrerName, Integer commissionRate) {}
}
