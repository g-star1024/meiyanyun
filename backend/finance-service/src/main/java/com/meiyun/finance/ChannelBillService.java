package com.meiyun.finance;

import com.meiyun.security.DataScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渠道账单导入服务（B12 域一，DESIGN §3.3）：微信/支付宝/银行结算单 CSV 整批导入
 * pay_channel_bill，作为非现金渠道账实核对的「账单侧」原料。
 *
 * <p><b>整批校验</b>：任一行格式/金额非法 → 整批拒绝（不落任何行），错误信息带 CSV 行号与中文原因；
 * <b>批次幂等</b>：相同 import_batch 重放整批跳过返回既有行数；UNIQUE(渠道,订单号,结算批次) 兜底。
 *
 * <p>CSV 列（首行表头，逗号分隔，金额「元」两位小数 → 内部转 Long「分」）：
 * <pre>渠道,门店,订单号,交易金额,手续费,状态,交易时间,结算批次
 * wxpay,SST01,SO20260901001,1980.00,11.88,SUCCESS,2026-09-01 10:23:45,WX20260902</pre>
 *
 * <p><b>资金红线</b>：账单只写勾兑台账，绝不据账单伪造实付渠道分录。
 */
@Service
public class ChannelBillService {

    private static final Logger log = LoggerFactory.getLogger(ChannelBillService.class);

    static final Set<String> CHANNELS = Set.of("wxpay", "alipay", "transfer");
    static final Set<String> STATUSES = Set.of("SUCCESS", "REFUND", "FAILED");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final PayChannelBillRepository repo;
    private final FinanceAuditRecorder audit;

