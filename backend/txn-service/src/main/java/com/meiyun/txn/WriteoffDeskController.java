package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M2-01 划扣核销台：今日待划扣队列 + 双签执行 + 异常标记/解除 + 老客未预约手工建单。
 *
 * <p>队列读模型富化客户名/掩码手机号/门店名/卡项名/操作人名（零英文技术码外露）；
 * 写动作四件套：入参校验（中文 4xx）、幂等（签到按预约号 / 执行按 DONE 态）、全动作审计（WDESK）、越权统一 404。
 * 金额单位「分」，前端 fen2yuan 展示。
 */
@RestController
@RequestMapping("/api/txn/writeoff-desk")
public class WriteoffDeskController {

    private final WriteoffDeskService service;
    private final ApptRefNameResolver names;
    private final MemberCardRepository cardRepo;

    public WriteoffDeskController(WriteoffDeskService service, ApptRefNameResolver names,
                                  MemberCardRepository cardRepo) {
        this.service = service;
        this.names = names;
        this.cardRepo = cardRepo;
    }

    /** 待划扣队列：默认今日，支持日期/门店/状态过滤；数据域强制注入。 */
    @GetMapping("/tasks")
    @RequirePerm("writeoffdesk:view")
    public List<WdView> tasks(@RequestParam(required = false) LocalDate date,
                              @RequestParam(required = false) String storeCode,
                              @RequestParam(required = false) String status) {
        LocalDate day = date != null ? date : LocalDate.now();
        List<WriteoffDeskTask> list = service.list(day, storeCode, status);
        return toViews(list);
    }

    /** 老客未预约直接到店：手工建划扣任务（WALKIN）。新客须先走登记分诊预约，不在此列。 */
    @PostMapping("/tasks")
    @RequirePerm("writeoff:create")
    public WdView createWalkin(@RequestBody @Valid WalkinCmd cmd) {
        WriteoffDeskTask t = service.createWalkin(cmd.customerId(), cmd.storeCode(),
                cmd.project(), cmd.cardNo());
        return toViews(List.of(t)).get(0);
    }

    /** 双签划扣执行：body 复核人必填、卡选择器可选（缺省用任务绑定卡）。 */
    @PostMapping("/tasks/{wdNo}/execute")
    @RequirePerm("writeoff:create")
    public WdView execute(@PathVariable String wdNo, @RequestBody @Valid ExecuteCmd cmd) {
        WriteoffDeskTask t = service.execute(wdNo, cmd.reviewer(), cmd.cardNo(), cmd.remark());
        return toViews(List.of(t)).get(0);
    }

    /** 标记异常（DONE 不可标）。 */
    @PostMapping("/tasks/{wdNo}/exception")
    @RequirePerm("writeoff:edit")
    public WdView exception(@PathVariable String wdNo, @RequestBody @Valid ExceptionCmd cmd) {
        WriteoffDeskTask t = service.markException(wdNo, cmd.reason(), cmd.note());
        return toViews(List.of(t)).get(0);
    }

    /** 解除异常：恢复待执行队列。 */
    @PostMapping("/tasks/{wdNo}/reset")
    @RequirePerm("writeoff:edit")
    public WdView reset(@PathVariable String wdNo) {
        WriteoffDeskTask t = service.reset(wdNo);
        return toViews(List.of(t)).get(0);
    }

    /**
     * 客户本店在用卡列表（双签弹窗卡选择器 / 手工建单选卡）。
     * 返回卡项名/总次/余次/余额（分）/单次均价（分），前端直接渲染。
     */
    @GetMapping("/customer-cards")
    @RequirePerm("writeoffdesk:view")
    public List<CardOption> customerCards(@RequestParam String customerId,
                                          @RequestParam(required = false) String storeCode) {
        List<MemberCard> cards = service.activeCards(customerId, storeCode);
        List<CardOption> out = new ArrayList<>();
        for (MemberCard c : cards) {
            long unit = c.getBalance() != null && c.getRemainTimes() != null && c.getRemainTimes() > 0
                    ? c.getBalance() / c.getRemainTimes() : 0L;
            out.add(new CardOption(c.getCardNo(), c.getCardItem(), c.getStoreCode(),
                    c.getTotalTimes(), c.getRemainTimes(), c.getBalance(), unit));
        }
        return out;
    }

    // ---- 读模型富化 ----

    private List<WdView> toViews(List<WriteoffDeskTask> list) {
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> custNames = names.customerNames(
                list.stream().map(WriteoffDeskTask::getCustomerId).toList());
        Map<String, String> phones = names.customerPhones(
                list.stream().map(WriteoffDeskTask::getCustomerId).toList());
        Map<String, String> storeNames = names.storeNames(
                list.stream().map(WriteoffDeskTask::getStoreCode).toList());
        Map<String, String> staffNames = names.staffNames(
                list.stream().map(WriteoffDeskTask::getOperator).toList());
        List<String> cardNos = list.stream().map(WriteoffDeskTask::getCardNo)
                .filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, MemberCard> cardMap = new LinkedHashMap<>();
        if (!cardNos.isEmpty()) {
            for (MemberCard c : cardRepo.findAllById(cardNos)) {
                cardMap.put(c.getCardNo(), c);
            }
        }
        List<WdView> out = new ArrayList<>(list.size());
        for (WriteoffDeskTask t : list) {
            MemberCard card = t.getCardNo() == null ? null : cardMap.get(t.getCardNo());
            int total = card != null && card.getTotalTimes() != null ? card.getTotalTimes() : 0;
            int remain = card != null && card.getRemainTimes() != null ? card.getRemainTimes() : 0;
            String cardName = card != null ? card.getCardItem() : null;
            out.add(new WdView(
                    t.getWdNo(), t.getWdNo(),
                    t.getCustomerId(), t.getStoreCode(), t.getCardNo(),
                    custNames.getOrDefault(t.getCustomerId(), t.getCustomerId()),
                    phones.getOrDefault(t.getCustomerId(), ""),
                    t.getProject(), cardName, total, remain,
                    t.getAmount(),
                    staffNames.getOrDefault(t.getOperator(), t.getOperator()),
                    t.getReviewer(),
                    t.getSource(), t.getStatus(), t.getExceptionReason(),
                    t.getAppointmentTime(), t.getExecutedAt(),
                    service.readTimeline(t.getTimeline())));
        }
        return out;
    }

    /**
     * 核销台任务读模型（对齐前端 stores/writeoffDesk.ts 的 WriteoffDeskItem 形状；amount 单位：分）。
     * customerId/storeCode/cardNo 为内部字段：供双签卡选择器/手工建单反查，页面不渲染。
     */
    public record WdView(
            String id, String no,
            String customerId, String storeCode, String cardNo,
            String customerName, String phone,
            String project, String cardName,
            int totalCount, int remainingCount,
            long amount,
            String operator, String reviewer,
            String source, String status, String exceptionReason,
            OffsetDateTime appointmentTime, OffsetDateTime executedAt,
            List<Map<String, String>> timeline) {}

    /** 卡选择器选项。 */
    public record CardOption(String cardNo, String cardName, String storeCode,
                             int totalTimes, int remainTimes, long balance, long unitAmount) {}

    public record WalkinCmd(
            @NotBlank String customerId,
            @NotBlank String storeCode,
            @NotBlank String project,
            String cardNo) {}

    public record ExecuteCmd(@NotBlank String reviewer, String cardNo, String remark) {}

    public record ExceptionCmd(@NotBlank String reason, String note) {}
}
