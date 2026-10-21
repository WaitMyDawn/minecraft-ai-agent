package yagen.waitmydawn.maa.model;

import java.util.*;
import java.util.regex.Pattern;

/** 项目自身的生成行为与用户授权分别核对；不以项目名或 worldgen 分类推断行为。 */
public final class WorldGenerationPolicy {
    private WorldGenerationPolicy() {}
    public enum Effect { VOID_WORLD, FLAT_WORLD, CONTENT_REMOVAL }
    public record Finding(Effect effect, String quote) {}
    private static Pattern pattern(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static final Pattern VOID = pattern("\\bsky[ -]*block\\b(?!\\s+(?:biomes?|structures?))|空岛(?!群系|结构|建筑|生物|遗迹)|(?:infinite\\s+)?void\\s+(?:world|generation|terrain)|worlds?\\s+with\\s+missing\\s+terrain|remov\\w*\\s+(?:(?:all|the entire|world)\\s+)?terrain(?=\\s*(?:[.!?,;]|$|from\\b))|(?:without|missing)\\s+(?:any\\s+)?terrain\\b|虚空(?:世界|生成)|(?:移除|删除|没有|清空).{0,8}(?:全部)?地形(?!.{0,4}(?:错误|漏洞|瑕疵))");
    private static final Pattern FLAT = pattern("\\bsuperflat\\b|super[- ]flat\\s+(?:world|dimension|generation)|超平坦");
    private static final Pattern FLAT_BEHAVIOR = pattern("creat\\w*|generat\\w*|replac\\w*|transform\\w*|becomes?|turn\\w*|converts?|world types?\\s+(?:is|are)|super[- ]?flat\\s+(?:worlds?|dimensions?|terrain|generator)\\b|生成|创建|变成|替换|转换|改造|超平坦(?:世界|维度|地形)");
    private static final Pattern REMOVAL = pattern("(?:remov\\w*|disabl\\w*|strip\\w*)\\s+(?:(?:all|the|vanilla|nether|end|overworld|custom|new)\\s+)*(?:structures?|biomes?|mobs?|bosses|terrain)\\b(?!\\s+(?:generation\\s+)?(?:bugs?|issues?|artifacts?|glitches?))|(?:删除|移除|禁用)(?:全部|原版|下界|末地)?(?:群系|结构|生物|首领)(?!.{0,4}(?:错误|漏洞|瑕疵))|只保留.{0,12}(?:群系|地形)");
    private static final Pattern DENIAL = pattern("不要|不想|不需要|不使用|不加入|禁止|避免|排除|无需|而非|不是|别|\\b(?:no|not|without|avoid|exclude|never)\\b|don't|do not");
    private static final Pattern CONFIG_REMOVAL = pattern("\\b(?:remov\\w*|disabl\\w*|strip\\w*)\\s+(?:(?:all|the|vanilla|nether|end|overworld|custom|new)\\s+)*(?:structures?|biomes?|mobs?|bosses|terrain)\\s+from\\s+(?:the\\s+)?(?:config(?:uration)?|(?:black|white|allow|deny)[ -]?list)\\b|(?:从|在)(?:配置|黑名单|白名单|允许列表|禁用列表).{0,12}(?:删除|移除)(?:群系|结构|生物|首领)");
    public static List<Finding> findings(String facts) {
        if (facts == null || facts.isBlank()) return List.of();
        var result = new LinkedHashMap<Effect, Finding>();
        for (var statement : ProjectBehavior.statements(facts)) {
            if (statement.scope() != ProjectBehavior.Scope.OWN) continue;
            String sentence = statement.quote();
            if (VOID.matcher(sentence).find()) result.putIfAbsent(Effect.VOID_WORLD, new Finding(Effect.VOID_WORLD, sentence.trim()));
            if (FLAT.matcher(sentence).find() && FLAT_BEHAVIOR.matcher(sentence).find()) result.putIfAbsent(Effect.FLAT_WORLD, new Finding(Effect.FLAT_WORLD, sentence.trim()));
            // 编辑配置列表不等于删除世界中生成的内容。
            String behavior = CONFIG_REMOVAL.matcher(sentence).replaceAll("");
            if (REMOVAL.matcher(behavior).find()) result.putIfAbsent(Effect.CONTENT_REMOVAL, new Finding(Effect.CONTENT_REMOVAL, sentence.trim()));
        }
        return List.copyOf(result.values());
    }
    /** 搜索词和模型选中的 slug 不是授权；request 只能使用原始需求或已校验的需求原文。 */
    public static List<Finding> blocked(String request, String slug, String facts) {
        var findings = findings(facts);
        if (findings.isEmpty()) return findings;
        String source = Objects.requireNonNullElse(request, "");
        var allowed = EnumSet.noneOf(Effect.class);
        boolean named = false;
        for (String clause : source.split("[，,；;。\\n]|但是|\\bbut\\b")) {
            if (DENIAL.matcher(clause).find()) continue;
            if (slug != null && !slug.isBlank() && pattern("(?<![a-z0-9_-])" + Pattern.quote(slug) + "(?![a-z0-9_-])").matcher(clause).find()) named = true;
            if (VOID.matcher(clause).find()) allowed.add(Effect.VOID_WORLD);
            if (FLAT.matcher(clause).find()) allowed.add(Effect.FLAT_WORLD);
            // 内容削减涉及具体核心与对象，仅明确点名项目时授权，不能从泛泛的“丰富生态”推导。
        }
        if (named) return List.of();
        return findings.stream().filter(f -> !allowed.contains(f.effect())).toList();
    }
    public static String reason(List<Finding> findings) {
        return String.join("、", findings.stream().map(f -> switch (f.effect()) {
            case VOID_WORLD -> "空岛或虚空地形改造";
            case FLAT_WORLD -> "超平坦世界改造";
            case CONTENT_REMOVAL -> "删除或禁用生成内容";
        }).distinct().toList());
    }
}
