package com.meiyun.ai.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.meiyun.ai.audit.AuditRecorder;
import com.meiyun.ai.domain.AiKnowledgeCitation;
import com.meiyun.ai.domain.AiKnowledgeCitationRepository;
import com.meiyun.ai.domain.AiKnowledgeItem;
import com.meiyun.ai.domain.AiKnowledgeItemRepository;
import com.meiyun.security.LoginUser;
import com.meiyun.security.SecurityContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * AI 知识库：条目 CRUD + PG 词法检索（ILIKE 标题/标签/正文加权召回）+ 引用流水溯源。
 *
 * 诚实口径：
 * 1. 当前「向量化/索引」为词法检索可用状态（index_status=INDEXED 即可被检索），
 *    语义向量依赖 EMBEDDING 多能力模型与向量库，属远期 Backlog，不伪造向量维度与相似度；
 * 2. 引用流水仅来自本页真实检索（source_feature=manual_search），话术/客服/内容生成的
 *    RAG 自动联动为远期，枚举预留 scripts/chatbot/content，但不编造跨模块引用；
 * 3. 「引用准确率」不写死，由 citation 有用/无用反馈真实计算，无反馈时返回 null，前端显 —。
 */
@Service
public class KnowledgeService {

    private static final int PAGE_MAX = 200;
    private static final int TITLE_MAX = 200;
    private static final int CONTENT_MAX = 8000;
    private static final int TAGS_MAX = 500;
    private static final int SOURCE_MAX = 200;
    private static final int QUERY_MAX = 200;
    private static final int SEARCH_LIMIT_MAX = 20;
    private static final int SNIPPET_LEN = 120;
    private static final int HOT_LIMIT = 8;
    private static final Set<String> CATEGORIES = Set.of("project", "script", "compliance");
    private static final String INDEX_NOTE =
            "词法检索索引（PG ILIKE 标题/标签/正文加权召回）；语义向量（EMBEDDING 模型）为远期能力";

    private final AiKnowledgeItemRepository itemRepo;
    private final AiKnowledgeCitationRepository citationRepo;
    private final AuditRecorder audit;
    private final ObjectMapper json = new ObjectMapper();

    public KnowledgeService(AiKnowledgeItemRepository itemRepo,
                            AiKnowledgeCitationRepository citationRepo,
                            AuditRecorder audit) {
        this.itemRepo = itemRepo;
        this.citationRepo = citationRepo;
        this.audit = audit;
    }

