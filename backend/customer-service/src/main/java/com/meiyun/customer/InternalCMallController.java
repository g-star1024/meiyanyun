package com.meiyun.customer;

import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * C 端积分商城内部端点（服务间调用，不经网关；网关对 /internal/** 一律 404）。
 *
 * <p>调用方：c-service 会员小程序兑换。鉴权走 X-Internal-Token（拦截器注入 perms=["*"]），
 * 普通登录人即使拿到路径也因缺 internal:c-mall 被 403 挡下。
 * 兑换复用 {@link MallController#placeExchange} 全量校验链（clientToken 幂等/上架/库存/
 * 实物收货信息/积分按定价×数量后端计算），C 端来源由 clientToken 的 "C:" 前缀在 client_token 列留痕。
 */
@RestController
@RequestMapping("/api/customer/internal/c-mall")
public class InternalCMallController {

    private final MallController mallController;

    public InternalCMallController(MallController mallController) {
        this.mallController = mallController;
    }

    /** C 端兑换申请：与 B 端共用 placeExchange（幂等/校验/审计全复用，不复制逻辑）。 */
    @PostMapping("/exchange")
    @RequirePerm("internal:c-mall")
    public MallExchange exchange(@RequestBody MallController.PlaceExchangeCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (cmd.productId() == null || cmd.productId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品ID不可为空");
        }
        if (cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        return mallController.placeExchange(cmd);
    }
}
