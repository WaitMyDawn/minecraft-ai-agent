package yagen.waitmydawn.maa.service;

import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.regex.Pattern;

/** 新建缺省参数需确认；当前前端环境只提供建议，不证明用户已确认默认值。 */
public final class BasicBuildInputs {
    private BasicBuildInputs() {}
    private static final Pattern COUNT = Pattern.compile("(?:[0-9一二三四五六七八九十百两]+\\s*(?:个|款)?\\s*(?:模组|mods?\\b|文件)|(?:模组|mods?\\b|文件)(?:数量|数)?\\s*[:：=为]?\\s*[0-9一二三四五六七八九十百两]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOADER = Pattern.compile("neoforge|(?<!neo)forge|fabric|新锻造|织物", Pattern.CASE_INSENSITIVE);
    private static final Pattern VERSION = Pattern.compile("(?<![\\d.])\\d{1,2}\\.\\d{1,2}(?:\\.\\d{1,2})?(?![\\d.])");

    public static String confirmation(String prompt, BuildIntent intent) {
        if (intent.mode() != BuildIntent.Mode.NEW) return "";
        boolean environmentMissing = !LOADER.matcher(prompt).find() || !VERSION.matcher(prompt).find();
        boolean countMissing = !COUNT.matcher(prompt).find();
        if (!environmentMissing && !countMissing) return "";
        var e = intent.environment();
        var count = intent.count();
        String quantity = countMissing ? "80～120 个模组文件（含自动补充的前置）"
                : count.min() == count.max() ? count.value() + " 个" : count.min() + "～" + count.max() + " 个";
        if (!countMissing) quantity += switch (count.scope()) {
            case TOTAL_FILES -> "模组文件（含前置）";
            case ROOT_MODS -> "内容模组（不含自动前置）";
            case ADDED_ROOTS -> "本轮新增内容模组";
        };
        return "构建前请确认基本参数：" + e.loader().name().toLowerCase(java.util.Locale.ROOT) + " " + e.mcVersion()
                + "，" + quantity + "。回复“好的”即可按原始主题继续，也可以修改环境或数量。详细玩法和点名核心可选。"
                + "若要放弃本次需求，请回复“取消构建”；另起需求请以“重新开始”开头。";
    }
}
