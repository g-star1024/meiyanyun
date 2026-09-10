package com.meiyun.txn;

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
 * 接待台 / 候诊分诊：到店登记、分诊入位、改派、叫号、完成。
 *
 * <p>队列读模型富化客户名/掩码手机号 + 内联当前分诊单（类型/负责人姓名）；
 * 写动作四件套：入参校验（中文 4xx）、幂等（预约按 apptNo / 叫号完成按终态）、全动作审计（ARRIVAL）、越权统一 404。
 */
@RestController
@RequestMapping("/api/txn/arrivals")
public class ArrivalController {

    private final ArrivalService service;
    private final ApptRefNameResolver names;
    private final TriageRepository triageRepo;
    private final TriageReassignRepository reassignRepo;

    public ArrivalController(ArrivalService service, ApptRefNameResolver names,
                             TriageRepository triageRepo, TriageReassignRepository reassignRepo) {
        this.service = service;
        this.names = names;
        this.triageRepo = triageRepo;
        this.reassignRepo = reassignRepo;
    }

    /** 今日队列：默认今日，支持日期/门店/状态过滤；数据域强制注入。接待台与候诊看板共用一次拉全。 */
    @GetMapping
    @RequirePerm({"reception:view", "queue:view"})
    public List<ArrivalView> list(@RequestParam(required = false) LocalDate date,
                                  @RequestParam(required = false) String storeCode,
                                  @RequestParam(required = false) String status) {
        LocalDate day = date != null ? date : LocalDate.now();
        return toViews(service.list(day, storeCode, status));
    }

    /** 前台手工到店登记（门店取 JWT 当前本店，body 不接收 storeCode）。 */
    @PostMapping
    @RequirePerm("reception:edit")
    public ArrivalView create(@RequestBody @Valid CheckInCmd cmd) {
        Arrival a = service.create(cmd.customerId(), cmd.channel(), cmd.note());
        return toViews(List.of(a)).get(0);
    }

    /** 分诊入位：WAITING → TRIAGED，同事务建 consult_plan 空草稿（planId 随分诊单回查）。 */
    @PostMapping("/{ahNo}/triage")
    @RequirePerm("reception:edit")
    public ArrivalView triage(@PathVariable String ahNo, @RequestBody @Valid TriageCmd cmd) {
        Arrival a = service.triage(ahNo, cmd.type(), cmd.assignedTo(), cmd.note());
        return toViews(List.of(a)).get(0);
    }

    /** 改派：更新当前分诊单 forwardedTo 并追加改派历史（triage.reassignHistory 正序返回）。 */
    @PostMapping("/{ahNo}/reassign")
    @RequirePerm("reception:edit")
    public ArrivalView reassign(@PathVariable String ahNo, @RequestBody @Valid ReassignCmd cmd) {
        Arrival a = service.reassign(ahNo, cmd.newAssignedTo());
        return toViews(List.of(a)).get(0);
    }

    /** 叫号：TRIAGED → CALLED（幂等）。 */
    @PostMapping("/{ahNo}/call")
    @RequirePerm("queue:edit")
    public ArrivalView call(@PathVariable String ahNo) {
        return toViews(List.of(service.call(ahNo))).get(0);
    }

    /** 完成接诊：TRIAGED/CALLED → DONE（幂等）。 */
    @PostMapping("/{ahNo}/done")
    @RequirePerm("queue:edit")
    public ArrivalView done(@PathVariable String ahNo) {
        return toViews(List.of(service.done(ahNo))).get(0);
    }

    /** 手工释放号源：WAITING → LEFT（leftAt 落库），同事务触发本店候补首位递补通知。 */
    @PostMapping("/{ahNo}/release")
    @RequirePerm("queue:edit")
    public ArrivalView release(@PathVariable String ahNo) {
        return toViews(List.of(service.release(ahNo))).get(0);
    }

    // ---- 读模型富化 ----

