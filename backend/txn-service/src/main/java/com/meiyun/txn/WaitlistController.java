package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 排队智能候补（P5-B29 卡①）：候补登记、取消、到场转正式到店登记、候补队列查询。
 *
 * <p>读模型富化门店名/锚定客户姓名（散客回登记快照）；手机号一律掩码快照。
 * 写动作四件套：入参校验（中文 4xx）、幂等（活跃候补 409 / 终态拒重）、全动作审计（WAITLIST）、越权统一 404。
 * 自动递补不由本控制器触发，由超时释放 Job 在号源释放后调 {@link WaitlistService#promoteNext}。
 */
@RestController
@RequestMapping("/api/txn/waitlist")
public class WaitlistController {

    private final WaitlistService service;
    private final ApptRefNameResolver names;

    public WaitlistController(WaitlistService service, ApptRefNameResolver names) {
        this.service = service;
        this.names = names;
    }

    /** 候补队列：默认全部日期，支持期望日期/门店/状态过滤；数据域强制注入，登记时间正序（FIFO 顺位）。 */
    @GetMapping
    @RequirePerm({"reception:view", "queue:view"})
    public List<WaitlistView> list(@RequestParam(required = false) LocalDate expectDate,
                                   @RequestParam(required = false) String storeCode,
                                   @RequestParam(required = false) String status) {
        return toViews(service.list(expectDate, storeCode, status));
    }

    /** 候补登记：门店取 JWT 当前本店；手机号自动反查锚定客户，未命中落散客快照。 */
    @PostMapping
    @RequirePerm("reception:edit")
    public WaitlistView register(@RequestBody @Valid RegisterCmd cmd) {
        ArrivalWaitlist w = service.register(cmd.customerId(), cmd.customerName(), cmd.phone(),
                cmd.project(), cmd.expectDate(), cmd.note());
        return toViews(List.of(w)).get(0);
    }

    /** 取消候补：WAITING/NOTIFIED → CANCELLED。 */
    @PostMapping("/{wlNo}/cancel")
    @RequirePerm("reception:edit")
    public WaitlistView cancel(@PathVariable String wlNo) {
        return toViews(List.of(service.cancel(wlNo))).get(0);
    }

    /** 客户到场确认：→ FULFILLED 并在同事务生成正式到店登记（AH），回传 ahNo/queueNo。 */
    @PostMapping("/{wlNo}/fulfill")
    @RequirePerm("reception:edit")
    public WaitlistView fulfill(@PathVariable String wlNo, @RequestBody(required = false) FulfillCmd cmd) {
        String channel = cmd == null ? null : cmd.channel();
        return toViews(List.of(service.fulfill(wlNo, channel))).get(0);
    }

    // ---- 读模型富化 ----

    private List<WaitlistView> toViews(List<ArrivalWaitlist> list) {
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, String> storeNames = names.storeNames(
                list.stream().map(ArrivalWaitlist::getStoreCode).distinct().toList());
        Map<String, String> custNames = names.customerNames(
                list.stream().map(ArrivalWaitlist::getCustomerId)
                        .filter(s -> s != null && !s.isBlank()).distinct().toList());
        List<WaitlistView> out = new ArrayList<>(list.size());
        for (ArrivalWaitlist w : list) {
            String customerName = w.getCustomerId() != null && !w.getCustomerId().isBlank()
                    ? custNames.getOrDefault(w.getCustomerId(), w.getCustomerName())
                    : w.getCustomerName();
            out.add(new WaitlistView(
                    w.getWlNo(), w.getWlNo(),
                    w.getStoreCode(), storeNames.getOrDefault(w.getStoreCode(), w.getStoreCode()),
                    w.getCustomerId(), customerName, w.getPhone(),
                    w.getProject(), w.getExpectDate(), w.getStatus(),
                    w.getNotifiedAt(), w.getAhNo(), w.getOperator(), w.getNote(),
                    service.readTimeline(w.getTimeline()), w.getCreatedAt()));
        }
        return out;
    }

    /** 候补读模型（对齐前端候补卡片：锚定客户富化客户域姓名，散客回快照；phone 恒为掩码）。 */
    public record WaitlistView(
            String id, String wlNo,
            String storeCode, String storeName,
            String customerId, String customerName, String phone,
            String project, LocalDate expectDate, String status,
            OffsetDateTime notifiedAt, String ahNo, String operator, String note,
            List<Map<String, String>> timeline, OffsetDateTime createdAt) {}

    public record RegisterCmd(
            String customerId,
            @NotBlank String customerName,
            @NotBlank String phone,
            @NotBlank String project,
            LocalDate expectDate,
            String note) {}

    /** 到店渠道可选：WALK_IN（默认）/ REFERRAL / MARKETING；候补不允许 APPOINTMENT。 */
    public record FulfillCmd(String channel) {}
}
