package com.meiyun.ai.content;

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

/**
 * 渠道内容生成（公众号/海报/短信）业务出口。
 * 面向网关业务角色，类级仅需 aiGateway:view；能否真正出站由 content 功能的
 * ai_feature_role 灰度矩阵在 FeatureInvokeService 内判定，下发动作经审计链留痕。
 */
@RestController
@RequestMapping("/api/ai/content")
@RequirePerm("aiGateway:view")
public class ContentController {

    private final ContentService contentService;

    public ContentController(ContentService contentService) {
        this.contentService = contentService;
    }

    @PostMapping("/generate")
    public ContentService.ContentView generate(@RequestBody ContentService.ContentCmd cmd) {
        return contentService.generate(cmd);
    }

    @GetMapping("/records")
    public Page<ContentService.ContentView> records(@RequestParam(required = false) String channel,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "20") int size) {
        return contentService.history(channel, page, size);
    }

    @GetMapping("/stats")
    public ContentService.ContentStats stats() {
        return contentService.stats();
    }

    @PostMapping("/records/{id}/deploy")
    public ContentService.DeployResult deploy(@PathVariable Long id) {
        return contentService.deploy(id, DataScope.currentActor());
    }
}
