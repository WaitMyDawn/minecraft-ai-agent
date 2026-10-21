package yagen.waitmydawn.maa.model.agent;

import java.util.regex.Pattern;

/** 局部数量只统计有依据的内容根，不含附属核心本体或自动补入的前置。 */
public record RequirementQuantity(int min, int max, Scope scope) {
    public enum Scope { RETAINED_ROOTS, ADDED_ROOTS }
    private static final Pattern RANGE = Pattern.compile("(\\d{1,3})\\s*(?:[~～—–-]|至|到)\\s*(\\d{1,3})\\s*(?:个|款)(?=\\s*$|[、，,。；;）)]|模组|附属)");
    public RequirementQuantity {
        ContractRules.require(min >= 0 && min <= max && max <= ContractRules.MAX_MODS, "quantity.range");
        ContractRules.required(scope, "quantity.scope");
    }
    /** 旧计划只恢复明确单一范围，复合片段的范围由子目标分别承载。 */
    public static RequirementQuantity fromSource(String source) {
        if (source == null || source.matches("(?is).*(整合包|总量|总共|总计|modpack|in total).*")) return null;
        var matcher = RANGE.matcher(source);
        if (!matcher.find()) return null;
        String context = source.substring(0, matcher.start()) + source.substring(matcher.end());
        if (context.replaceAll("模组|数量|范围|文件|大概|大约|左右|约|共|个|款|\\s+", "").isBlank()) return null;
        int min = Integer.parseInt(matcher.group(1)), max = Integer.parseInt(matcher.group(2));
        if (matcher.find()) return null;
        return new RequirementQuantity(min, max, source.matches("(?is).*(补充|新增|再加|添加|加入|新加|扩充|增加|补上|\\badd\\b).*")
                ? Scope.ADDED_ROOTS : Scope.RETAINED_ROOTS);
    }
}
