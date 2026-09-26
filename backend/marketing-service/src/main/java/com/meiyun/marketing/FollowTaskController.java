package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

/**
 * 跟进任务端点（M3-B2，/m3-follow-tasks 切真，DESIGN-M3 §3 D1）。
 *
 * <p>权限：类级 followuptask:view（列表/KPI），create/complete/reassign/logs 方法级
 * followuptask:edit（PermissionMatrix 预埋零新码）。任务标识=follow_no（与前端视图 id 一致）。
 */
@RestController
@RequestMapping("/api/marketing/follow-tasks")
@RequirePerm("followuptask:view")
public class FollowTaskController {

    private final FollowTaskService followTaskService;

    public FollowTaskController(FollowTaskService followTaskService) {
        this.followTaskService = followTaskService;
    }

    /** 列表+KPI 同响应（status=PENDING/DONE/OVERDUE/ALL，storeCode 精确，NULL 行=全连锁可见）。 */
    @GetMapping
    public FollowTaskService.ListResp list(@RequestParam(required = false) String status,
                                           @RequestParam(required = false) String storeCode) {
        return followTaskService.list(status, storeCode);
    }

    @PostMapping
    @RequirePerm("followuptask:edit")
    public FollowTaskService.FollowTaskView create(@RequestBody FollowTaskService.CreateCmd cmd) {
        return followTaskService.create(cmd);
    }

    @PostMapping("/{followNo}/complete")
    @RequirePerm("followuptask:edit")
    public FollowTaskService.FollowTaskView complete(@PathVariable String followNo) {
        return followTaskService.complete(followNo);
    }

    @PostMapping("/{followNo}/reassign")
    @RequirePerm("followuptask:edit")
    public FollowTaskService.FollowTaskView reassign(@PathVariable String followNo,
                                                     @RequestBody FollowTaskService.ReassignCmd cmd) {
        return followTaskService.reassign(followNo, cmd);
    }

    @PostMapping("/{followNo}/logs")
    @RequirePerm("followuptask:edit")
    public FollowTaskService.FollowTaskView addLog(@PathVariable String followNo,
                                                   @RequestBody FollowTaskService.LogCmd cmd) {
        return followTaskService.addLog(followNo, cmd);
    }
}
