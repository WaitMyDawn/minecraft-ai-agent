package yagen.waitmydawn.maa.model;

/** 每轮读取一次的账号选择策略；缺省保持人工确认，不接受构建请求覆盖。 */
public record PopupPreferences(boolean addons, boolean replacements, boolean overlap, boolean generation) {
    public static PopupPreferences defaults() { return new PopupPreferences(true, true, true, true); }

    public boolean asks(ContentOverlap.Group group) {
        return group.original().startsWith("generation:") || group.original().startsWith("major-worldgen")
                ? generation : overlap;
    }
}
