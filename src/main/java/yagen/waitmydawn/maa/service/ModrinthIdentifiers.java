package yagen.waitmydawn.maa.service;

import java.util.regex.Pattern;

/** Modrinth 项目标识兼容 base62 ID 和官方 slug 字符集，不接受路径或查询分隔符。 */
public final class ModrinthIdentifiers {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9]{1,128}");
    private static final Pattern SLUG = Pattern.compile("[\\w!@$()`.+,\"\\-']{3,64}");
    private ModrinthIdentifiers() {}
    public static boolean isProjectReference(String value) {
        return value != null && (ID.matcher(value).matches() || SLUG.matcher(value).matches());
    }
}
