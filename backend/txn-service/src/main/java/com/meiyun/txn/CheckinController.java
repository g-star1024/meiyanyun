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
 * M2-09 会员到店核销：今日到店队列 + 登记（扫码/预约/直接到店）+ 确认核销 + 异常标记/解除。
 *
 * <p>队列读模型对齐前端 stores/checkin.ts 的 CheckinRecord 形状（id/no/customerName/phone/project/
 * method/status/exceptionReason/arrivedAt/checkedAt/operator/note/timeline）；锚定客户姓名以客户域为准富化，
 * 散客回登记快照，手机号一律掩码。写动作四件套：入参校验（中文 4xx）、幂等（登记按当日同号 PENDING /
 * 核销按 DONE 态）、全动作审计（CHECKIN）、越权统一 404。
 */
@RestController
@RequestMapping("/api/txn/checkin")
public class CheckinController {

    private final CheckinService service;
    private final ApptRefNameResolver names;

    public CheckinController(CheckinService service, ApptRefNameResolver names) {
        this.service = service;
        this.names = names;
    }

    /** 到店队列：默认今日，支持日期/门店/方式/状态过滤；数据域强制注入。 */
    @GetMapping("/records")
    @RequirePerm("checkin:view")
    public List<CiView> records(@RequestParam(required = false) LocalDate date,
                                @RequestParam(required = false) String storeCode,
                                @RequestParam(required = false) String method,
                                @RequestParam(required = false) String status) {
        LocalDate day = date != null ? date : LocalDate.now();
        return toViews(service.list(day, storeCode, method, status));
    }

    /** 登记到店：门店取 JWT 当前本店；手机号锚定客户，未命中落快照散客；当日同号仍待确认幂等返回既有单。 */
    @PostMapping("/records")
    @RequirePerm("checkin:create")
    public CiView register(@RequestBody @Valid RegisterCmd cmd) {
        CheckinRecord t = service.register(cmd.customerName(), cmd.phone(), cmd.project(), cmd.method());
        return toViews(List.of(t)).get(0);
    }

    /** 确认核销：PENDING → DONE。 */
    @PostMapping("/records/{ciNo}/confirm")
    @RequirePerm("checkin:create")
    public CiView confirm(@PathVariable String ciNo) {
        return toViews(List.of(service.confirm(ciNo))).get(0);
    }

    /** 标记异常（DONE 不可标；EXCEPTION 允许改标原因）。 */
    @PostMapping("/records/{ciNo}/exception")
    @RequirePerm("checkin:create")
    public CiView exception(@PathVariable String ciNo, @RequestBody @Valid ExceptionCmd cmd) {
        return toViews(List.of(service.markException(ciNo, cmd.reason(), cmd.note()))).get(0);
    }

    /** 解除异常：恢复待确认。 */
    @PostMapping("/records/{ciNo}/reset")
    @RequirePerm("checkin:create")
    public CiView reset(@PathVariable String ciNo) {
        return toViews(List.of(service.reset(ciNo))).get(0);
    }

    // ---- 读模型富化 ----

    private List<CiView> toViews(List<CheckinRecord> list) {
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> customerIds = list.stream()
                .map(CheckinRecord::getCustomerId)
                .filter(s -> s != null && !s.isBlank())
                .distinct()
                .toList();
        Map<String, String> custNames = names.customerNames(customerIds);
        Map<String, String> staffNames = names.staffNames(
                list.stream().map(CheckinRecord::getOperator).distinct().toList());
        List<CiView> out = new ArrayList<>(list.size());
        for (CheckinRecord t : list) {
            String customerName = (t.getCustomerId() != null && !t.getCustomerId().isBlank())
                    ? custNames.getOrDefault(t.getCustomerId(), t.getCustomerName())
                    : t.getCustomerName();
            out.add(new CiView(
                    t.getCiNo(), t.getCiNo(),
                    t.getCustomerId(), t.getStoreCode(),
                    customerName, t.getPhone(),
                    t.getProject(), t.getMethod(), t.getStatus(), t.getExceptionReason(),
                    t.getArrivedAt(), t.getCheckedAt(),
                    staffNames.getOrDefault(t.getOperator(), t.getOperator()),
                    t.getNote(),
                    service.readTimeline(t.getTimeline())));
        }
        return out;
    }

    /** 到店核销读模型（对齐前端 CheckinRecord 形状）；customerId/storeCode 为内部字段，页面不渲染。 */
    public record CiView(
            String id, String no,
            String customerId, String storeCode,
            String customerName, String phone,
            String project, String method, String status, String exceptionReason,
            OffsetDateTime arrivedAt, OffsetDateTime checkedAt,
            String operator, String note,
            List<Map<String, String>> timeline) {}

    public record RegisterCmd(
            @NotBlank String customerName,
            @NotBlank String phone,
            @NotBlank String project,
            @NotBlank String method) {}

    public record ExceptionCmd(@NotBlank String reason, String note) {}
}
