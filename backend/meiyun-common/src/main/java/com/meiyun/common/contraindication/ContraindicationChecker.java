package com.meiyun.common.contraindication;

import java.util.ArrayList;
import java.util.List;

/**
 * 过敏禁忌硬阻断引擎（红线 B0-2 / B0-3，M4-06 采集 → M4-10 校验）。
 *
 * <p>八帧链路对应的三态判定：
 * <ul>
 *   <li><b>RED</b>：绝对禁忌命中（疤痕体质 / 孕期 / 凝血异常 / 项目含药物过敏原）→ 硬阻断，仅双签豁免可放行；</li>
 *   <li><b>YELLOW</b>：存在过敏史但项目未直接命中 → 需知情确认，可附豁免说明后放行；</li>
 *   <li><b>GREEN</b>：无风险项。</li>
 * </ul>
 *
 * <p>纯 Java 零依赖，可被各微服务与单测复用。
 */
public final class ContraindicationChecker {

    private ContraindicationChecker() {
    }

    public enum Level { GREEN, YELLOW, RED }

    /** 客情/禁忌档案。字段值「是」/「否」，过敏史分号分隔。 */
    public record Profile(
            String allergyHistory,      // 过敏史（如 海鲜过敏;花粉过敏）
            String drugAllergy,         // 药物过敏（如 青霉素）
            String scarConstitution,    // 疤痕体质 是/否
            String pregnancy,           // 孕期 是/否
            String coagulationAbn) {    // 凝血异常 是/否
    }

    public record Result(Level level, List<String> hits) {
        public boolean red() { return level == Level.RED; }
    }

    /**
     * 校验客情档案与项目：返回三态与命中项。
     *
     * @param profile 客情档案（可为 null → 视为无档案，GREEN）
     * @param project 开单项目名称（用于药物过敏原包含匹配）
     */
    public static Result check(Profile profile, String project) {
        if (profile == null) {
            return new Result(Level.GREEN, List.of());
        }
        List<String> redHits = new ArrayList<>();
        List<String> yellowHits = new ArrayList<>();

        // 1. 绝对禁忌（体质类）
        if (isYes(profile.scarConstitution())) redHits.add("疤痕体质（绝对禁忌）");
        if (isYes(profile.pregnancy())) redHits.add("孕期（绝对禁忌）");
        if (isYes(profile.coagulationAbn())) redHits.add("凝血功能异常（绝对禁忌）");

        // 2. 药物过敏原 × 项目包含匹配
        String proj = project == null ? "" : project;
        for (String drug : split(profile.drugAllergy())) {
            if (!drug.isEmpty() && proj.contains(drug)) {
                redHits.add("药物过敏原命中：项目含「" + drug + "」");
            } else if (!drug.isEmpty()) {
                yellowHits.add("药物过敏史：" + drug);
            }
        }

        // 3. 其他过敏史 → YELLOW 提示
        for (String a : split(profile.allergyHistory())) {
            if (!a.isEmpty()) {
                yellowHits.add("过敏史：" + a);
            }
        }

        if (!redHits.isEmpty()) return new Result(Level.RED, redHits);
        if (!yellowHits.isEmpty()) return new Result(Level.YELLOW, yellowHits);
        return new Result(Level.GREEN, List.of());
    }

    /**
     * 校验豁免放行条件（RED → YELLOW 的唯一合法路径）：
     * 两名豁免签不同人，且第二签须持医疗执业资质（主理医生评估）。
     */
    public static List<String> validateExemption(String sign1Id, String sign2Id, boolean sign2MedicalLicensed, String note) {
        List<String> errors = new ArrayList<>();
        if (sign1Id == null || sign1Id.isBlank() || sign2Id == null || sign2Id.isBlank()) {
            errors.add("豁免须双签：缺少签核人");
        } else if (sign1Id.equals(sign2Id)) {
            errors.add("豁免双签不得为同一人");
        }
        if (!sign2MedicalLicensed) {
            errors.add("豁免第二签须持医疗执业资质（主理医生评估）");
        }
        if (note == null || note.isBlank()) {
            errors.add("豁免须填写评估说明（留痕）");
        }
        return errors;
    }

    private static boolean isYes(String v) {
        return "是".equals(v);
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        if (s == null || s.isBlank()) return out;
        for (String part : s.split("[;；,，]")) {
            String t = part.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
