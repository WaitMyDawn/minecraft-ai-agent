package yagen.waitmydawn.maa.service;

import java.util.regex.Pattern;

/** 本轮明确推荐禁令约束自动召回，不阻止必需前置或用户要求的核心附属。 */
public final class RecommendationPolicy {
    private RecommendationPolicy() {}
    private static final Pattern DIRECTIVE = Pattern.compile(
            "不额外推荐|不要额外推荐|不要推荐(?:其它|其他)模组|(?:不需要|不要)(?:其它|其他|额外)(?:的)?推荐|"
            + "(?:只|仅)(?:添加|加入|包含)\\s*[a-zA-Z][a-zA-Z0-9_.-]{1,127}(?:\\s*(?:、|,|，|和|及|与)\\s*[a-zA-Z][a-zA-Z0-9_.-]{1,127})*(?:\\s*(?:和|及|与)\\s*(?:必要|所需)?前置)?(?=\\s*(?:[，,。；;\\n]|$))|"
            + "只(?:添加|使用)(?:点名|指定)(?:的)?(?:项目|模组)|"
            + "仅(?:添加|使用)(?:点名|指定)(?:的)?(?:项目|模组)|no extra recommendations|"
            + "允许额外推荐|可以额外推荐|allow extra recommendations", Pattern.CASE_INSENSITIVE);
    public static boolean namedOnly(String prompt) {
        var matcher = DIRECTIVE.matcher(prompt == null ? "" : prompt);
        boolean result = false;
        while (matcher.find()) result = !matcher.group().matches("(?i)允许.*|可以.*|allow.*");
        return result;
    }
}
