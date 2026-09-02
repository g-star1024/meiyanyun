package com.meiyun.marketing;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.marketing.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 违禁词库写链路与校验（A1-04 服务化）。
 *
 * <p>读走 {@link ForbiddenWordCatalog}（Redis/内存缓存）；写（增/启停/删）统一
 * {@code @CacheEvict(allEntries=true)} 失效，并落审计（bizType=FORBIDDEN_WORD，payload JSON）。
 * 校验口径与原静态 {@link ForbiddenWords#check(String)} 一致：命中返回「类别:词」列表。
 */
@Service
public class ForbiddenWordService {

    /** 合法类别（与播种词库四类对齐，非法类别 400 中文错误）。 */
    public static final List<String> CATEGORIES =
            List.of("绝对化用语", "医疗承诺", "虚假宣传", "低俗诱导");

    private final ForbiddenWordRepository repo;
    private final ForbiddenWordCatalog catalog;
    private final AuditRecorder audit;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ForbiddenWordService(ForbiddenWordRepository repo, ForbiddenWordCatalog catalog,
                                AuditRecorder audit) {
        this.repo = repo;
        this.catalog = catalog;
        this.audit = audit;
    }

    // ==================== 查询 / 校验 ====================

    /** 管理端列表（含停用词，只读视图，走缓存）。 */
    public List<ForbiddenWordView> list() {
        return catalog.all();
    }

    /** 启用词按类别分组（缓存），供前端文案预检展示与兜底。 */
    public Map<String, List<String>> categories() {
        Map<String, List<String>> grouped = catalog.enabledCategories();
        return grouped.isEmpty() ? ForbiddenWords.categories() : grouped;
    }

    /**
     * 校验文案：返回命中项（「类别:词」），空列表表示通过。
     * DB 词库为空（未播种/异常清空）时回落内置静态词库，保证合规红线不断档。
     */
    public List<String> check(String content) {
        List<String> hits = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return hits;
        }
        Map<String, List<String>> grouped = catalog.enabledCategories();
        if (grouped.isEmpty()) {
            return ForbiddenWords.check(content);
        }
        for (Map.Entry<String, List<String>> e : grouped.entrySet()) {
            for (String w : e.getValue()) {
                if (content.contains(w)) {
                    hits.add(e.getKey() + ":" + w);
                }
            }
        }
        return hits;
    }

    // ==================== 写动作 ====================

    /** 新增违禁词（幂等：同类别同词已存在则直接返回该行，不重复审计）。 */
    @Transactional
    @CacheEvict(cacheNames = ForbiddenWordCatalog.CACHE_NAME, allEntries = true)
    public ForbiddenWord create(WordCmd cmd) {
        String category = cmd.category() == null ? "" : cmd.category().trim();
        String word = cmd.word() == null ? "" : cmd.word().trim();
        if (!CATEGORIES.contains(category)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "违禁词类别不合法（绝对化用语/医疗承诺/虚假宣传/低俗诱导）");
        }
        if (word.isEmpty() || word.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "违禁词不可为空且长度不超过 64 字");
        }
        ForbiddenWord existing = repo.findByCategoryAndWord(category, word).orElse(null);
        if (existing != null) {
            if (!Boolean.TRUE.equals(existing.getEnabled())) {
                existing.setEnabled(true);
                existing.setUpdatedAt(OffsetDateTime.now());
                existing = repo.save(existing);
                audit("ENABLE", String.valueOf(existing.getWordId()), Map.of(
                        "category", category, "word", word));
            }
            return existing;
        }
        ForbiddenWord w = new ForbiddenWord();
        w.setCategory(category);
        w.setWord(word);
        w.setEnabled(true);
        w.setCreatedAt(OffsetDateTime.now());
        w.setUpdatedAt(OffsetDateTime.now());
        ForbiddenWord saved = repo.save(w);
        audit("CREATE", String.valueOf(saved.getWordId()), Map.of(
                "category", category, "word", word));
        return saved;
    }

    /** 启用/停用切换，返回是否实际发生变更（幂等：状态未变返回 false 不审计）。 */
    @Transactional
    @CacheEvict(cacheNames = ForbiddenWordCatalog.CACHE_NAME, allEntries = true)
    public boolean toggle(Long wordId, boolean enabled) {
        ForbiddenWord w = mustGet(wordId);
        if (Boolean.TRUE.equals(w.getEnabled()) == enabled) {
            return false;
        }
        w.setEnabled(enabled);
        w.setUpdatedAt(OffsetDateTime.now());
        repo.save(w);
        audit(enabled ? "ENABLE" : "DISABLE", String.valueOf(wordId), Map.of(
                "category", w.getCategory(), "word", w.getWord(), "enabled", enabled));
        return true;
    }

    /** 删除违禁词（停用词与自定义词均可删；返回是否实际删除）。 */
    @Transactional
    @CacheEvict(cacheNames = ForbiddenWordCatalog.CACHE_NAME, allEntries = true)
    public boolean delete(Long wordId) {
        ForbiddenWord w = repo.findById(wordId).orElse(null);
        if (w == null) {
            return false;
        }
        repo.delete(w);
        audit("DELETE", String.valueOf(wordId), Map.of(
                "category", w.getCategory(), "word", w.getWord()));
        return true;
    }

    // ==================== 内部方法 ====================

    private ForbiddenWord mustGet(Long wordId) {
        return repo.findById(wordId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "违禁词不存在：" + wordId));
    }

    private void audit(String action, String txnNo, Map<String, Object> payload) {
        try {
            audit.record("FORBIDDEN_WORD", txnNo, DataScope.currentActor(), action,
                    objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            audit.record("FORBIDDEN_WORD", txnNo, DataScope.currentActor(), action, "{}");
        }
    }

    // ==================== 命令 DTO ====================

    /** 新增违禁词命令。 */
    public record WordCmd(String category, String word) {}

    /** 启用/停用命令（enabled 缺省视为启用）。 */
    public record ToggleCmd(Boolean enabled) {}
}
