package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.SelectionPreferences;
import java.time.*;
import java.util.*;

/** 有界软偏好：相关性、兼容性和真实附属关系仍决定候选资格。 */
public final class PreferenceRanking {
    private PreferenceRanking() {}
    public record Channel(String index, boolean longTail) {}
    public static List<Channel> channels(SelectionPreferences preferences) {
        var result = new ArrayList<Channel>();
        result.add(new Channel("relevance", false));
        if (preferences.freshness() == SelectionPreferences.Freshness.RECENT)
            result.add(new Channel("newest", preferences.popularity() == SelectionPreferences.Popularity.LESS_POPULAR));
        else if (preferences.popularity() == SelectionPreferences.Popularity.LESS_POPULAR)
            result.add(new Channel("relevance", true));
        else if (preferences.popularity() == SelectionPreferences.Popularity.POPULAR)
            result.add(new Channel("downloads", false));
        return List.copyOf(result);
    }
    /** 仅采样通道使用50万上限；常规通道保留更热门但相关的候选。 */
    public static String facets(String facets, Channel channel) {
        return channel.longTail() ? facets.substring(0, facets.length() - 1) + ",[\"downloads <= 500000\"]]" : facets;
    }
    public static Instant date(String value, Instant now) {
        try { var parsed = Instant.parse(value); return parsed.isAfter(now) ? null : parsed; }
        catch (RuntimeException invalid) { return null; }
    }
    public static double factor(SelectionPreferences preferences, long downloads, long poolMax, Instant published, Instant now) {
        if (preferences.equals(SelectionPreferences.DEFAULT)) return RetrievalScorer.popularityNorm(downloads, poolMax);
        double popular = Math.log10(Math.max(1, downloads)) / Math.log10(Math.max(10, poolMax));
        popular = Math.min(1, Math.max(0, popular));
        double popularity = switch (preferences.popularity()) {
            case DEFAULT -> 1;
            case POPULAR -> .65 + .35 * popular;
            case LESS_POPULAR -> 1 - .35 * popular;
        };
        double freshness = 1;
        if (preferences.freshness() == SelectionPreferences.Freshness.RECENT) {
            // 未知日期使用中性值；旧项目最近更新不能冒充新项目。
            freshness = published == null || published.isAfter(now) ? .85
                    : 1 - .30 * Math.min(1, Math.max(0, Duration.between(published, now).toDays()) / 730.0);
        }
        return popularity * freshness;
    }
}
