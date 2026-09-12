package com.meiyun.ai.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiSensitiveHit;
import com.meiyun.ai.domain.AiSensitiveHitRepository;
import com.meiyun.ai.domain.AiSensitiveWord;
import com.meiyun.ai.domain.AiSensitiveWordRepository;
import com.meiyun.ai.feature.FeatureCatalog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 敏感词治理台：词库 CRUD（含启停）、命中记录分页/统计、误报标注。
 * 命中次数为 append-only 的 ai_sensitive_hit 按 word_id 实时聚合，词表自身不存计数列。
 */
@Service
public class SensitiveWordAdminService {

    private static final int PAGE_MAX = 200;
    private static final int WORD_MAX = 128;

    private final AiSensitiveWordRepository wordRepo;
    private final AiSensitiveHitRepository hitRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public SensitiveWordAdminService(AiSensitiveWordRepository wordRepo,
                                     AiSensitiveHitRepository hitRepo,
                                     AuditRecorder audit) {
        this.wordRepo = wordRepo;
        this.hitRepo = hitRepo;
        this.audit = audit;
    }

    public record WordCmd(String word, String category, Boolean enabled) {
    }

    public record WordView(Long wordId, String word, String category, boolean enabled,
                           long hits, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record SaveResult(boolean changed, Long wordId) {
    }

    public record HitView(Long hitId, OffsetDateTime hitAt, Long wordId, String word, String category,
                          String featureCode, String featureName, String staffName, String storeCode,
                          String contextSnippet, boolean falsePositive, String markedBy, OffsetDateTime markedAt) {
    }

    public record HitStats(long todayHits, long totalHits, long falsePositiveHits,
                           long totalWords, long enabledWords) {
    }

    @Transactional(readOnly = true)
    public Page<HitView> hits(String category, int page, int size) {
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        String cat = normalizeCategoryOrNull(category);
        Page<AiSensitiveHit> result = cat == null
                ? hitRepo.findAllByOrderByHitIdDesc(pageable)
                : hitRepo.findByCategoryOrderByHitIdDesc(cat, pageable);
        return result.map(this::toHitView);
    }

    @Transactional(readOnly = true)
    public HitStats stats() {
        OffsetDateTime todayStart = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        return new HitStats(
                hitRepo.countByHitAtGreaterThanEqual(todayStart),
                hitRepo.count(),
                hitRepo.countByFalsePositiveTrue(),
                wordRepo.count(),
                wordRepo.countByEnabledTrue());
    }

    @Transactional(readOnly = true)
    public java.util.List<WordView> words() {
        Map<Long, Long> hitsMap = new HashMap<>();
        for (Object[] row : hitRepo.countGroupByWord()) {
            if (row[0] != null) {
                hitsMap.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
            }
        }
        return wordRepo.findAllByOrderByWordIdDesc().stream()
                .map(w -> new WordView(w.getWordId(), w.getWord(), w.getCategory(),
                        Boolean.TRUE.equals(w.getEnabled()),
                        hitsMap.getOrDefault(w.getWordId(), 0L),
                        w.getCreatedAt(), w.getUpdatedAt()))
                .toList();
    }

    @Transactional
    public SaveResult create(WordCmd cmd, String actor) {
        String word = normalizeWord(cmd == null ? null : cmd.word());
        String category = normalizeCategory(cmd == null ? null : cmd.category());
        boolean enabled = !Boolean.FALSE.equals(cmd.enabled());
        if (wordRepo.findByWordAndCategory(word, category).isPresent()) {
            throw badRequest("敏感词「" + word + "」在该词类下已存在，请勿重复添加");
        }
        AiSensitiveWord w = new AiSensitiveWord();
        w.setWord(word);
        w.setCategory(category);
        w.setEnabled(enabled);
        w = wordRepo.save(w);
        audit.record("AI_SENSITIVE_WORD", "WORD-" + w.getWordId(), actor, "CREATE",
                payload(Map.of("word", word, "category", category, "enabled", enabled)));
        return new SaveResult(true, w.getWordId());
    }

    @Transactional
    public SaveResult update(Long id, WordCmd cmd, String actor) {
        AiSensitiveWord w = wordRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "敏感词不存在（id=" + id + "）"));
        String word = normalizeWord(cmd == null ? null : cmd.word());
        String category = normalizeCategory(cmd == null ? null : cmd.category());
        boolean enabled = !Boolean.FALSE.equals(cmd.enabled());
        AiSensitiveWord dup = wordRepo.findByWordAndCategory(word, category).orElse(null);
        if (dup != null && !Objects.equals(dup.getWordId(), id)) {
            throw badRequest("敏感词「" + word + "」在该词类下已存在，请勿重复添加");
        }
        boolean changed = !Objects.equals(w.getWord(), word)
                || !Objects.equals(w.getCategory(), category)
                || Boolean.TRUE.equals(w.getEnabled()) != enabled;
        if (!changed) {
            return new SaveResult(false, id);
        }
        w.setWord(word);
        w.setCategory(category);
        w.setEnabled(enabled);
        wordRepo.save(w);
        audit.record("AI_SENSITIVE_WORD", "WORD-" + id, actor, "UPDATE",
                payload(Map.of("word", word, "category", category, "enabled", enabled)));
        return new SaveResult(true, id);
    }

    /** 误报标注：幂等（重复标注返回 changed=false，不重复写审计）。 */
    @Transactional
    public SaveResult markFalsePositive(Long id, String actor) {
        AiSensitiveHit hit = hitRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "命中记录不存在（id=" + id + "）"));
        if (Boolean.TRUE.equals(hit.getFalsePositive())) {
            return new SaveResult(false, id);
        }
        hit.setFalsePositive(true);
        hit.setMarkedBy(actor);
        hit.setMarkedAt(OffsetDateTime.now());
        hitRepo.save(hit);
        audit.record("AI_SENSITIVE_HIT", "HIT-" + id, actor, "MARK_FALSE_POSITIVE",
                payload(Map.of("word", hit.getWord(), "category", hit.getCategory())));
        return new SaveResult(true, id);
    }

    private HitView toHitView(AiSensitiveHit h) {
        return new HitView(h.getHitId(), h.getHitAt(), h.getWordId(), h.getWord(), h.getCategory(),
                h.getFeatureCode(), FeatureCatalog.nameOf(h.getFeatureCode() == null ? "" : h.getFeatureCode()),
                h.getStaffName(), h.getStoreCode(), h.getContextSnippet(),
                Boolean.TRUE.equals(h.getFalsePositive()), h.getMarkedBy(), h.getMarkedAt());
    }

    private String normalizeWord(String word) {
        if (word == null || word.isBlank()) {
            throw badRequest("敏感词内容不能为空");
        }
        String w = word.trim();
        if (w.length() > WORD_MAX) {
            throw badRequest("敏感词内容不能超过 " + WORD_MAX + " 字");
        }
        return w;
    }

    private String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            throw badRequest("词类不能为空");
        }
        String c = category.trim().toUpperCase();
        if (!SensitiveWordService.BANNED.equals(c) && !SensitiveWordService.INJECTION.equals(c)) {
            throw badRequest("词类仅支持 BANNED（违禁内容）/ INJECTION（越权提示词）");
        }
        return c;
    }

    private String normalizeCategoryOrNull(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        return normalizeCategory(category);
    }

    private static ResponseStatusException badRequest(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private String payload(Map<String, ?> data) {
        try {
            return json.writeValueAsString(data);
        } catch (Exception e) {
            return "{}";
        }
    }
}
