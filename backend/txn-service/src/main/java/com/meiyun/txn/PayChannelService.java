package com.meiyun.txn;

import com.meiyun.security.DataScope;
import com.meiyun.txn.audit.AuditRecorder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 支付渠道配置服务（pay_channel_config，B12 域一）。
 *
 * <p>渠道随支付方式走：wxpay 微信支付 / alipay 支付宝 / transfer 银行转账需后台配置对接参数；
 * cash 现金 / balance 储值余额为系统内置渠道，无需配置（列表返回只读内置行）。
 *
 * <p>密钥红线：{@code apiV3Key} 写后不可读回——列表/读模型只暴露 {@code hasApiKey} 布尔位，
 * 更新时空串/缺省 = 不修改已存密钥；审计 payload 只记 hasApiKey，绝不记明文。
 *
 * <p>诚实降级：收银台真实支付/退款、账单 API 自动拉取本期不做（reconcile_mode 仅 IMPORT），
 * 配置只落库与维护，不伪造任何渠道调用。
 */
@Service
public class PayChannelService {

    /** 本期支持配置的非现金渠道（cash/balance 为内置只读，card 暂不配置）。 */
    static final Set<String> CONFIGURABLE = Set.of("wxpay", "alipay", "transfer");

    static final Map<String, String> CHANNEL_NAMES = Map.of(
            "wxpay", "微信支付",
            "alipay", "支付宝",
            "transfer", "银行转账",
            "cash", "现金",
            "balance", "储值余额");

    private final PayChannelConfigRepository repo;
    private final AuditRecorder audit;

    public PayChannelService(PayChannelConfigRepository repo, AuditRecorder audit) {
        this.repo = repo;
        this.audit = audit;
    }

    /** 渠道配置读模型（密钥不回显，hasApiKey 标记是否已设置）。 */
    public record ChannelView(String configId, String channelCode, String channelName,
                              String storeCode, boolean builtin, boolean enabled,
                              String appId, String mchId, boolean hasApiKey, String certSerial,
                              String notifyUrl, String reconcileMode, int feeRate,
                              String remark) {}

    /** upsert 命令体（字段均可选；apiV3Key 空串 = 不修改）。 */
    public record UpsertCmd(String channelCode, String storeCode, Boolean enabled,
                            String appId, String mchId, String apiV3Key, String certSerial,
                            String notifyUrl, Integer feeRate, String remark) {}

    /**
     * 渠道列表：系统内置行（cash/balance，只读）+ 全部已配置渠道行。
     * 未配置的可配置渠道不返回（前端按内置渠道卡渲染，配置缺失显示「未配置」）。
     */
    @Transactional(readOnly = true)
    public List<ChannelView> list() {
        List<ChannelView> rows = new ArrayList<>();
        rows.add(new ChannelView(null, "cash", CHANNEL_NAMES.get("cash"), "", true, true,
                null, null, false, null, null, "BUILTIN", 0, "系统内置渠道，无需配置"));
        rows.add(new ChannelView(null, "balance", CHANNEL_NAMES.get("balance"), "", true, true,
                null, null, false, null, null, "BUILTIN", 0, "系统内置渠道，无需配置"));
        repo.findAllByOrderByChannelCodeAscStoreCodeAsc().stream()
                .map(this::toView).forEach(rows::add);
        return rows;
    }

