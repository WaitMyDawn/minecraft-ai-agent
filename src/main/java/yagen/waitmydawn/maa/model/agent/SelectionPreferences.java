package yagen.waitmydawn.maa.model.agent;

/** 局部内容选择偏好；不改变点名项目、兼容环境或依赖约束。 */
public record SelectionPreferences(Popularity popularity, Freshness freshness) {
    public enum Popularity { DEFAULT, POPULAR, LESS_POPULAR }
    public enum Freshness { DEFAULT, RECENT }
    public static final SelectionPreferences DEFAULT = new SelectionPreferences(Popularity.DEFAULT, Freshness.DEFAULT);
    /** 历史子目标缺少偏好字段时仅恢复该片段的明确措辞，不继承相邻用途。 */
    public static SelectionPreferences fromSource(String source) {
        String text = source == null ? "" : source.toLowerCase(java.util.Locale.ROOT);
        // 显式否定只消去紧邻的偏好措辞，不跨越转折或其它用途。
        text = text.replaceAll("(?:不要|不需要|不想|避免|排除|不用|不希望)(?:使用|选择|添加|加入|采用|用|的|太|过于|很|特别|比较|非常)*(?:冷门|非热门|小众|较新|比较新|新一些|新一点|新发布|新出的|近期发布|新颖)", "")
                .replaceAll("(?:not|avoid|exclude|no)\\s+(?:recent(?:ly released)?|newer|less popular|niche)", "");
        boolean less = text.matches("(?s).*(冷门|非热门|不热门|不太热门|小众|less popular|non.?popular|niche).*" );
        boolean recent = text.matches("(?s).*(较新|比较新|新一些|新一点|新发布|新出的|近期发布|新颖|recent|newer).*" );
        return new SelectionPreferences(less ? Popularity.LESS_POPULAR : Popularity.DEFAULT,
                recent ? Freshness.RECENT : Freshness.DEFAULT);
    }
    public SelectionPreferences {
        ContractRules.required(popularity, "requirements.preferences.popularity");
        ContractRules.required(freshness, "requirements.preferences.freshness");
    }
}
