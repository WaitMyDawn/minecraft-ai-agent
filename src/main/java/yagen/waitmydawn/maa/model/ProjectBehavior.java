package yagen.waitmydawn.maa.model;

import java.util.*;
import java.util.regex.Pattern;

/** 保留原文片段及其说明范围，避免把预设、配置能力和历史问题当作项目当前默认行为。 */
public final class ProjectBehavior {
    private ProjectBehavior() {}
    public enum Scope { OWN, OPTIONAL, REFERENCE, HISTORY }
    public record Statement(String quote, Scope scope) {}
    private static Pattern pattern(String regex) { return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE); }
    private static final Pattern HEADING = pattern("^\\s*(#{1,6})\\s+(.+)$");
    private static final Pattern HTML_HEADING = pattern("^\\s*<h([1-6])(?:\\s+[^>]*)?>(.*?)</h\\1>\\s*$");
    private static final Pattern OPTIONAL_SECTION = pattern("optional|configuration|config options|world presets|custom presets|可选|配置|世界预设");
    private static final Pattern REFERENCE_SECTION = pattern("compatibility|integrations?|兼容|联动");
    private static final Pattern HISTORY_SECTION = pattern("changelog|release notes|version history|更新日志|版本历史");
    private static final Pattern OPTIONAL = pattern("\\boptionally\\b|\\bcan\\s+(?:be|disable|remove|enable|create|choose|select)\\b|\\b(?:if|when)\\s+(?:enabled|selected|you (?:enable|select))\\b|\\bconfigur\\w*|\\b(?:with|using|via)\\s+(?:a\\s+)?(?:single\\s+)?(?:setting|option|toggle)|\\b(?:choose|select|enable)\\s+(?:the\\s+)?(?:preset|world type)|to disable|to remove|how to|可选|配置|可以禁用|可禁用|启用后|选择后|通过.{0,8}(?:选项|设置)|选择.{0,8}预设");
    private static final Pattern PRESET_ENTRY = pattern("^.{1,100}\\(\\s*`?[a-z0-9_.-]+:[a-z0-9_./-]+`?\\s*\\)\\s*:");
    private static final Pattern REFERENCE = pattern("(?<!not )\\bcompatible with|compatibility with|integration with|support for|(?<!not )\\bworks? (?:with|on)|respect\\w*\\s+(?:superflat|skyblock)|适用于|(?<!不)兼容|联动|(?:does not|doesn't|never)\\s+(?:remove|disable|create|replace)|不(?:会)?(?:删除|移除|禁用|变成|替换)|without\\s+(?:removing|disabling)|(?:not(?: a)?|no)\\s+(?:skyblock|void world|superflat)|不是空岛|并非空岛");
    private static final Pattern HISTORY = pattern("\\b(?:previously|formerly|used to|broke|fixed|fixes|resolved)\\b|was\\s+(?:one of|incompatible)|曾经|过去|历史版本|修复.{0,30}(?:问题|错误|崩溃)");
    private static final Pattern DEFAULT = pattern("\\bby default\\b|\\bdefault (?:behavior|generation|world|preset)\\b|默认(?:行为|生成|世界|预设|会|移除|禁用)");
    private static final String SENTENCES = "[.!?。！？;；\\n]+|\\s+(?i:but|however|and|while)\\s+(?=(?i:(?:it\\s+)?(?:also\\s+)?(?:creates?|removes?|disables?|overhauls?|revamps?|adds?|transforms?)\\b))|但是|但(?=默认|会|移除|禁用|生成)";

    public static List<Statement> statements(String text) {
        if (text == null || text.isBlank()) return List.of();
        var result = new ArrayList<Statement>();
        var sections = new TreeMap<Integer, Scope>();
        Scope paragraphScope = null;
        boolean fenced = false;
        for (String line : text.split("\\R")) {
            if (line.stripLeading().startsWith("```") || line.stripLeading().startsWith("~~~")) { fenced = !fenced; continue; }
            if (fenced) continue;
            if (line.isBlank()) { paragraphScope = null; continue; }
            var heading = HEADING.matcher(line);
            var htmlHeading = HTML_HEADING.matcher(line);
            boolean markdown = heading.matches();
            if (markdown || htmlHeading.matches()) {
                int depth = markdown ? heading.group(1).length() : Integer.parseInt(htmlHeading.group(1));
                String title = markdown ? heading.group(2) : htmlHeading.group(2);
                sections.tailMap(depth).clear();
                sections.put(depth, sectionScope(title, sections.isEmpty() ? Scope.OWN : sections.lastEntry().getValue()));
                paragraphScope = null;
                continue;
            }
            Scope inherited = sections.isEmpty() ? Scope.OWN : sections.lastEntry().getValue();
            if (line.strip().endsWith(":")) {
                paragraphScope = sectionScope(line, Scope.OWN);
                if (paragraphScope != Scope.OWN) continue;
            }
            if (paragraphScope != null && paragraphScope != Scope.OWN) inherited = paragraphScope;
            // 预设标识包含点号，必须在断句前识别；泛称单个空岛预设不自动获得豁免。
            boolean presetEntry = PRESET_ENTRY.matcher(line.strip()).find();
            for (String raw : line.split(SENTENCES)) {
                String quote = raw.trim();
                if (quote.isEmpty() || quote.length() > 2000) continue;
                Scope scope = inherited;
                if (HISTORY.matcher(quote).find()) scope = Scope.HISTORY;
                else if (REFERENCE.matcher(quote).find()) scope = Scope.REFERENCE;
                else if (DEFAULT.matcher(quote).find()) scope = Scope.OWN;
                else if (presetEntry || OPTIONAL.matcher(quote).find()) scope = Scope.OPTIONAL;
                result.add(new Statement(quote, scope));
            }
        }
        return List.copyOf(result);
    }
    private static Scope sectionScope(String title, Scope inherited) {
        if (HISTORY_SECTION.matcher(title).find()) return Scope.HISTORY;
        if (REFERENCE_SECTION.matcher(title).find()) return Scope.REFERENCE;
        if (OPTIONAL_SECTION.matcher(title).find()) return Scope.OPTIONAL;
        return inherited;
    }
    /** 引用必须位于当前自身行为的完整原文范围内，不能裁去预设标题后重新取得默认行为身份。 */
    public static boolean owns(String facts, String quote) {
        if (quote == null || quote.isBlank()) return false;
        String normalized = quote.replaceAll("(?U)\\s+", " ").trim();
        return statements(facts).stream().filter(s -> s.scope() == Scope.OWN)
                .anyMatch(s -> s.quote().replaceAll("(?U)\\s+", " ").trim().contains(normalized));
    }
}
