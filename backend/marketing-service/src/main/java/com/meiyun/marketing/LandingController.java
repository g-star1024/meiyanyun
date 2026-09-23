package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 落地页端点（P5-B88）。
 * 类级 landing:view 覆盖查询；写动作方法级 landing:edit（区域级权限，矩阵已播种零新增）。
 * 挂 /api/marketing 同前缀——既有网关 /api 转发规则直接覆盖，零改动。
 */
@RestController
@RequestMapping("/api/marketing")
@RequirePerm("landing:view")
public class LandingController {

    private final LandingService landingService;

    public LandingController(LandingService landingService) {
        this.landingService = landingService;
    }

    @GetMapping("/landing-pages")
    public List<LandingPage> pages() {
        return landingService.list();
    }

    /** 搭建落地页（草稿）；校验、敏感词拦截、client_token 幂等与审计在 {@link LandingService}。 */
    @PostMapping("/landing-pages")
    @RequirePerm("landing:edit")
    public LandingPage create(@RequestBody LandingService.LandingCmd cmd) {
        return landingService.create(cmd);
    }

    /** 发布（幂等：已发布 changed=false）。 */
    @PostMapping("/landing-pages/{id}/publish")
    @RequirePerm("landing:edit")
    public Map<String, Object> publish(@PathVariable String id) {
        return Map.of("changed", landingService.publish(id));
    }

    /** 下线（仅已发布可下线，其他状态 changed=false）。 */
    @PostMapping("/landing-pages/{id}/offline")
    @RequirePerm("landing:edit")
    public Map<String, Object> offline(@PathVariable String id) {
        return Map.of("changed", landingService.offline(id));
    }

    /** 组件块上移/下移（越界或块不存在 changed=false）。 */
    @PostMapping("/landing-pages/{id}/blocks/move")
    @RequirePerm("landing:edit")
    public Map<String, Object> moveBlock(@PathVariable String id,
                                         @RequestBody LandingService.MoveBlockCmd cmd) {
        boolean changed = landingService.moveBlock(id, cmd == null ? null : cmd.blockId(),
                cmd == null ? null : cmd.direction());
        return Map.of("changed", changed);
    }

    /** A/B 开关（开启时初始化演示变体）。 */
    @PostMapping("/landing-pages/{id}/ab/toggle")
    @RequirePerm("landing:edit")
    public Map<String, Object> toggleAb(@PathVariable String id) {
        return Map.of("changed", landingService.toggleAb(id));
    }
}