    public ChannelBillService(PayChannelBillRepository repo, FinanceAuditRecorder audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** 导入结果读模型。 */
    public record ImportResult(String importBatch, int totalRows, int importedRows,
                               int skippedRows, boolean idempotentReplay, String message) {}

    /**
     * 整批导入 CSV。
     *
     * @param channel     渠道码 wxpay/alipay/transfer（整单必须同渠道，与文件名/导出方一致）
     * @param storeCode   可选门店；CSV 列门店为空时回落此值
     * @param importBatch 导入批次号；空则按 IMP+yyyyMMdd-6 位生成
     * @param csvContent  CSV 全文（含表头）
     * @param actor       操作人工号（控制器取 SecurityContext.currentStaffId()）
     */
    @Transactional
    public ImportResult importCsv(String channel, String storeCode, String importBatch,
                                  String csvContent, String actor) {
        if (channel == null || !CHANNELS.contains(channel.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "渠道码无效: " + channel + "（支持 wxpay/alipay/transfer）");
        }
        channel = channel.trim();
        String fallbackStore = storeCode == null ? "" : storeCode.trim();
        if (csvContent == null || csvContent.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV 内容为空，请先从微信商户平台/支付宝商家中心导出结算单");
        }

        String[] rawLines = csvContent.replace("\r\n", "\n").replace("\r", "\n").split("\n");
        // 跳过表头与空行，收集数据行（保留物理行号用于中文错误定位）
        List<int[]> dataLineIdx = new ArrayList<>();
        for (int i = 0; i < rawLines.length; i++) {
            String line = rawLines[i].trim();
            if (line.isEmpty()) continue;
            if (i == 0 && line.contains("订单号")) continue; // 表头
            dataLineIdx.add(new int[]{i});
        }
        if (dataLineIdx.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "CSV 无有效数据行（需表头 + 至少一行账单）");
        }

        String batch = (importBatch == null || importBatch.isBlank()) ? nextBatchNo() : importBatch.trim();
        long existed = repo.countByImportBatch(batch);
        if (existed > 0) {
            String msg = "导入批次 " + batch + " 已存在（" + existed + " 行），整批幂等跳过，未重复入账";
            log.info("[ChannelBill] 批次幂等跳过 {} channel={} 既有 {} 行", batch, channel, existed);
            return new ImportResult(batch, (int) existed, 0, (int) existed, true, msg);
        }

        List<PayChannelBill> bills = new ArrayList<>();
        OffsetDateTime now = OffsetDateTime.now();
        // 单号序号循环外只取一次：同事务内未 flush 的行对 maxSeqOfDay 查询不可见，
        // 若每行查库会得到同号 billId（分配式 ID → saveAll 走 merge → 同批多行互相覆盖只落 1 行）
        String billDayPrefix = LocalDate.now(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long billSeq = repo.maxSeqOfDay("PCB" + billDayPrefix + "-%");
        int rowNo = 0;
        for (int[] idx : dataLineIdx) {
            rowNo++;
            int physical = idx[0] + 1;
            String[] cols = rawLines[idx[0]].split(",", -1);
            if (cols.length < 8) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行列数不足（需 8 列：渠道,门店,订单号,交易金额,手续费,状态,交易时间,结算批次），实际 " + cols.length + " 列");
            }
            String rowChannel = cols[0].trim();
            String rowStore = cols[1].trim();
            String orderNo = cols[2].trim();
            String amountStr = cols[3].trim();
            String feeStr = cols[4].trim();
            String status = cols[5].trim().toUpperCase();
            String timeStr = cols[6].trim();
            String settle = cols[7].trim();

            if (!rowChannel.isEmpty() && !channel.equals(rowChannel)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行渠道为「" + rowChannel + "」，与本次导入渠道「" + channel + "」不一致，请按渠道分别导入");
            }
            String sc = rowStore.isEmpty() ? fallbackStore : rowStore;
            if (sc.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行门店为空且未指定导入门店，无法归属账实核对范围");
            }
            if (!DataScope.canReadStore(sc)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "CSV 第 " + physical + " 行门店 " + sc + " 无权导入或不存在");
            }
            long txn = parseYuan(amountStr, physical, "交易金额");
            long fee = feeStr.isEmpty() ? 0L : parseYuan(feeStr, physical, "手续费");
            if (txn == 0L) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行交易金额为 0，账单行金额必须为正数（元，两位小数）");
            }
            if (!STATUSES.contains(status)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行状态「" + cols[5].trim() + "」无效（支持 SUCCESS/REFUND/FAILED）");
            }
            OffsetDateTime billTime;
            try {
                billTime = LocalDateTime.parse(timeStr, TS).atOffset(ZoneOffset.ofHours(8));
            } catch (Exception e) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行交易时间「" + timeStr + "」格式非法，需 yyyy-MM-dd HH:mm:ss");
            }
            if (settle.isEmpty()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行结算批次为空（渠道结算单号/日期批次，幂等去重必需）");
            }
            // 退款行以 REFUND 状态 + 正数金额列示；net = txn − fee（退款手续费一般为 0），勾兑侧按状态取方向
            long net = txn - fee;

            PayChannelBill b = new PayChannelBill();
            b.setBillId("PCB" + billDayPrefix + "-" + String.format("%06d", ++billSeq));
            b.setChannelCode(channel);
            b.setStoreCode(sc);
            b.setOrderNo(orderNo.isEmpty() ? null : orderNo);
            b.setTxnAmount(txn);
            b.setFeeAmount(fee);
            b.setNetAmount(net);
            b.setBillStatus(status);
            b.setBillTime(billTime);
            b.setSettleBatch(settle);
            b.setImportBatch(batch);
            b.setCreatedAt(now);
            bills.add(b);
        }

        repo.saveAll(bills);
        long successCount = bills.stream().filter(b -> "SUCCESS".equals(b.getBillStatus())).count();
        long refundCount = bills.stream().filter(b -> "REFUND".equals(b.getBillStatus())).count();
        long failedCount = bills.stream().filter(b -> "FAILED".equals(b.getBillStatus())).count();
        String message = "批次 " + batch + " 导入完成：共 " + bills.size() + " 行（成功 " + successCount
                + " / 退款 " + refundCount + " / 失败 " + failedCount + "），可在「非现金渠道核对」按月份+渠道勾兑";
        audit.record("CHANNEL_BILL", batch, actor, "IMPORT",
                "{\"channel\":\"" + channel + "\",\"rows\":" + bills.size()
                        + ",\"success\":" + successCount + ",\"refund\":" + refundCount
                        + ",\"failed\":" + failedCount + "}");
        log.info("[ChannelBill] 批次 {} 导入 {} 行 channel={} actor={}", batch, bills.size(), channel, actor);
        return new ImportResult(batch, bills.size(), bills.size(), 0, false, message);
    }

    /** 账单查询（渠道/门店/时间区间），按 DataScope 逐行收敛。 */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String channel, String storeCode,
                                          OffsetDateTime from, OffsetDateTime to) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (PayChannelBill b : repo.findByBillTimeBetweenOrderByBillTimeAsc(from, to)) {
            if (channel != null && !channel.isBlank() && !channel.trim().equals(b.getChannelCode())) continue;
            String sc = b.getStoreCode();
            if (!DataScope.canReadStore(sc)) continue;
            if (storeCode != null && !storeCode.isBlank() && !storeCode.trim().equals(sc)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("billId", b.getBillId());
            row.put("channelCode", b.getChannelCode());
            row.put("storeCode", sc);
            row.put("orderNo", b.getOrderNo());
            row.put("txnAmountFen", b.getTxnAmount());
            row.put("txnAmountYuan", yuan(b.getTxnAmount()));
            row.put("feeAmountFen", b.getFeeAmount());
            row.put("feeAmountYuan", yuan(b.getFeeAmount()));
            row.put("netAmountFen", b.getNetAmount());
            row.put("netAmountYuan", yuan(b.getNetAmount()));
            row.put("billStatus", b.getBillStatus());
            row.put("billTime", b.getBillTime());
            row.put("settleBatch", b.getSettleBatch());
            row.put("importBatch", b.getImportBatch());
            rows.add(row);
        }
        return rows;
    }

    /** 元字符串 → 分（两位小数四舍五入；非法中文报错带行号）。 */
    private static long parseYuan(String s, int physical, String field) {
        try {
            double yuan = Double.parseDouble(s);
            if (yuan < 0) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "CSV 第 " + physical + " 行" + field + "为负数「" + s + "」；退款请用 REFUND 状态行并列示正数金额");
            }
            return Math.round(yuan * 100);
        } catch (NumberFormatException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "CSV 第 " + physical + " 行" + field + "「" + s + "」不是合法金额（元，两位小数，如 1980.00）");
        }
    }

    private static double yuan(long fen) {
        return Math.round(fen / 100.0 * 100.0) / 100.0;
    }

    /** IMP + yyyyMMdd(北京) + - + 6 位序号（导入批次号，独立序列查 import_batch 列）。 */
    private synchronized String nextBatchNo() {
        String day = LocalDate.now(ZoneOffset.ofHours(8))
                .format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long seq = repo.maxBatchSeqOfDay("IMP" + day + "-%") + 1;
        return "IMP" + day + "-" + String.format("%06d", seq);
    }
}