    public record DocView(Long docId, String title, String category, String content, String tags,
                          String source, String indexStatus, String indexNote, Long refsCount,
                          String staffId, String staffName, String storeCode,
                          OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    public record Stats(long totalDocs, long indexedCount, long pendingCount, long failedCount,
                        long indexedPct, long totalRefs, long todaySearches,
                        long feedbackTotal, Long usefulRatePct) {
    }

    public record SearchHit(Long citationId, Long docId, String title, String category,
                            String tags, String snippet, int rank, Long refsCount) {
    }

    public record CitationView(Long citationId, Long docId, String query, String sourceFeature,
                               Boolean useful, String staffName, OffsetDateTime createdAt) {
    }

    public record SaveCmd(String title, String category, String content, String tags, String source) {
    }

    public record FeedbackCmd(Boolean useful) {
    }

    @Transactional(readOnly = true)
    public Page<DocView> list(String category, String keyword, int page, int size) {
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        String cat = category == null || category.isBlank() ? "" : normalizeCategory(category);
        String kw = keyword == null || keyword.isBlank() ? "" : keyword.trim();
        return itemRepo.search(cat, kw, pageable).map(this::toView);
    }

    @Transactional(readOnly = true)
    public DocView get(Long id) {
        return toView(mustGet(id));
    }

    @Transactional(readOnly = true)
    public Stats stats() {
        OffsetDateTime bjMidnight = OffsetDateTime.now(ZoneOffset.ofHours(8))
                .toLocalDate().atStartOfDay().atOffset(ZoneOffset.ofHours(8));
        OffsetDateTime since30 = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(30);
        long total = itemRepo.count();
        long indexed = itemRepo.countByIndexStatus("INDEXED");
        long pending = itemRepo.countByIndexStatus("PENDING");
        long failed = itemRepo.countByIndexStatus("FAILED");
        long pct = total == 0 ? 0 : Math.round(indexed * 100.0 / total);
        long totalRefs = citationRepo.count();
        long todaySearches = citationRepo.countByCreatedAtGreaterThanEqual(bjMidnight);
        long feedbackTotal = citationRepo.countByUsefulNotNull();
        long useful = citationRepo.countByUseful(Boolean.TRUE);
        Long usefulRatePct = feedbackTotal == 0 ? null : Math.round(useful * 100.0 / feedbackTotal);
        return new Stats(total, indexed, pending, failed, pct, totalRefs, todaySearches,
                feedbackTotal, usefulRatePct);
    }

    /**
     * 真实检索：词法加权召回，每次命中写一条 manual_search 引用流水并累加条目 refs_count。
     * 引用即「该次检索真实调阅了此条目」；RAG 自动引用为远期 Backlog。
     */
    @Transactional
    public List<SearchHit> search(String rawQuery, Integer limit) {
        LoginUser user = requireUser();
        String q = normalizeQuery(rawQuery);
        int max = limit == null ? 10 : Math.min(Math.max(limit, 1), SEARCH_LIMIT_MAX);
        List<AiKnowledgeItem> hits = itemRepo.recall(q, max);
        List<SearchHit> views = new ArrayList<>();
        int rank = 1;
        for (AiKnowledgeItem doc : hits) {
            AiKnowledgeCitation c = new AiKnowledgeCitation();
            c.setDocId(doc.getDocId());
            c.setQuery(q);
            c.setSourceFeature("manual_search");
            c.setStaffId(user.staffId());
            c.setStaffName(user.staffName());
            c.setStoreCode(user.storeCode());
            AiKnowledgeCitation saved = citationRepo.save(c);
            doc.setRefsCount(doc.getRefsCount() + 1);
            itemRepo.save(doc);
            views.add(new SearchHit(saved.getCitationId(), doc.getDocId(), doc.getTitle(),
                    doc.getCategory(), doc.getTags(), snippet(doc.getContent()), rank++,
                    doc.getRefsCount()));
        }
        return views;
    }

    @Transactional(readOnly = true)
    public Page<CitationView> citations(Long docId, int page, int size) {
        mustGet(docId);
        int s = Math.min(Math.max(size, 1), PAGE_MAX);
        Pageable pageable = PageRequest.of(Math.max(page, 0), s);
        return citationRepo.findByDocIdOrderByCitationIdDesc(docId, pageable).map(this::toCitationView);
    }

    @Transactional(readOnly = true)
    public List<String> hotWords() {
        OffsetDateTime since30 = OffsetDateTime.now(ZoneOffset.ofHours(8)).minusDays(30);
        return citationRepo.hotQueries(since30, HOT_LIMIT).stream()
                .map(row -> (String) row[0])
                .toList();
    }

    // 刻意不加方法级事务：save 由仓储自身事务提交，随后 findById 全新读回时间戳。
    public DocView create(SaveCmd cmd, String actor) {
        LoginUser user = requireUser();
        String title = normalizeTitle(cmd == null ? null : cmd.title());
        String category = normalizeCategory(cmd == null ? null : cmd.category());
        String content = normalizeContent(cmd == null ? null : cmd.content());
        String tags = normalizeTags(cmd == null ? null : cmd.tags());
        String source = normalizeSource(cmd == null ? null : cmd.source());

        AiKnowledgeItem doc = new AiKnowledgeItem();
        doc.setTitle(title);
        doc.setCategory(category);
        doc.setContent(content);
        doc.setTags(tags);
        doc.setSource(source.isBlank() ? "人工录入" : source);
        doc.setIndexStatus("INDEXED");
        doc.setIndexNote(INDEX_NOTE);
        doc.setRefsCount(0L);
        doc.setStaffId(user.staffId());
        doc.setStaffName(user.staffName());
        doc.setStoreCode(user.storeCode());
        AiKnowledgeItem saved = itemRepo.save(doc);
        audit.record("AI_KNOWLEDGE", "KB-DOC-" + saved.getDocId(), actor, "CREATE",
                payload(Map.of("title", title, "category", category, "indexStatus", "INDEXED")));
        return itemRepo.findById(saved.getDocId()).map(this::toView).orElseGet(() -> toView(saved));
    }

    public DocView update(Long id, SaveCmd cmd, String actor) {
        AiKnowledgeItem doc = mustGet(id);
        String title = normalizeTitle(cmd == null ? null : cmd.title());
        String category = normalizeCategory(cmd == null ? null : cmd.category());
        String content = normalizeContent(cmd == null ? null : cmd.content());
        String tags = normalizeTags(cmd == null ? null : cmd.tags());
        String source = normalizeSource(cmd == null ? null : cmd.source());

        doc.setTitle(title);
        doc.setCategory(category);
        doc.setContent(content);
        doc.setTags(tags);
        if (!source.isBlank()) {
            doc.setSource(source);
        }
        // 编辑后重新进入词法索引（同步 ILIKE 无异步管道，直接置 INDEXED）
        doc.setIndexStatus("INDEXED");
        doc.setIndexNote(INDEX_NOTE);
        itemRepo.save(doc);
        audit.record("AI_KNOWLEDGE", "KB-DOC-" + id, actor, "UPDATE",
                payload(Map.of("title", title, "category", category)));
        return itemRepo.findById(id).map(this::toView).orElseGet(() -> toView(doc));
    }

    @Transactional
    public DocView reindex(Long id, String actor) {
        AiKnowledgeItem doc = mustGet(id);
        String before = doc.getIndexStatus();
        doc.setIndexStatus("INDEXED");
        doc.setIndexNote(INDEX_NOTE);
        itemRepo.save(doc);
        if (!"INDEXED".equals(before)) {
            audit.record("AI_KNOWLEDGE", "KB-DOC-" + id, actor, "REINDEX",
                    payload(Map.of("from", before, "to", "INDEXED")));
        }
        return itemRepo.findById(id).map(this::toView).orElseGet(() -> toView(doc));
    }

    @Transactional
    public CitationView feedback(Long citationId, FeedbackCmd cmd, String actor) {
        AiKnowledgeCitation citation = citationRepo.findById(citationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "引用记录不存在（id=" + citationId + "）"));
        if (cmd == null || cmd.useful() == null) {
            throw badRequest("反馈 useful 不能为空（true=有用 / false=无用）");
        }
        if (!cmd.useful().equals(citation.getUseful())) {
            citation.setUseful(cmd.useful());
            citationRepo.save(citation);
            audit.record("AI_KNOWLEDGE", "KB-CITE-" + citationId, actor, "FEEDBACK",
                    payload(Map.of("docId", citation.getDocId(), "useful", cmd.useful())));
        }
        return toCitationView(citation);
    }

