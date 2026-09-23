package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 关怀任务端点（P5-B90，/m3-care 切真，DESIGN §3）。
 *
 * <p>权限：类级 care:view（列表/KPI/模板），create/reach/convert 方法级 care:edit，
 * send 方法级 care:send（D3 零新码）。任务标识=care_no（与前端视图 id 一致）。
 */
@RestController
@RequestMapping("/api/marketing/care")
@RequirePerm("care:view")
public class CareController {

    private final CareService careService;

    public CareController(CareService careService) {
        this.careService = careService;
    }

    /** 列表+KPI 同响应（status=PENDING/SENT/REACHED/ALL，month=yyyy-MM，storeCode 精确）。 */
    @GetMapping("/tasks")
    public CareService.ListResp tasks(@RequestParam(required = false) String status,
                                      @RequestParam(required = false) String month,
                                      @RequestParam(required = false) String storeCode) {
        return careService.list(status, month, storeCode);
    }

    @PostMapping("/tasks")
    @RequirePerm("care:edit")
    public CareService.CareView create(@RequestBody CareService.CreateCmd cmd) {
        return careService.create(cmd);
    }

    /** 发送（SMS/WECHAT 经 PushService consent 门控；skipped=true=合规拦截未发送）。 */
    @PostMapping("/tasks/{careNo}/send")
    @RequirePerm("care:send")
    public CareService.SendResult send(@PathVariable String careNo) {
        return careService.send(careNo);
    }

    @PostMapping("/tasks/{careNo}/reach")
    @RequirePerm("care:edit")
    public CareService.CareView reach(@PathVariable String careNo, @RequestBody CareService.FlagCmd cmd) {
        return careService.reach(careNo, cmd != null && Boolean.TRUE.equals(cmd.reached()));
    }

    @PostMapping("/tasks/{careNo}/convert")
    @RequirePerm("care:edit")
    public CareService.CareView convert(@PathVariable String careNo, @RequestBody CareService.FlagCmd cmd) {
        return careService.convert(careNo, cmd != null && Boolean.TRUE.equals(cmd.converted()));
    }

    /** 关怀模板（marketing_cfg.care_templates，5 条播种对齐 mock）。 */
    @GetMapping("/templates")
    public List<CareService.TemplateView> templates() {
        return careService.templates();
    }
}