    /** 新增/更新渠道配置（upsert by channel+store；密钥空串=不修改）。 */
    @Transactional
    public ChannelView upsert(UpsertCmd cmd) {
        String actor = DataScope.currentActor();
        String channel = cmd.channelCode() == null ? "" : cmd.channelCode().trim();
        if (!CONFIGURABLE.contains(channel)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "渠道码无效: " + channel + "（本期支持配置 wxpay/alipay/transfer；现金/储值余额为系统内置）");
        }
        String store = cmd.storeCode() == null ? "" : cmd.storeCode().trim();
        int feeRate = cmd.feeRate() == null ? 0 : cmd.feeRate();
        if (feeRate < 0 || feeRate > 100000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "手续费率无效: " + feeRate + "（万分位整数，0~100000，如 60 = 0.6%）");
        }

        Optional<PayChannelConfig> exist = repo.findByChannelCodeAndStoreCode(channel, store);
        PayChannelConfig c = exist.orElseGet(PayChannelConfig::new);
        boolean created = exist.isEmpty();
        if (created) {
            c.setConfigId(nextConfigNo());
            c.setChannelCode(channel);
            c.setChannelName(CHANNEL_NAMES.get(channel));
            c.setStoreCode(store);
            c.setReconcileMode("IMPORT");
            c.setEnabled(cmd.enabled() == null || cmd.enabled());
        } else if (cmd.enabled() != null) {
            c.setEnabled(cmd.enabled());
        }
        c.setAppId(trim(cmd.appId()));
        c.setMchId(trim(cmd.mchId()));
        String key = cmd.apiV3Key() == null ? "" : cmd.apiV3Key().trim();
        if (!key.isEmpty()) {
            if (key.length() > 128) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "APIv3 密钥长度不得超过 128 位");
            }
            c.setApiV3Key(key);
        } else if (created) {
            // 新建时密钥可暂缺（占位/测试参数），不强制；有则必须非空
            c.setApiV3Key(null);
        }
        c.setCertSerial(trim(cmd.certSerial()));
        c.setNotifyUrl(trim(cmd.notifyUrl()));
        c.setFeeRate(feeRate);
        c.setRemark(trim(cmd.remark()));
        c.setUpdatedBy(actor);
        if (created) c.setCreatedBy(actor);
        repo.save(c);

        // 审计脱敏：只记渠道/门店/是否已设密钥，绝不记密钥明文
        audit.record("PAY_CHANNEL", c.getConfigId(), actor, created ? "CREATE" : "UPDATE",
                "{\"channel\":\"" + channel + "\",\"store\":\"" + store
                        + "\",\"enabled\":" + c.isEnabled()
                        + ",\"hasApiKey\":" + (c.getApiV3Key() != null && !c.getApiV3Key().isBlank())
                        + ",\"feeRate\":" + feeRate + "}");
        return toView(c);
    }

    /** 启用/停用切换（随时调整；停用不抹配置、不影响历史账）。 */
    @Transactional
    public ChannelView toggle(String configId) {
        String actor = DataScope.currentActor();
        PayChannelConfig c = repo.findById(configId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "渠道配置不存在: " + configId));
        c.setEnabled(!c.isEnabled());
        c.setUpdatedBy(actor);
        repo.save(c);
        audit.record("PAY_CHANNEL", configId, actor, "TOGGLE",
                "{\"channel\":\"" + c.getChannelCode() + "\",\"store\":\"" + c.getStoreCode()
                        + "\",\"enabled\":" + c.isEnabled() + "}");
        return toView(c);
    }

    private ChannelView toView(PayChannelConfig c) {
        return new ChannelView(c.getConfigId(), c.getChannelCode(), c.getChannelName(),
                c.getStoreCode() == null ? "" : c.getStoreCode(), false, c.isEnabled(),
                c.getAppId(), c.getMchId(),
                c.getApiV3Key() != null && !c.getApiV3Key().isBlank(),
                c.getCertSerial(), c.getNotifyUrl(), c.getReconcileMode(), c.getFeeRate(),
                c.getRemark());
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** PCC + yyyyMMdd + - + 6 位序号（与 PM 单号同一范式，UTC 日界）。 */
    private synchronized String nextConfigNo() {
        String day = LocalDate.now().toString().replace("-", "");
        long seq = repo.maxSeqOfDay("PCC" + day + "-%") + 1;
        return "PCC" + day + "-" + String.format("%06d", seq);
    }
}
