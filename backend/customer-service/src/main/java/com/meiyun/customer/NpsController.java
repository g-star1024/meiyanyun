package com.meiyun.customer;

import com.meiyun.customer.audit.AuditRecorder;
import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * NPS 回访 REST（M3-B1 / DESIGN-M3 §3 M3-12）。读/写分别受 nps:view / nps:edit 约束。
 * 提交幂等 (customer_id, period) 由服务层保证；category 不接入参，服务层按 score 推导。
 */
@RestController
@RequestMapping("/api/customer/m3/nps")
public class NpsController {

    private final NpsService service;
    private final AuditRecorder audit;

    public NpsController(NpsService service, AuditRecorder audit) {
        this.service = service;
        this.audit = audit;
    }

    /** 记录列表（提交时刻倒序；M3-12 列表/详情，前端内存过滤）。 */
    @GetMapping("/records")
    @RequirePerm("nps:view")
    public List<NpsRecord> records() {
        return service.list();
    }

    /** 汇总：分类计数/NPS 分/占比/回收率（与前端 computed 同口径）。 */
    @GetMapping("/summary")
    @RequirePerm("nps:view")
    public Map<String, Object> summary() {
        return service.summary();
    }

    /** 趋势：近 6 周 ISO 周聚合，无数据周补零（M3-12 六柱形态保真）。 */
    @GetMapping("/trends")
    @RequirePerm("nps:view")
    public List<Map<String, Object>> trends() {
        return service.trend();
    }

    /** 提交 NPS 回执：真实新建落 NPS/CREATE 审计；命中幂等返已存在记录（不重复落审计）。 */
    @PostMapping("/records")
    @RequirePerm("nps:edit")
    public NpsRecord submit(@RequestBody NpsSubmitReq req) {
        NpsService.SubmitResult result = service.submit(
                req == null ? null : req.customer(),
                req == null ? null : req.score(),
                req == null ? null : req.service(),
                req == null ? null : req.tags(),
                req == null ? null : req.comment());
        if (!result.dedup()) {
            NpsRecord r = result.record();
            audit.record("NPS", r.getRecordNo(), DataScope.currentActor(), "CREATE",
                    "{\"recordNo\":\"" + esc(r.getRecordNo()) + "\",\"customer\":\"" + esc(r.getCustomerName())
                            + "\",\"score\":" + r.getScore() + ",\"category\":\"" + esc(r.getCategory()) + "\"}");
        }
        return result.record();
    }

    /** 标记已跟进（备注必填，重复标记幂等覆盖）；落 NPS/FOLLOW 审计。 */
    @PostMapping("/records/{recordNo}/follow")
    @RequirePerm("nps:edit")
    public NpsRecord follow(@PathVariable String recordNo, @RequestBody FollowReq req) {
        NpsRecord r = service.markFollowed(recordNo, req == null ? null : req.note());
        audit.record("NPS", recordNo, DataScope.currentActor(), "FOLLOW",
                "{\"recordNo\":\"" + esc(recordNo) + "\",\"customer\":\"" + esc(r.getCustomerName()) + "\"}");
        return r;
    }

    private static String esc(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 提交请求体：customer/score 必填；category 不接（服务层按 score 推导，防伪造成交口径）。 */
    public record NpsSubmitReq(String customer, Integer score, String service, List<String> tags, String comment) {}

    /** 跟进请求体：note 必填（服务层校验 ≤500 字）。 */
    public record FollowReq(String note) {}
}