    private List<ArrivalView> toViews(List<Arrival> list) {
        if (list.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> ahNos = list.stream().map(Arrival::getAhNo).toList();
        Map<String, Triage> triageMap = new LinkedHashMap<>();
        for (Triage t : triageRepo.findByArrivalIdIn(ahNos)) {
            triageMap.put(t.getArrivalId(), t);
        }
        List<TriageReassign> histories = reassignRepo.findByArrivalIdInOrderByIdAsc(ahNos);
        Map<String, List<TriageReassign>> historyMap = new LinkedHashMap<>();
        for (TriageReassign h : histories) {
            historyMap.computeIfAbsent(h.getArrivalId(), k -> new ArrayList<>()).add(h);
        }
        Map<String, String> custNames = names.customerNames(
                list.stream().map(Arrival::getCustomerId).toList());
        Map<String, String> phones = names.customerPhones(
                list.stream().map(Arrival::getCustomerId).toList());
        List<String> staffIds = java.util.stream.Stream.concat(
                triageMap.values().stream()
                        .flatMap(t -> java.util.stream.Stream.of(t.getAssignedTo(), t.getForwardedTo())),
                histories.stream()
                        .flatMap(h -> java.util.stream.Stream.of(h.getFromStaff(), h.getToStaff(), h.getOperator())))
                .filter(s -> s != null && !s.isBlank()).distinct().toList();
        Map<String, String> staffNames = names.staffNames(staffIds);

        List<ArrivalView> out = new ArrayList<>(list.size());
        for (Arrival a : list) {
            Triage t = triageMap.get(a.getAhNo());
            out.add(new ArrivalView(
                    a.getAhNo(), a.getAhNo(),
                    a.getCustomerId(), a.getStoreCode(),
                    custNames.getOrDefault(a.getCustomerId(), a.getCustomerId()),
                    phones.getOrDefault(a.getCustomerId(), ""),
                    a.getChannel(), a.getQueueNo(), a.getStatus(), a.getNote(), a.getApptNo(),
                    a.getArrivedAt(), a.getCalledAt(), a.getDoneAt(), a.getLeftAt(),
                    toTriageView(t, historyMap.getOrDefault(a.getAhNo(), List.of()), staffNames)));
        }
        return out;
    }

    private TriageView toTriageView(Triage t, List<TriageReassign> histories, Map<String, String> staffNames) {
        if (t == null) {
            return null;
        }
        String owner = t.getForwardedTo() != null && !t.getForwardedTo().isBlank()
                ? t.getForwardedTo() : t.getAssignedTo();
        List<ReassignView> historyViews = histories.stream()
                .map(h -> new ReassignView(
                        h.getFromStaff(), nameOf(staffNames, h.getFromStaff()),
                        h.getToStaff(), nameOf(staffNames, h.getToStaff()),
                        h.getOperator(), nameOf(staffNames, h.getOperator()),
                        h.getCreatedAt()))
                .toList();
        return new TriageView(
                t.getTrNo(), t.getArrivalId(), t.getCustomerId(), t.getType(),
                t.getAssignedTo(), nameOf(staffNames, t.getAssignedTo()),
                t.getForwardedTo(), nameOf(staffNames, t.getForwardedTo()),
                staffNames.getOrDefault(owner, owner),
                t.getNote(), t.getPlanId(), t.getEditedBy(), t.getEditedAt(),
                historyViews);
    }

    private static String nameOf(Map<String, String> map, String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return map.getOrDefault(id, id);
    }

    /** 到店登记读模型（对齐前端 stores/arrival.ts 适配后的 Arrival 形状）。 */
    public record ArrivalView(
            String id, String ahNo,
            String customerId, String storeCode,
            String customerName, String phoneMask,
            String channel, int queueNo, String status, String note, String apptNo,
            OffsetDateTime arrivedAt, OffsetDateTime calledAt, OffsetDateTime doneAt,
            OffsetDateTime leftAt,
            TriageView triage) {}

    /** 内联分诊单读模型：assignedToName 为首诊负责人名，ownerName 为改派后当前负责人名。 */
    public record TriageView(
            String id, String arrivalId, String customerId, String type,
            String assignedTo, String assignedToName,
            String forwardedTo, String forwardedToName, String ownerName,
            String note, String planId, String editedBy, OffsetDateTime editedAt,
            List<ReassignView> reassignHistory) {}

    /** 改派历史读模型：时间正序（最早一次改派在前），from/to/operator 均富化中文名。 */
    public record ReassignView(
            String fromStaff, String fromStaffName,
            String toStaff, String toStaffName,
            String operator, String operatorName,
            OffsetDateTime createdAt) {}

    public record CheckInCmd(@NotBlank String customerId, String channel, String note) {}

    public record TriageCmd(@NotBlank String type, @NotBlank String assignedTo, String note) {}

    public record ReassignCmd(@NotBlank String newAssignedTo) {}
}
