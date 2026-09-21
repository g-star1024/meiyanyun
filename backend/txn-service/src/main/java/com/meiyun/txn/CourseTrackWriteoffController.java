package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * B83 卡2 L66 疗程跟踪详情弹层核销记录读端点。
 *
 * <p>路由：GET /api/txn/writeoffs/by-card/{cardNo}（按 cardNo 倒序取该卡全部核销流水）。
 * 权限码 course:view（与 G1 同权限，疗程跟踪详情查看）。数据域强制注入：
 * 取卡后校验 store_code 可读，越权统一 404（不泄露卡是否存在）。
 *
 * <p>operatorName 由 TxnStaffNameResolver 调 org-service /api/org/staff/name-map 批量解析，
 * 服务不可用时降级返回 null（前端兜底用工号展示），不阻断主流程。
 * 金额单位「分」，纯扣次为 0；status DONE=已核销，中文存储中文展示。
 */
@RestController
@RequestMapping("/api/txn")
public class CourseTrackWriteoffController {

    private final WriteoffRepository writeoffRepo;
    private final MemberCardRepository cardRepo;
    private final TxnStaffNameResolver staffNames;

    public CourseTrackWriteoffController(WriteoffRepository writeoffRepo,
                                         MemberCardRepository cardRepo,
                                         TxnStaffNameResolver staffNames) {
        this.writeoffRepo = writeoffRepo;
        this.cardRepo = cardRepo;
        this.staffNames = staffNames;
    }

    /**
     * 疗程跟踪详情弹层核销流水：GET /api/txn/writeoffs/by-card/{cardNo}。
     * 返回该卡全部核销记录（card_no 维度划扣扣次，约 304 条），时间倒序，对齐前端 AssetTxn 结构。
     */
    @GetMapping("/writeoffs/by-card/{cardNo}")
    @RequirePerm("course:view")
    public List<WriteoffRecordDTO> byCard(@PathVariable String cardNo) {
        // 取卡做数据域校验（卡不存在或越权统一 404，不泄露卡是否存在）。
        MemberCard card = cardRepo.findById(cardNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看"));
        if (!DataScope.canReadStore(card.getStoreCode())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "数据不存在或无权查看");
        }
        List<WriteoffRecord> records = writeoffRepo.findByCardNoOrderByCreatedAtDesc(cardNo);
        if (records.isEmpty()) {
            return List.of();
        }
        // 批量解析操作人姓名（org-service 不可用时降级为空 Map，名字返回 null）。
        Map<String, String> names = staffNames.staffNames(
                records.stream().map(WriteoffRecord::getOperator).toList());
        List<WriteoffRecordDTO> out = new ArrayList<>(records.size());
        for (WriteoffRecord w : records) {
            String op = w.getOperator();
            String opName = op == null ? null : names.get(op);
            out.add(new WriteoffRecordDTO(
                    w.getWriteoffId(),
                    w.getCardNo(),
                    w.getProject(),
                    w.getTimesUsed() == null ? 0 : w.getTimesUsed(),
                    w.getAmount() == null ? 0L : w.getAmount(),
                    op,
                    opName,
                    w.getCreatedAt(),
                    w.getStatus() == null ? "DONE" : w.getStatus()));
        }
        return out;
    }
}
