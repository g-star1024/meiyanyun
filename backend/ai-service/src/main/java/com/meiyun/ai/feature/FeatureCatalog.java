package com.meiyun.ai.feature;

import java.util.List;
import java.util.Map;

/** AI 功能目录基线：featureCode 与 A1 页面/前端矩阵一一对应，启动时幂等播种。 */
public final class FeatureCatalog {

    public record Feature(String code, String name) {
    }

    /** A1Admin 权限矩阵功能（与前端 features 数组同序，启动时幂等播种）。 */
    public static final List<Feature> FEATURES = List.of(
            new Feature("profile", "客户画像"),
            new Feature("repurchase", "复购预测"),
            new Feature("churn", "流失预警"),
            new Feature("daily", "经营日报"),
            new Feature("scripts", "智能话术"),
            new Feature("scheduling", "智能排班"),
            new Feature("content", "内容生成"),
            new Feature("chatbot", "AI 客服"),
            new Feature("govern", "审批评估"));

    /** 功能×角色灰度矩阵的五角色（与 A1Admin 角色头一致）。 */
    public static final List<String> MATRIX_ROLES = List.of(
            "SUPER_ADMIN", "REGION_MGR", "STORE_MGR", "CONSULTANT", "OPERATOR");

    public static final Map<String, String> ROLE_NAMES = Map.of(
            "SUPER_ADMIN", "超级管理员",
            "REGION_MGR", "区域经理",
            "STORE_MGR", "店长",
            "CONSULTANT", "咨询师",
            "OPERATOR", "市场专员");

    private FeatureCatalog() {
    }

    public static String nameOf(String code) {
        return FEATURES.stream().filter(f -> f.code().equals(code)).findFirst()
                .map(Feature::name).orElse(code);
    }
}
