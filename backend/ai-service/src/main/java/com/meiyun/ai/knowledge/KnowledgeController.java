package com.meiyun.ai.knowledge;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * AI 知识库业务出口（A1-09 /ai/knowledge）。
 * 类级权限 aiKnowledge:view（REGION_MGR/STORE_MGR/SUPER_ADMIN 可见，已在 org-service 权限矩阵声明）。
 * 知识库为管理/检索面，非 AI invoke 功能（不接 FeatureCatalog/模型绑定/配额）；
 * 写动作（录入/编辑/重建索引/反馈）经审计链留痕，检索高频引用走 append-only ai_knowledge_citation。
 */
@RestController
@RequestMapping("/api/ai/knowledge")
@RequirePerm("aiKnowledge:view")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    @GetMapping
    public Page<KnowledgeService.DocView> list(@RequestParam(required = false) String category,
                                               @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return knowledgeService.list(category, keyword, page, size);
    }

    @GetMapping("/{id}")
    public KnowledgeService.DocView get(@PathVariable Long id) {
        return knowledgeService.get(id);
    }

    @GetMapping("/stats")
    public KnowledgeService.Stats stats() {
        return knowledgeService.stats();
    }

    @GetMapping("/hot")
    public List<String> hotWords() {
        return knowledgeService.hotWords();
    }

    @GetMapping("/search")
    public List<KnowledgeService.SearchHit> search(@RequestParam("q") String q,
                                                   @RequestParam(required = false) Integer limit) {
        return knowledgeService.search(q, limit);
    }

    @GetMapping("/{id}/citations")
    public Page<KnowledgeService.CitationView> citations(@PathVariable Long id,
                                                         @RequestParam(defaultValue = "0") int page,
                                                         @RequestParam(defaultValue = "10") int size) {
        return knowledgeService.citations(id, page, size);
    }

    @PostMapping
    public KnowledgeService.DocView create(@RequestBody KnowledgeService.SaveCmd cmd) {
        return knowledgeService.create(cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}")
    public KnowledgeService.DocView update(@PathVariable Long id,
                                           @RequestBody KnowledgeService.SaveCmd cmd) {
        return knowledgeService.update(id, cmd, DataScope.currentActor());
    }

    @PostMapping("/{id}/reindex")
    public KnowledgeService.DocView reindex(@PathVariable Long id) {
        return knowledgeService.reindex(id, DataScope.currentActor());
    }

    @PostMapping("/citations/{citationId}/feedback")
    public KnowledgeService.CitationView feedback(@PathVariable Long citationId,
                                                  @RequestBody KnowledgeService.FeedbackCmd cmd) {
        return knowledgeService.feedback(citationId, cmd, DataScope.currentActor());
    }
}
