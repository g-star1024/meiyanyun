package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.marketing.storage.DelegatingStorageService;
import com.meiyun.marketing.storage.StorageException;
import com.meiyun.security.DataScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
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

    /** 棒⑧卡4：渲染产物大小上限 10MB（与端点校验、multipart 配置同口径）。 */
    public static final long MAX_RENDER_SIZE = 10L * 1024 * 1024;

    /** PNG 魔数前 8 字节：89 50 4E 47 0D 0A 1A 0A。 */
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

    private final PosterTemplateRepository templateRepo;
    private final PosterRecordRepository posterRepo;
    private final BizNoGenerator noGen;
    private final AuditRecorder audit;
    private final ForbiddenWordService forbiddenWordService;
    private final DelegatingStorageService storageService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PosterService(PosterTemplateRepository templateRepo, PosterRecordRepository posterRepo,
                         BizNoGenerator noGen, AuditRecorder audit,
                         ForbiddenWordService forbiddenWordService,
                         DelegatingStorageService storageService) {
        this.templateRepo = templateRepo;
        this.posterRepo = posterRepo;
        this.noGen = noGen;
        this.audit = audit;
        this.forbiddenWordService = forbiddenWordService;
        this.storageService = storageService;
    }

    // ==================== 查询 ====================

    public List<PosterTemplate> listTemplates() {
        return templateRepo.findAll();
    }

    public List<PosterRecord> listPosters() {
        return posterRepo.findAllByOrderByCreatedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 新建模板：风格/视觉色白名单＋文案合规校验；初始 ENABLED/uses=0；审计 CREATE。 */
    @Transactional
    public PosterTemplate createTemplate(TemplateCmd cmd) {
        String name = cmd.templateName() == null ? "" : cmd.templateName().trim();
        String style = cmd.style() == null ? "" : cmd.style().trim();
        String accent = cmd.accent() == null ? "" : cmd.accent().trim();
        String defaultTitle = cmd.defaultTitle() == null ? "" : cmd.defaultTitle().trim();
        String defaultSubtitle = cmd.defaultSubtitle() == null ? "" : cmd.defaultSubtitle().trim();
        validateTemplateFields(name, style, accent, defaultTitle, defaultSubtitle);

        PosterTemplate t = new PosterTemplate();
        t.setTemplateId(noGen.next("PT", like -> templateRepo
                .findTopByTemplateIdLikeOrderByTemplateIdDesc(like).map(PosterTemplate::getTemplateId).orElse(null)));
        t.setTemplateName(name);
        t.setStyle(style);
        t.setStatus("ENABLED");
        t.setUses(0);
        t.setAccent(accent);
        t.setDefaultTitle(defaultTitle);
        t.setDefaultSubtitle(defaultSubtitle);
        t.setCreatedAt(OffsetDateTime.now());
        PosterTemplate saved = templateRepo.save(t);
        audit("POSTER_TEMPLATE", "CREATE", saved.getTemplateId(), Map.of(
                "name", name, "style", style, "accent", accent,
                "defaultTitle", defaultTitle, "defaultSubtitle", defaultSubtitle));
        return saved;
    }

    /** 编辑模板：名称/风格/视觉色/默认文案可改；status 与 uses 不动；审计 UPDATE。 */
    @Transactional
    public PosterTemplate updateTemplate(String templateId, TemplateCmd cmd) {
        PosterTemplate t = mustGetTemplate(templateId);
        String name = cmd.templateName() == null ? "" : cmd.templateName().trim();
        String style = cmd.style() == null ? "" : cmd.style().trim();
        String accent = cmd.accent() == null ? "" : cmd.accent().trim();
        String defaultTitle = cmd.defaultTitle() == null ? "" : cmd.defaultTitle().trim();
        String defaultSubtitle = cmd.defaultSubtitle() == null ? "" : cmd.defaultSubtitle().trim();
        validateTemplateFields(name, style, accent, defaultTitle, defaultSubtitle);

        t.setTemplateName(name);
        t.setStyle(style);
        t.setAccent(accent);
        t.setDefaultTitle(defaultTitle);
        t.setDefaultSubtitle(defaultSubtitle);
        PosterTemplate saved = templateRepo.save(t);
        audit("POSTER_TEMPLATE", "UPDATE", saved.getTemplateId(), Map.of(
                "name", name, "style", style, "accent", accent,
                "defaultTitle", defaultTitle, "defaultSubtitle", defaultSubtitle));
        return saved;
    }

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

    /**
     * 棒⑧卡4：海报渲染产物上传。写接口四件套：① 校验（海报存在、非空、≤10MB、Content-Type
     * 与 PNG 魔数双重校验）；② 幂等（objectKey 固定 posters/{posterId}.png，覆盖写天然幂等，
     * 重传不产生重复对象）；③ 审计 RENDER_UPLOAD（objectKey/size/provider）；④ 中文错误。
     * 存储路由：STORAGE_DIRECT 未启用走 local 固定桶；启用缺参抛 503 SKIPPED 不静默回落。
     */
    @Transactional
    public PosterRecord renderUpload(String posterId, MultipartFile file) {
        PosterRecord p = mustGetPoster(posterId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渲染产物文件为空");
        }
        if (file.getSize() > MAX_RENDER_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渲染产物大小超过 10MB 上限");
        }
        String contentType = file.getContentType();
        if (contentType == null || !contentType.equalsIgnoreCase("image/png")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渲染产物仅支持 PNG 图片");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "渲染产物读取失败");
        }
        if (!isPng(bytes)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "文件内容不是有效 PNG 图片");
        }

        DelegatingStorageService.Route route = storageService.currentRoute();
        String objectKey = "posters/" + posterId + ".png";
        try {
            route.service().upload(route.bucket(), objectKey,
                    new ByteArrayInputStream(bytes), bytes.length, "image/png");
        } catch (StorageException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "渲染产物存储失败，请稍后重试");
        }

        p.setRenderObjectKey(route.bucket() + "/" + objectKey);
        p.setRenderUploadedAt(OffsetDateTime.now());
        p.setRenderSize((long) bytes.length);
        PosterRecord saved = posterRepo.save(p);
        audit("POSTER", "RENDER_UPLOAD", saved.getPosterId(), Map.of(
                "objectKey", saved.getRenderObjectKey(), "size", bytes.length, "provider", route.provider()));
        return saved;
    }

    /** 棒⑧卡4：渲染产物回源。定位符首段拆 bucket/objectKey；未上传 / 文件缺失均 404 中文。 */
    public RenderFile renderFile(String posterId) {
        PosterRecord p = mustGetPoster(posterId);
        String locator = p.getRenderObjectKey();
        if (locator == null || locator.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该海报尚未上传渲染产物");
        }
        int slash = locator.indexOf('/');
        if (slash <= 0 || slash == locator.length() - 1) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "渲染产物定位符损坏");
        }
        InputStream stream;
        try {
            stream = storageService.download(locator.substring(0, slash), locator.substring(slash + 1));
        } catch (StorageException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "渲染产物文件不存在或已删除");
        }
        return new RenderFile(stream, p.getRenderSize() == null ? -1L : p.getRenderSize());
    }

    // ==================== 内部方法 ====================

    private void validateTemplateFields(String name, String style, String accent,
                                        String defaultTitle, String defaultSubtitle) {
        if (name.isEmpty() || name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称不可为空且长度不超过 64 字");
        }
        if (!STYLES.contains(style)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "模板风格不合法（可选：" + String.join("/", STYLES) + "）");
        }
        if (!ACCENTS.contains(accent)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "视觉色不合法（可选：" + String.join("/", ACCENTS) + "）");
        }
        if (defaultTitle.isEmpty() || defaultTitle.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认主标题不可为空且长度不超过 64 字");
        }
        if (defaultSubtitle.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "默认副标题长度不超过 128 字");
        }
        List<String> hits = forbiddenWordService.check(name + "\n" + defaultTitle + "\n" + defaultSubtitle);
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "营销合规拦截：命中违禁词 " + String.join("、", hits));
        }
    }

    private PosterTemplate mustGetTemplate(String templateId) {
        return templateRepo.findById(templateId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "海报模板不存在：" + templateId));
    }

    private PosterRecord mustGetPoster(String posterId) {
        return posterRepo.findById(posterId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "海报不存在：" + posterId));
    }

    private static boolean isPng(byte[] bytes) {
        if (bytes == null || bytes.length < PNG_MAGIC.length) {
            return false;
        }
        for (int i = 0; i < PNG_MAGIC.length; i++) {
            if (bytes[i] != PNG_MAGIC[i]) {
                return false;
            }
        }
        return true;
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

    /** 模板新建/编辑命令（defaultSubtitle 可空）。 */
    public record TemplateCmd(String templateName, String style, String accent,
                              String defaultTitle, String defaultSubtitle) {}

    /** 棒⑧卡4：渲染产物回源视图（size=-1 表示长度未知，不写 Content-Length）。 */
    public record RenderFile(InputStream stream, long size) {}
}
