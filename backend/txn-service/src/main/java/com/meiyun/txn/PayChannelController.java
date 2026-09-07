package com.meiyun.txn;

import com.meiyun.security.RequirePerm;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 支付渠道配置端点（B12 域一，挂 /api/txn）。
 *
 * <p>权限：查询 integration:view（与「小程序/支付集成设置」菜单一致）；
 * 新增/更新/启停 finance:channel:edit（渠道对接参数属财务集成配置）。
 * 密钥类字段写后不读回，详见 {@link PayChannelService}。
 */
@RestController
@RequestMapping("/api/txn")
public class PayChannelController {

    private final PayChannelService service;

    public PayChannelController(PayChannelService service) {
        this.service = service;
    }

    /** 渠道配置列表（含系统内置 cash/balance 只读行）。 */
    @GetMapping("/pay-channels")
    @RequirePerm("integration:view")
    public List<PayChannelService.ChannelView> list() {
        return service.list();
    }

    /** 新增/更新配置（upsert by channel+store；apiV3Key 空串 = 不修改已存密钥）。 */
    @PostMapping("/pay-channels")
    @RequirePerm("finance:channel:edit")
    public PayChannelService.ChannelView upsert(@RequestBody PayChannelService.UpsertCmd cmd) {
        return service.upsert(cmd);
    }

    /** 启用/停用切换。 */
    @PostMapping("/pay-channels/{id}/toggle")
    @RequirePerm("finance:channel:edit")
    public PayChannelService.ChannelView toggle(@PathVariable("id") String configId) {
        return service.toggle(configId);
    }
}
