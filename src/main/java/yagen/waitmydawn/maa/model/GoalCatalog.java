package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.BuildIntent;
import yagen.waitmydawn.maa.model.agent.BuildRequirement;
import yagen.waitmydawn.maa.model.agent.BuildRequirement.Capability;
import java.util.*;
import java.util.regex.Pattern;

/** 能力召回词与用途证据；种子仅是待核实候选，不声明跨环境兼容或玩法满足。 */
public final class GoalCatalog {
    private GoalCatalog() {}
    private static final Map<String, Pattern> PATTERNS = new java.util.concurrent.ConcurrentHashMap<>();
    public record Profile(Capability capability, String category, List<String> queries, List<String> seeds, int target, Set<String> focus) {
        public Profile(Capability capability, String category, List<String> queries, List<String> seeds, int target) {
            this(capability, category, queries, seeds, target, Set.of());
        }
    }
    private static Profile p(Capability c, String category, List<String> queries, List<String> seeds, int target) {
        return new Profile(c, category, queries, seeds, target);
    }
    public static Profile profile(Capability c, int size) {
        return switch (c) {
            case TERRAIN -> p(c, "worldgen", List.of("terrain generation", "landscape"), List.of("tectonic"), 1);
            case BIOMES -> p(c, "worldgen", List.of("biomes", "biome generation"), List.of("biomes-o-plenty", "regions-unexplored", "terralith"), size >= 80 ? 2 : 1);
            case STRUCTURES -> p(c, "adventure", List.of("dungeons", "structures"), List.of("repurposed-structures", "dungeons-and-taverns"), size >= 80 ? 3 : 1);
            case CREATURE_ECOLOGY -> p(c, "mobs", List.of("wildlife", "aquatic creatures", "creatures"), List.of("naturalist", "mowzies-mobs", "aquamirae"), size >= 80 ? 4 : 1);
            case NETHER -> p(c, "worldgen", List.of("nether biomes", "nether structures", "nether mobs"), List.of("incendium"), size >= 80 ? 2 : 1);
            case END -> p(c, "worldgen", List.of("end biomes", "end structures", "end mobs"), List.of("nullscape", "enderscape"), size >= 80 ? 2 : 1);
            case PERFORMANCE -> p(c, "optimization", List.of("performance optimization", "rendering optimization"), List.of("sodium", "ferrite-core", "modernfix", "entityculling"), size >= 80 ? 4 : 2);
            case MODERN_UI -> p(c, "utility", List.of("user interface", "modern ui"), List.of("modern-ui", "fancymenu"), 1);
            case FIXES -> p(c, "utility", List.of("bug fixes", "fixes"), List.of("modernfix", "debugify"), size >= 80 ? 2 : 1);
            case UTILITY -> p(c, "utility", List.of("quality of life", "recipe viewer"), List.of("appleskin", "jei", "jade"), size >= 80 ? 3 : 1);
        };
    }
    public static List<Profile> active(BuildIntent intent, int size) {
        if (intent == null) return List.of();
        var capabilities = EnumSet.noneOf(Capability.class);
        intent.requirements().stream().filter(r -> r.kind() != BuildRequirement.Kind.CHANGE && r.kind() != BuildRequirement.Kind.PROJECTS
                        && r.searches().isEmpty() && r.criteria().isEmpty())
                .forEach(r -> capabilities.addAll(r.capabilities()));
        var result = new ArrayList<Profile>();
        for (var c : capabilities) {
            var related = intent.requirements().stream().filter(r -> r.kind() != BuildRequirement.Kind.CHANGE && r.kind() != BuildRequirement.Kind.PROJECTS
                    && r.searches().isEmpty() && r.criteria().isEmpty() && r.capabilities().contains(c)).toList();
            if (Set.of(Capability.BIOMES, Capability.TERRAIN, Capability.STRUCTURES).contains(c)
                    && related.stream().allMatch(r -> r.capabilities().contains(Capability.NETHER) || r.capabilities().contains(Capability.END))
                    && related.stream().noneMatch(r -> r.source().contains("主世界"))) continue;
            var p = profile(c, size);
            if (Set.of(Capability.NETHER, Capability.END).contains(c) && related.stream().noneMatch(r -> r.source().contains("生态"))) {
                var focus = new LinkedHashSet<String>();
                for (var r : related) {
                    if (r.capabilities().contains(Capability.BIOMES)) focus.add(c + ":biomes");
                    if (r.capabilities().contains(Capability.STRUCTURES)) focus.add(c + ":structures");
                    if (r.capabilities().contains(Capability.TERRAIN)) focus.add(c + ":terrain");
                    if (r.capabilities().contains(Capability.CREATURE_ECOLOGY)) focus.add(c + ":creatures");
                }
                if (!focus.isEmpty()) {
                    String realm = c == Capability.NETHER ? "nether" : "end";
                    var queries = focus.stream().map(f -> realm + " " + f.substring(f.indexOf(':') + 1).replace("creatures", "mobs")).toList();
                    p = new Profile(c, p.category(), queries, List.of(), Math.min(2, p.target()), Set.copyOf(focus));
                }
            }
            if (!intent.excludedCategories().contains(p.category())) result.add(p);
        }
        return List.copyOf(result);
    }
    private static boolean word(String text, String pattern) {
        return PATTERNS.computeIfAbsent(pattern, p -> Pattern.compile("(?i)\\b(?:" + p + ")\\b")).matcher(text).find();
    }
    public static String queryCategory(Profile p, String query) {
        return (p.capability() == Capability.NETHER || p.capability() == Capability.END) && query.endsWith("mobs") ? "mobs" : p.category();
    }
    public static String evidence(Profile profile, String slug, String title, String description) {
        String heading = (slug + " " + title).toLowerCase(Locale.ROOT);
        String text = (heading + " " + description).toLowerCase(Locale.ROOT);
        if (word(heading, "library|api|compatibility|compat|integration|addon|add-on|delight")) return null;
        if (!profile.focus().isEmpty() && profile.focus().stream().noneMatch(f -> switch (f.substring(f.indexOf(':') + 1)) {
            case "biomes" -> word(text, "biomes?");
            case "structures" -> word(text, "structures?|dungeons?");
            case "terrain" -> word(text, "terrain|landscape");
            case "creatures" -> word(text, "creatures?|mobs|wildlife|animals?");
            default -> false;
        })) return null;
        if (profile.seeds().contains(slug)) return "已知用途候选身份；仍需环境版本和组合审核";
        boolean match = switch (profile.capability()) {
            case TERRAIN -> word(text, "terrain|landscape|mountains") && word(text, "generation|generator|overhaul|worldgen|world|terrain")
                    && (!word(text, "nether|the end|end dimension|end islands?") || word(text, "overworld|surface"));
            case BIOMES -> word(text, "biomes?") && !word(heading, "nether|end")
                    && (!word(text, "nether|the end|end dimension|end biomes?") || word(text, "overworld|surface"));
            case STRUCTURES -> word(text, "structures?|dungeons?|towers?|villages?");
            case CREATURE_ECOLOGY -> word(text, "animals?|wildlife|creatures?|mobs|fauna") && !word(heading, "animation|texture|sounds?|skins?");
            case NETHER -> word(text, "nether") && word(text, "biomes?|structures?|mobs|generation|overhaul|content|dimension");
            case END -> word(text, "the end|end dimension|end biomes?|end structures?|end islands?|end worldgen|end mobs");
            case PERFORMANCE -> !OptimizationPortfolio.classify(description).isEmpty();
            case MODERN_UI -> word(text, "user interface|modern ui|gui|menus?|interface|hud")
                    && word(text, "modern|redesign|overhaul|customizable|enhanced|improved|improves") && !word(heading, "library|api");
            case FIXES -> word(text, "bug fixes|bugfix|bugfixes|fixes|fix bugs|fixing bugs") && !word(text, "resource pack");
            case UTILITY -> word(text, "quality of life|recipe viewer|recipes|inventory|tooltip|tooltips|hunger|food information|item information");
        };
        return match ? "项目标题/说明中有对应用途线索，丰富度待评" : null;
    }
    public static Set<String> facets(Profile p, String slug, String title, String description) {
        if (evidence(p, slug, title, description) == null) return Set.of();
        String text = (slug + " " + title + " " + description).toLowerCase(Locale.ROOT);
        var result = new LinkedHashSet<String>();
        if (Set.of(Capability.NETHER, Capability.END, Capability.CREATURE_ECOLOGY).contains(p.capability())) {
            if (word(text, "biomes?")) result.add(p.capability() + ":biomes");
            if (word(text, "structures?|dungeons?")) result.add(p.capability() + ":structures");
            if (word(text, "terrain|landscape")) result.add(p.capability() + ":terrain");
            if (word(text, "mobs|creatures?|animals?|wildlife")) result.add(p.capability() + ":creatures");
            if (word(text, "aquatic|ocean|fish|marine")) result.add(p.capability() + ":aquatic");
            if (word(text, "wildlife|animals?|fauna")) result.add(p.capability() + ":wildlife");
            if (slug.equals("aquamirae")) { result.add(p.capability() + ":aquatic"); result.add(p.capability() + ":creatures"); }
            if (slug.equals("naturalist")) { result.add(p.capability() + ":wildlife"); result.add(p.capability() + ":creatures"); }
        }
        if (p.capability() == Capability.PERFORMANCE)
            OptimizationPortfolio.classify(description).keySet().forEach(role -> result.add("PERFORMANCE:" + role.id));
        if (p.capability() == Capability.UTILITY) {
            if (word(text, "jei|recipe|recipes")) result.add("UTILITY:recipes");
            if (word(text, "appleskin|hunger|food")) result.add("UTILITY:food");
            if (word(text, "inventory")) result.add("UTILITY:inventory");
            if (word(text, "tooltip|tooltips|jade")) result.add("UTILITY:information");
        }
        return Set.copyOf(result);
    }
    public static Set<String> desiredFacets(Profile p) {
        if (!p.focus().isEmpty()) return p.focus();
        if (p.capability() == Capability.PERFORMANCE && p.target() >= 4)
            return Set.of("PERFORMANCE:renderer", "PERFORMANCE:memory");
        if (p.capability() == Capability.UTILITY && p.target() >= 3)
            return Set.of("UTILITY:recipes", "UTILITY:food");
        if (Set.of(Capability.NETHER, Capability.END).contains(p.capability()) && p.target() >= 2)
            return Set.of(p.capability() + ":biomes", p.capability() + ":structures", p.capability() + ":creatures");
        if (p.capability() == Capability.CREATURE_ECOLOGY && p.target() >= 4)
            return Set.of("CREATURE_ECOLOGY:wildlife", "CREATURE_ECOLOGY:aquatic");
        return Set.of();
    }
    /** 缺口优先安排合格候选；预算紧张时一次覆盖多个目标，热度只决定同等候选顺序。 */
    public static List<String> order(List<String> eligible, Map<String, Set<Capability>> evidence,
                                     Map<Capability, Integer> targets, Set<String> existing) {
        return order(eligible, evidence, targets, existing, Map.of());
    }
    public static List<String> order(List<String> eligible, Map<String, Set<Capability>> evidence,
                                     Map<Capability, Integer> targets, Set<String> existing, Map<String, Set<String>> facets) {
        var counts = new EnumMap<Capability, Integer>(Capability.class);
        var covered = new HashSet<String>();
        existing.forEach(s -> covered.addAll(facets.getOrDefault(s, Set.of())));
        existing.forEach(s -> evidence.getOrDefault(s, Set.of()).forEach(c -> counts.merge(c, 1, Integer::sum)));
        var remaining = new ArrayList<>(new LinkedHashSet<>(eligible));
        var result = new ArrayList<String>();
        while (!remaining.isEmpty()) {
            String best = remaining.getFirst(); int bestGain = -1;
            for (String s : remaining) {
                int gain = evidence.getOrDefault(s, Set.of()).stream()
                        .filter(c -> counts.getOrDefault(c, 0) < targets.getOrDefault(c, 0))
                        .mapToInt(c -> counts.getOrDefault(c, 0) == 0 ? 4 : 2).sum()
                        + (int) facets.getOrDefault(s, Set.of()).stream().filter(f -> !covered.contains(f)).count();
                if (gain > bestGain) { best = s; bestGain = gain; }
            }
            remaining.remove(best); result.add(best);
            evidence.getOrDefault(best, Set.of()).forEach(c -> counts.merge(c, 1, Integer::sum));
            covered.addAll(facets.getOrDefault(best, Set.of()));
        }
        return List.copyOf(result);
    }
}