    private AiKnowledgeItem mustGet(Long id) {
        return itemRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "知识条目不存在（id=" + id + "）"));
    }

    private DocView toView(AiKnowledgeItem d) {
        return new DocView(d.getDocId(), d.getTitle(), d.getCategory(), d.getContent(), d.getTags(),
                d.getSource(), d.getIndexStatus(), d.getIndexNote(), d.getRefsCount(),
                d.getStaffId(), d.getStaffName(), d.getStoreCode(), d.getCreatedAt(), d.getUpdatedAt());
    }

    private CitationView toCitationView(AiKnowledgeCitation c) {
        return new CitationView(c.getCitationId(), c.getDocId(), c.getQuery(), c.getSourceFeature(),
                c.getUseful(), c.getStaffName(), c.getCreatedAt());
    }

    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= SNIPPET_LEN ? content : content.substring(0, SNIPPET_LEN) + "…";
    }

    private LoginUser requireUser() {
        LoginUser user = SecurityContext.get();
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录或登录已失效，请重新登录");
        }
        return user;
    }

    private String normalizeQuery(String q) {
        if (q == null || q.isBlank()) {
            throw badRequest("检索词不能为空");
        }
        String t = q.trim();
        if (t.length() > QUERY_MAX) {
            throw badRequest("检索词不能超过 " + QUERY_MAX + " 字");
        }
        return t;
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            throw badRequest("知识标题不能为空");
        }
        String t = title.trim();
        if (t.length() > TITLE_MAX) {
            throw badRequest("知识标题不能超过 " + TITLE_MAX + " 字");
        }
        return t;
    }

    private String normalizeContent(String content) {
        if (content == null || content.isBlank()) {
            throw badRequest("正文内容不能为空");
        }
        String c = content.trim();
        if (c.length() > CONTENT_MAX) {
            throw badRequest("正文内容不能超过 " + CONTENT_MAX + " 字");
        }
        return c;
    }

    private String normalizeTags(String tags) {
        if (tags == null) {
            return null;
        }
        String t = tags.trim();
        if (t.length() > TAGS_MAX) {
            throw badRequest("检索标签不能超过 " + TAGS_MAX + " 字");
        }
        return t.isBlank() ? null : t;
    }

    private String normalizeSource(String source) {
        if (source == null) {
            return "";
        }
        String s = source.trim();
        if (s.length() > SOURCE_MAX) {
            throw badRequest("来源出处不能超过 " + SOURCE_MAX + " 字");
        }
        return s;
    }

    private String normalizeCategory(String category) {
        String c = normalizeCategoryOrNull(category);
        if (c == null) {
            throw badRequest("分类 category 仅支持 project（项目知识）/ script（方案话术）/ compliance（法规合规）");
        }
        return c;
    }

    private String normalizeCategoryOrNull(String category) {
        if (category == null || category.isBlank()) {
            return null;
        }
        String c = category.trim().toLowerCase();
        return CATEGORIES.contains(c) ? c : null;
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
