package com.meiyun.txn;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 复购/资产转移共享并账组件（P5-B64 卡1）：把资产转移卡余额/次数搬移从胖控制器下沉为独立组件，
 * 供两个调用方复用，避免 ApprovalService ↔ RepurchaseService 循环依赖：
 * ① 三方签核小额（&lt;¥1000）即时完成，同事务并账；
 * ② 大额多级审批 FINANCE 终审 APPROVED，同事务回调并账。
 *
 * 红线：
 * ① 仅「资产转移」单搬卡账；「复购」单无卡账动作；
 * ② 账实校验失败抛 400 整事务回滚（待办不置 APPROVED、复购单不置已完成）；
 * ③ 终审重放靠复购单状态闸（非「审批中」→409）兜底，不重复并账；
 * ④ 不做状态写库（状态/审计由各调用方负责），本组件只搬卡账。
 */
@Component
public class RepurchaseTransferExecutor {

    private final MemberCardRepository cardRepo;

    public RepurchaseTransferExecutor(MemberCardRepository cardRepo) {
        this.cardRepo = cardRepo;
    }

    /**
     * 执行资产转移并账：来源卡扣减、目标卡增加（次数/余额），同事务保存。
     * 仅「资产转移」业务类型搬账；账实不足抛 400；来源/目标卡不存在抛 404。
     */
    public void executeTransfer(Repurchase r) {
        if (!"资产转移".equals(r.getBizType())) {
            return;
        }
        int times = r.getTransferTimes() == null ? 0 : r.getTransferTimes();
        long amount = r.getTransferAmount() == null ? 0L : r.getTransferAmount();
        MemberCard from = cardRepo.findById(r.getFromCardNo())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "来源卡不存在: " + r.getFromCardNo()));
        MemberCard to = cardRepo.findById(r.getToCardNo())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "目标卡不存在: " + r.getToCardNo()));
        if (from.getRemainTimes() < times) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "账实校验失败：来源卡剩余次数 " + from.getRemainTimes()
                            + " < 转移次数 " + times);
        }
        if (from.getBalance() < amount) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "账实校验失败：来源卡余额 " + from.getBalance()
                            + " 分 < 转移金额 " + amount + " 分");
        }
        from.setRemainTimes(from.getRemainTimes() - times);
        from.setBalance(from.getBalance() - amount);
        if (from.getRemainTimes() == 0 && from.getBalance() == 0) {
            from.setStatus("已用完");
        }
        to.setRemainTimes(to.getRemainTimes() + times);
        to.setBalance(to.getBalance() + amount);
        cardRepo.save(from);
        cardRepo.save(to);
    }
}
