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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 落地页写链路（P5-B88）：创建（草稿）/ 发布 / 下线 / 组件块排序 / A/B 开关。
 *
 * 状态机：DRAFT 草稿 → PUBLISHED 已发布 → OFFLINE 已下线；OFFLINE 可再发布回 PUBLISHED。
 * 合法动作与前端 m5Landing store 语义完全一致：
 *   publish ＝ 非 PUBLISHED → PUBLISHED；offline ＝ 仅 PUBLISHED → OFFLINE。
 * 敏感词双道（D6）：前端 checkSensitive 预检保留，服务端创建时对 headline/subtitle/project
 * 再经 {@link ForbiddenWordService#check} 拦截，命中抛 400 中文错误。
 * 幂等：create 带 client_token 命中返回已有行不重复审计；publish/offline 状态未变返回 false。
 */
@Service
public class LandingService {

    public static final Set<String> TEMPLATES =
            Set.of("NEWBIE", "PROJECT", "FESTIVAL", "MEMBER", "BRAND");

    /** 默认组件块（与前端 DEFAULT_BLOCKS 一致：头图/标题/项目卡/表单/按钮）。 */
    static final List<Map<String, Object>> DEFAULT_BLOCKS = List.of(
            Map.of("id", "blk-1", "type", "HERO", "label", "头图"),
            Map.of("id", "blk-2", "type", "TITLE", "label", "标题"),
            Map.of("id", "blk-3", "type", "PROJECT", "label", "项目卡"),
            Map.of("id", "blk-4", "type", "FORM", "label", "表单"),
            Map.of("id", "blk-5", "type", "BUTTON", "label", "按钮"));

    private final LandingPageRepository landingRepo;
    private final BizNoGenerator noGen;
    private final ForbiddenWordService forbiddenWordService;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LandingService(LandingPageRepository landingRepo, BizNoGenerator noGen,
                          ForbiddenWordService forbiddenWordService, AuditRecorder audit) {
        this.landingRepo = landingRepo;
        this.noGen = noGen;
        this.forbiddenWordService = forbiddenWordService;
        this.audit = audit;
    }

    // ==================== 查询 ====================

    public List<LandingPage> list() {
        return landingRepo.findAllByOrderByCreatedAtDesc();
    }

    // ==================== 写动作 ====================

    /** 创建落地页（落 DRAFT 草稿，visits/leads 初始为 0，默认五块组件）。 */
    @Transactional
    public LandingPage create(LandingCmd cmd) {
        validate(cmd);
        if (cmd.clientToken() != null && !cmd.clientToken().isBlank()) {
            LandingPage existing = landingRepo.findByClientToken(cmd.clientToken().trim()).orElse(null);
            if (existing != null) {
                return existing;
            }
        }
        OffsetDateTime now = OffsetDateTime.now();
        LandingPage p = new LandingPage();
        p.setPageId(noGen.next("LP", like -> landingRepo
                .findTopByPageIdLikeOrderByPageIdDesc(like).map(LandingPage::getPageId).orElse(null)));
        p.setPageName(cmd.name().trim());
        p.setTemplate(cmd.template());
        p.setStatus("DRAFT");
        p.setHeadline(trim(cmd.headline()));
        p.setSubtitle(trim(cmd.subtitle()));
        p.setProject(trim(cmd.project()));
        p.setFormFields(toJson(cmd.formFields() == null ? List.of("姓名", "手机") : cmd.formFields(), "表单字段"));
        p.setBlocks(toJson(DEFAULT_BLOCKS, "组件块"));
        p.setVisits(0L);
        p.setLeads(0L);
        p.setAbEnabled(false);
        p.setVariants("[]");
        p.setClientToken(cmd.clientToken() == null || cmd.clientToken().isBlank() ? null : cmd.clientToken().trim());
        p.setCreatedAt(now);
        p.setUpdatedAt(now);
        LandingPage saved = landingRepo.save(p);
        audit("CREATE", saved.getPageId(), Map.of(
                "name", saved.getPageName(), "template", saved.getTemplate(), "status", saved.getStatus()));
        return saved;
    }

    /** 发布：非 PUBLISHED → PUBLISHED；幂等——已是 PUBLISHED 返回 false 不审计。 */
    @Transactional
    public boolean publish(String pageId) {
        LandingPage p = mustGet(pageId);
        if ("PUBLISHED".equals(p.getStatus())) {
            return false;
        }
        p.setStatus("PUBLISHED");
        p.setUpdatedAt(OffsetDateTime.now());
        landingRepo.save(p);
        audit("PUBLISH", pageId, Map.of("name", p.getPageName(), "to", "PUBLISHED"));
        return true;
    }

    /** 下线：仅 PUBLISHED → OFFLINE；其他状态返回 false 不审计（对齐前端 mock 语义）。 */
    @Transactional
    public boolean offline(String pageId) {
        LandingPage p = mustGet(pageId);
        if (!"PUBLISHED".equals(p.getStatus())) {
            return false;
        }
        p.setStatus("OFFLINE");
        p.setUpdatedAt(OffsetDateTime.now());
        landingRepo.save(p);
        audit("OFFLINE", pageId, Map.of("name", p.getPageName(), "to", "OFFLINE"));
        return true;
    }

    /**
     * 组件块上移/下移（direction=-1 上移 / 1 下移）。
     * 块不存在或越界返回 false 不审计（对齐前端 mock 静默忽略语义）。
     */
    @Transactional
    public boolean moveBlock(String pageId, String blockId, Integer direction) {
        LandingPage p = mustGet(pageId);
        int dir = direction == null ? 0 : direction;
        if (dir != -1 && dir != 1 || blockId == null || blockId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "移动方向不合法（-1 上移 / 1 下移）");
        }
        List<Map<String, Object>> blocks = readBlocks(p.getBlocks());
        int i = -1;
        for (int k = 0; k < blocks.size(); k++) {
            if (blockId.equals(String.valueOf(blocks.get(k).get("id")))) {
                i = k;
                break;
            }
        }
        int j = i + dir;
        if (i < 0 || j < 0 || j >= blocks.size()) {
            return false;
        }
        Map<String, Object> b = blocks.remove(i);
        blocks.add(j, b);
        p.setBlocks(toJson(blocks, "组件块"));
        p.setUpdatedAt(OffsetDateTime.now());
        landingRepo.save(p);
        audit("MOVE_BLOCK", pageId, Map.of("name", p.getPageName(), "blockId", blockId, "direction", dir));
        return true;
    }

    /**
     * A/B 开关翻转。开启且变体为空时按前端 mock 公式初始化演示变体
     *（A/B 各半流量，B 版转化率 ×1.35）；关闭时变体保留不清空。
     */
    @Transactional
    public boolean toggleAb(String pageId) {
        LandingPage p = mustGet(pageId);
        boolean enabling = !Boolean.TRUE.equals(p.getAbEnabled());
        p.setAbEnabled(enabling);
        if (enabling && (p.getVariants() == null || "[]".equals(p.getVariants()))) {
            long visits = p.getVisits() == null ? 0 : p.getVisits();
            long leads = p.getLeads() == null ? 0 : p.getLeads();
            long base = Math.max(1, Math.round(visits / 2.0));
            double rate = leads / (double) Math.max(1, visits);
            List<Map<String, Object>> variants = List.of(
                    Map.of("name", "A 版（原版）", "visits", base, "leads", Math.round(base * rate)),
                    Map.of("name", "B 版（新文案）", "visits", visits - base,
                            "leads", Math.round((visits - base) * rate * 1.35)));
            p.setVariants(toJson(variants, "A/B 变体"));
        }
        p.setUpdatedAt(OffsetDateTime.now());
        landingRepo.save(p);
        audit("AB_TOGGLE", pageId, Map.of("name", p.getPageName(), "abEnabled", enabling));
        return true;
    }

    // ==================== 内部方法 ====================

    private void validate(LandingCmd cmd) {
        if (cmd.name() == null || cmd.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "落地页名称不可为空");
        }
        if (cmd.name().trim().length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "落地页名称长度不可超过 64 字");
        }
        if (cmd.template() == null || !TEMPLATES.contains(cmd.template())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "落地页模板不合法（NEWBIE/PROJECT/FESTIVAL/MEMBER/BRAND）");
        }
        List<String> hits = new ArrayList<>();
        for (String field : List.of(
                cmd.headline() == null ? "" : cmd.headline(),
                cmd.subtitle() == null ? "" : cmd.subtitle(),
                cmd.project() == null ? "" : cmd.project())) {
            hits.addAll(forbiddenWordService.check(field));
        }
        if (!hits.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "文案命中违禁词：" + String.join("、", hits));
        }
    }

    private List<Map<String, Object>> readBlocks(String json) {
        try {
            if (json == null || json.isBlank()) {
                return new ArrayList<>();
            }
            return objectMapper.readValue(json,
                    objectMapper.getTypeFactory().constructCollectionType(ArrayList.class, Map.class));
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private String toJson(Object value, String label) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, label + "数据不合法");
        }
    }

    private LandingPage mustGet(String pageId) {
        return landingRepo.findById(pageId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "落地页不存在：" + pageId));
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("LANDING_PAGE", txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("LANDING_PAGE", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /** 创建落地页命令；clientToken 为创建幂等令牌（可空）。 */
    public record LandingCmd(
            String name,
            String template,
            String headline,
            String subtitle,
            String project,
            List<String> formFields,
            String clientToken) {}

    /** 组件块移动命令（direction=-1 上移 / 1 下移）。 */
    public record MoveBlockCmd(String blockId, Integer direction) {}
}
