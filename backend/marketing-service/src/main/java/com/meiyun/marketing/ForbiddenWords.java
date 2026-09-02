package com.meiyun.marketing;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 四类违禁词库（营销合规红线，对齐 A1-04 合规词库口径）。
 * 纯 Java 零依赖：发送前实时校验，命中即拦截。
 */
public final class ForbiddenWords {

    private ForbiddenWords() {
    }

    /** 四类违禁词（示例词库，生产由 A1-04 词库服务下发）。 */
    private static final Map<String, List<String>> CATEGORIES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("绝对化用语", List.of("最", "第一", "顶级", "极致", "国家级", "绝无仅有", "100%", "永久"));
        CATEGORIES.put("医疗承诺", List.of("根治", "治愈", "包治", "无效退款", "祖传秘方", "一次见效", "永不反弹"));
        CATEGORIES.put("虚假宣传", List.of("免费领", "零元购", "内部价", "稳赚", "假一赔万", "官方认证"));
        CATEGORIES.put("低俗诱导", List.of("私密", "约炮", "上门特殊服务"));
    }

    /** 校验文案，返回命中的违禁词（按类别），空列表表示通过。 */
    public static List<String> check(String content) {
        List<String> hits = new ArrayList<>();
        if (content == null || content.isBlank()) {
            return hits;
        }
        for (Map.Entry<String, List<String>> e : CATEGORIES.entrySet()) {
            for (String w : e.getValue()) {
                if (content.contains(w)) {
                    hits.add(e.getKey() + ":" + w);
                }
            }
        }
        return hits;
    }

    public static Map<String, List<String>> categories() {
        return CATEGORIES;
    }
}
