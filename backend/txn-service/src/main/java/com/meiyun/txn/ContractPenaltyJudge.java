package com.meiyun.txn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * B95 合同冷静期/违约金判定器（DESIGN §3 D2/D3/D6，退款 RF / 退卡 CC 双链复用）：
 * <ul>
 *   <li>四重校验（400 中文）：合同存在 → 状态「生效中」 → 客户匹配 → 计算基数 ≤ 合同总额；</li>
 *   <li>D2 窗口判定：inCooling = effectiveAt + coolingDays 天 &gt; 判定时刻（仅「生效中」可挂，状态机保证
 *       effectiveAt 非空，null 防御按期外计）；</li>
 *   <li>D3 口径：冷静期内违约金 0；期后 Math.round(基数 × penaltyRate / 10000.0)（HALF_UP，
 *       基点万分比 2000=20%）；基数 RF 链＝已付额 paidAmt、CC 链＝卡余额 balance；
 *       penaltyRate 上限 10000（ContractService 校验），全额扣留时实退为 0（RF 链退款额 &gt; 0 校验自然拦截）；</li>
 *   <li>D6 留证：判定要素＋窗口结论＋判定时刻落 JSON 快照，随单据 contract_snapshot 列持久化，
 *       供复核/争议时还原判定现场。</li>
 * </ul>
 * 语义红线：违约金＝从应退额「扣留」（资金本在商户户），非向客户额外收费；RF 链实退＝已付−违约金，
 * CC 链违约金即 fee（恒等式 balance = refundAmt + fee 天然满足）。
 */
@Component
public class ContractPenaltyJudge {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ContractRepository contractRepo;

    public ContractPenaltyJudge(ContractRepository contractRepo) {
        this.contractRepo = contractRepo;
    }

    /**
     * 判定违约金并出快照。contractNo 由调用方保证非空（空＝旧链不进入本判定）。
     *
     * @param customerId 本单客户（须与合同归属客户一致）
     * @param baseCents  违约金计算基数（分）：RF 链＝已付额、CC 链＝卡余额
     */
    public PenaltyVerdict judge(String contractNo, String customerId, long baseCents) {
        String no = contractNo.trim();
        Contract c = contractRepo.findById(no).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "关联合同不存在：" + no));
        if (!"生效中".equals(c.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "关联合同「" + no + "」当前状态为「" + c.getStatus() + "」，仅「生效中」合同可挂载退款/退卡");
        }
        if (customerId == null || customerId.isBlank() || !customerId.equals(c.getCustomerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "关联合同「" + no + "」归属客户与本单客户不一致，不可挂载");
        }
        long total = c.getTotalAmount() == null ? 0L : c.getTotalAmount();
        if (baseCents > total) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "违约金计算基数（¥" + fenToYuan(baseCents) + "）不得超过合同总额（¥" + fenToYuan(total) + "）");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int coolingDays = c.getCoolingDays() == null ? 7 : c.getCoolingDays();
        int penaltyRate = c.getPenaltyRate() == null ? 2000 : c.getPenaltyRate();
        boolean inCooling = c.getEffectiveAt() != null
                && c.getEffectiveAt().plusDays(coolingDays).isAfter(now);
        long penalty = inCooling ? 0L : Math.min(baseCents, Math.round(baseCents * penaltyRate / 10000.0));
        return new PenaltyVerdict(no, c.getTitle(), c.getContractType(), c.getEffectiveAt(),
                coolingDays, penaltyRate, inCooling, baseCents, penalty,
                snapshot(no, c, coolingDays, penaltyRate, inCooling, baseCents, penalty, now));
    }

    /** D6 判定快照 JSON（LinkedHashMap 保序，要素→结论→判定时刻）。 */
    private static String snapshot(String no, Contract c, int coolingDays, int penaltyRate,
                                   boolean inCooling, long baseCents, long penalty, OffsetDateTime now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("contractNo", no);
        m.put("title", c.getTitle());
        m.put("contractType", c.getContractType());
        m.put("effectiveAt", c.getEffectiveAt() == null ? null : c.getEffectiveAt().toString());
        m.put("coolingDays", coolingDays);
        m.put("penaltyRate", penaltyRate);
        m.put("inCooling", inCooling);
        m.put("baseCents", baseCents);
        m.put("penaltyAmt", penalty);
        m.put("judgedAt", now.toString());
        try {
            return MAPPER.writeValueAsString(m);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String fenToYuan(long fen) {
        return String.format("%.2f", fen / 100.0);
    }

    /** 判定结论：合同要素＋窗口结论＋口径金额＋快照 JSON（snapshot 即落库 contract_snapshot 列内容）。 */
    public record PenaltyVerdict(String contractNo, String title, String contractType,
                                 OffsetDateTime effectiveAt, int coolingDays, int penaltyRate,
                                 boolean inCooling, long baseCents, long penaltyAmt,
                                 String snapshot) {}
}
