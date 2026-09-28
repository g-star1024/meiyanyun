package com.meiyun.marketing;

import com.meiyun.security.RequirePerm;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C 端领券内部端点（服务间调用，不经网关；网关对 /internal/** 一律 404）。
 *
 * <p>调用方：c-service 会员小程序领券。鉴权走 X-Internal-Token（拦截器注入 perms=["*"]），
 * 普通登录人即使拿到路径也因缺 internal:c-coupon 被 403 挡下。
 */
@RestController
@RequestMapping("/api/marketing/internal/c-coupons")
public class InternalCCouponController {

    private final CouponService couponService;

    public InternalCCouponController(CouponService couponService) {
        this.couponService = couponService;
    }

    /** C 端领券：与 B 端发放共用 CouponService synchronized 锁防超发；同券同人重放直返既有持有。 */
    @PostMapping("/claim")
    @RequirePerm("internal:c-coupon")
    public Map<String, Object> claim(@RequestBody ClaimCmd cmd) {
        if (cmd == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请求体不能为空");
        }
        if (cmd.couponId() == null || cmd.couponId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "券ID不可为空");
        }
        if (cmd.customerId() == null || cmd.customerId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户ID不可为空");
        }
        CouponHold h = couponService.claimForCustomer(
                cmd.couponId().trim(), cmd.customerId().trim(), cmd.openid());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holdId", h.getId());
        body.put("couponId", h.getCouponId());
        body.put("customerId", h.getCustomerId());
        body.put("status", h.getStatus());
        body.put("createdAt", h.getCreatedAt() == null ? null : h.getCreatedAt().toString());
        return body;
    }

    /**
     * C 端领券入参。
     *
     * @param couponId   券模板ID（必填）
     * @param customerId 客户ID（必填，行级归属；c-service guard 已校验账号绑定会员档案）
     * @param openid     微信小程序 openid（内部调用下审计 actor 为 system，真实领取人靠本字段留痕）
     */
    public record ClaimCmd(String couponId, String customerId, String openid) {}
}
