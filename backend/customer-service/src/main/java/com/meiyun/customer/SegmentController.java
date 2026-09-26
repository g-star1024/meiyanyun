package com.meiyun.customer;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 客群分群端点（M3-14 / DESIGN-M3 §4 B3）：
 * segment_def CRUD + RULE 实时计算命中 + 命中批量跟进任务 + 一键回推标签 + 画像 apply 回推分群（D3-3 第三条）。
 *
 * <p>挂 /api/customer（铁律 1：网关 /api/customer 前缀已路由到 customer-service:8082）。
 * 权限码复用 segment:view/segment:edit（PermissionMatrix 预埋，零新码）。
 *
 * <p>九端点：
 * <ul>
 *   <li>GET    /api/customer/m3/segments — 分群列表（segment:view，每分群内嵌 top20 命中成员）</li>
 *   <li>GET    /api/customer/m3/segments/{id}/members — 命中成员全量（segment:view，500 截断）</li>
 *   <li>POST   /api/customer/m3/segments — 新建分群（segment:edit，clientToken 幂等）</li>
 *   <li>PUT    /api/customer/m3/segments/{id} — 更新分群（segment:edit）</li>
 *   <li>DELETE /api/customer/m3/segments/{id} — 删除分群（segment:edit）</li>
 *   <li>POST   /api/customer/m3/segments/{id}/refresh — 重算命中快照（segment:edit）</li>
 *   <li>POST   /api/customer/m3/segments/{id}/follow-tasks — 命中批量建跟进任务（segment:edit，软降级）</li>
 *   <li>POST   /api/customer/m3/segments/{id}/apply-tags — 命中成员一键回推标签（segment:edit）</li>
 *   <li>POST   /api/customer/m3/segments/sync-profile — 画像 apply 回推（tags 回写＋AI 分群 upsert，segment:edit）</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/customer/m3/segments")
public class SegmentController {

    private final SegmentService segmentService;

    public SegmentController(SegmentService segmentService) {
        this.segmentService = segmentService;
    }

    @GetMapping
    @RequirePerm("segment:view")
    public List<SegmentService.SegmentView> list() {
        return segmentService.list();
    }

    @GetMapping("/{id}/members")
    @RequirePerm("segment:view")
    public List<SegmentService.MemberView> members(@PathVariable("id") Long id) {
        return segmentService.members(id);
    }

    @PostMapping
    @RequirePerm("segment:edit")
    public SegmentService.SegmentView create(@RequestBody SegmentService.CreateCmd cmd) {
        return segmentService.create(cmd, DataScope.currentActor());
    }

    @PutMapping("/{id}")
    @RequirePerm("segment:edit")
    public SegmentService.SegmentView update(@PathVariable("id") Long id,
                                             @RequestBody SegmentService.UpdateCmd cmd) {
        return segmentService.update(id, cmd, DataScope.currentActor());
    }

    @DeleteMapping("/{id}")
    @RequirePerm("segment:edit")
    public void delete(@PathVariable("id") Long id) {
        segmentService.delete(id, DataScope.currentActor());
    }

    @PostMapping("/{id}/refresh")
    @RequirePerm("segment:edit")
    public SegmentService.RefreshResult refresh(@PathVariable("id") Long id) {
        return segmentService.refresh(id);
    }

    @PostMapping("/{id}/follow-tasks")
    @RequirePerm("segment:edit")
    public SegmentService.FollowTaskResult followTasks(@PathVariable("id") Long id) {
        return segmentService.followTasks(id, DataScope.currentActor());
    }

    @PostMapping("/{id}/apply-tags")
    @RequirePerm("segment:edit")
    public SegmentService.ApplyTagsResult applyTags(@PathVariable("id") Long id) {
        return segmentService.applyTags(id, DataScope.currentActor());
    }

    @PostMapping("/sync-profile")
    @RequirePerm("segment:edit")
    public SegmentService.SyncProfileResult syncProfile(@RequestBody SegmentService.SyncProfileCmd cmd) {
        return segmentService.syncProfile(cmd, DataScope.currentActor());
    }
}
