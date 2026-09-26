package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跟进任务内部端点（M3-B2 / DESIGN-M3 §3 D3-1/2：AI 干预下发接线）。
 *
 * <p>仅供服务间调用（网关 isInternalPath 守卫外部 404 隐身），方法级 internal:finance-flow
 * 照 InternalTouchController 先例。AI 侧（churn intervene / repurchase action）经软降级 client 调用；
 * 幂等由 idemKey 部分唯一索引兜底（D8），重放直返既有任务不重复建单。
 */
@RestController
@RequestMapping("/api/marketing/internal/follow-tasks")
public class InternalFollowTaskController {

    private final FollowTaskService followTaskService;

    public InternalFollowTaskController(FollowTaskService followTaskService) {
        this.followTaskService = followTaskService;
    }

    /** AI 干预下发建任务（CHURN/REPURCHASE；idemKey=source:sourceId:customerId:date）。 */
    @PostMapping
    @RequirePerm("internal:finance-flow")
    public FollowTaskService.FollowTaskView create(@RequestBody FollowTaskService.InternalCreateCmd cmd) {
        return followTaskService.internalCreate(cmd);
    }
}
