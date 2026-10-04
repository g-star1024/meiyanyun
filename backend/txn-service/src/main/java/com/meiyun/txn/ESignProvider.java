package com.meiyun.txn;

/**
 * 棒⑧卡3 电子签接入位·厂商无关适配层：
 * 合同电子签署流程的厂商抽象（开关探测 + 发起签署），具体厂商（e签宝/法大大等）以实现类插拔替换。
 * 诚实降级契约（铁律 11）：未启用/厂商参数缺失/密钥缺失一律 SKIPPED 如实中文原因，绝不伪造已发送。
 * 默认实现 {@link VendorNeutralESignProvider} 走配置目录 ESIGN_DIRECT（SWITCH+configJson）
 * + ESIGN_SECRET（AES-GCM 密文）通用 REST 契约，配置即用。
 */
public interface ESignProvider {

    /** 电子签接入位是否启用（配置目录 ESIGN_DIRECT 开关，库启用值优先于 env 兜底，fail-closed）。 */
    boolean enabled();

    /**
     * 发起签署流程。
     *
     * @return 成功 SENT 持厂商签署流程号；未启用/缺参/缺密钥 SKIPPED 持中文降级原因
     */
    ESignSendResult send(Contract contract);

    /** 发送结果：sent=true 持厂商流程号；sent=false 为 SKIPPED 诚实降级（detail 中文原因）。 */
    record ESignSendResult(boolean sent, String flowId, String detail) {

        public static ESignSendResult sent(String flowId) {
            return new ESignSendResult(true, flowId, "已发送至电子签厂商，等待客户签署");
        }

        public static ESignSendResult skipped(String detail) {
            return new ESignSendResult(false, null, detail);
        }
    }
}
