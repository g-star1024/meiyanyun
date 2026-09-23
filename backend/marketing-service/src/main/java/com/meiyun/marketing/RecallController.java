package com.meiyun.marketing;

import com.meiyun.marketing.RecallService.ConfirmCmd;
import com.meiyun.marketing.RecallService.DetailCmd;
import com.meiyun.marketing.RecallService.ListResp;
import com.meiyun.marketing.RecallService.NotifyCmd;
import com.meiyun.marketing.RecallService.RecallView;
import com.meiyun.marketing.RecallService.RescheduleCmd;
import com.meiyun.marketing.RecallService.ScheduleCmd;
import com.meiyun.marketing.RecallService.SkipCmd;
import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 复诊召回 REST（P5-B90，/m3-recall 页）。七端点：查询 / 新建(schedule) / 通知(notify) / 确认(confirm) /
 * 到院登记(book) / 改约(reschedule) / 流失登记(skip)。权限复用 recall:view（查询）、recall:create（新建）、
 * recall:edit（其余动作），零新码（卡0 拍板 D3）。动作路径参数为业务单号 recallNo（前端 store id 同口径）。
 * 状态机 TRANSITIONS 由服务端权威判定，非法转移返回 409（见 RecallService）。
 */
@RestController
@RequestMapping("/api/marketing/recall")
@RequirePerm("recall:view")
public class RecallController {

    private final RecallService recallService;

    public RecallController(RecallService recallService) {
        this.recallService = recallService;
    }

    @GetMapping
    public ListResp list(@RequestParam(required = false) String status,
                         @RequestParam(required = false) String kw,
                         @RequestParam(required = false) String storeCode) {
        return recallService.list(status, kw, storeCode);
    }

    @PostMapping
    @RequirePerm("recall:create")
    public RecallView schedule(@RequestBody ScheduleCmd cmd) {
        return recallService.schedule(cmd);
    }

    @PostMapping("/{recallNo}/notify")
    @RequirePerm("recall:edit")
    public RecallView notify(@PathVariable String recallNo, @RequestBody(required = false) NotifyCmd cmd) {
        return recallService.notify(recallNo, cmd);
    }

    @PostMapping("/{recallNo}/confirm")
    @RequirePerm("recall:edit")
    public RecallView confirm(@PathVariable String recallNo, @RequestBody(required = false) ConfirmCmd cmd) {
        return recallService.confirm(recallNo, cmd);
    }

    @PostMapping("/{recallNo}/book")
    @RequirePerm("recall:edit")
    public RecallView book(@PathVariable String recallNo, @RequestBody(required = false) DetailCmd cmd) {
        return recallService.book(recallNo, cmd);
    }

    @PostMapping("/{recallNo}/reschedule")
    @RequirePerm("recall:edit")
    public RecallView reschedule(@PathVariable String recallNo, @RequestBody RescheduleCmd cmd) {
        return recallService.reschedule(recallNo, cmd);
    }

    @PostMapping("/{recallNo}/skip")
    @RequirePerm("recall:edit")
    public RecallView skip(@PathVariable String recallNo, @RequestBody SkipCmd cmd) {
        return recallService.skip(recallNo, cmd);
    }
}
