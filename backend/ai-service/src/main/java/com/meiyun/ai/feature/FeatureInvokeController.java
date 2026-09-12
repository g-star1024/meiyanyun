package com.meiyun.ai.feature;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 功能真实调用出口。刻意不加类级 aiAdmin:view：
 * 面向业务角色开放，能否调用由登录态 + ai_feature_role 灰度矩阵在服务内判定。
 */
@RestController
@RequestMapping("/api/ai/features")
public class FeatureInvokeController {

    private final FeatureInvokeService featureInvokeService;

    public FeatureInvokeController(FeatureInvokeService featureInvokeService) {
        this.featureInvokeService = featureInvokeService;
    }

    @PostMapping("/{featureCode}/invoke")
    public FeatureInvokeService.InvokeView invoke(@PathVariable String featureCode,
                                                  @RequestBody FeatureInvokeService.InvokeCmd cmd) {
        return featureInvokeService.invoke(featureCode, cmd);
    }
}
