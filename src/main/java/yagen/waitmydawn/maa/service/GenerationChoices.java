package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.function.BooleanSupplier;

/** 只发现有描述依据且已核实环境版本的选项；等待票据不持有请求、密钥或执行线程。 */
public final class GenerationChoices {
    private GenerationChoices() {}
    public static List<ContentOverlap.Group> discover(ModrinthApiClient api, BuildIntent intent, Set<String> protectedSlugs,
                                                      RetrievalBudget budget, BooleanSupplier aborted) {
        if (intent.excludedCategories().contains("worldgen")) return List.of();
        var goals = DimensionGeneration.targets(intent.requirements()).values().stream().flatMap(List::stream)
                .distinct().limit(4).toList();
        var groups = new ArrayList<ContentOverlap.Group>();
        var compatibleVersions = new HashMap<String, Boolean>();
        for (var goal : goals) {
            var candidates = new LinkedHashMap<String, ContentOverlap.Candidate>();
            var compatibility = new HashMap<String, String>();
            for (String query : List.of(goal.query(), goal.dimension())) {
                if (aborted.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                String index = query.equals(goal.query()) ? null : "downloads";
                if (!budget.search("generation:" + query + ":" + index)) continue;
                try {
                    String facets = "[[\"project_type:mod\"],[\"loaders:" + intent.environment().loader().name().toLowerCase(Locale.ROOT)
                            + "\"],[\"versions:" + intent.environment().mcVersion() + "\"],[\"categories:worldgen\"]]";
                    var response = api.searchPage(query, 15, 0, index, facets);
                    if (response == null) { budget.unknown(); continue; }
                    var hits = new ArrayList<JsonNode>(); response.path("hits").forEach(hits::add);
                    hits.removeIf(h -> intent.exclude().contains(h.path("slug").asText()) || intent.remove().contains(h.path("slug").asText())
                            || h.path("downloads").asLong() > intent.maxDownloads());
                    hits.sort(Comparator.<JsonNode, Boolean>comparing(h -> DimensionGeneration.supports(goal, h.path("description").asText())).reversed()
                            .thenComparing(h -> DimensionGeneration.richEvidence(h.path("description").asText()).isEmpty())
                            .thenComparing(Comparator.comparingLong((JsonNode h) -> h.path("downloads").asLong()).reversed())
                            .thenComparing(h -> h.path("slug").asText()));
                    var references = hits.stream().map(h -> h.path("slug").asText()).filter(ModrinthIdentifiers::isProjectReference)
                            .distinct().limit(6).toList();
                    var facts = api.getProjectsBatch(references);
                    for (String slug : references) {
                        if (aborted.getAsBoolean()) throw new java.util.concurrent.CancellationException();
                        if (candidates.containsKey(slug)) continue;
                        var info = facts.get(slug);
                        if (info == null) { budget.unknown(); continue; }
                        String description = info.path("description").asText(), body = info.path("body").asText();
                        String text = description + "\n" + body.substring(0, Math.min(16000, body.length()));
                        String request = String.join("\n", intent.requirements().stream().map(BuildRequirement::source).toList());
                        if (!WorldGenerationPolicy.blocked(request, slug, text).isEmpty()) continue;
                        var profile = ContentOverlap.generationCandidate(new ContentOverlap.Project(slug, info.path("title").asText(slug),
                                description, body, "", info.path("downloads").asLong()), protectedSlugs);
                        if (profile.isEmpty()) continue;
                        var candidate = profile.get();
                        if (!candidate.scope().equals("介绍未明确范围") && !Arrays.asList(candidate.scope().split(" / ")).contains(goal.label())) continue;
                        String strictQuote = DimensionGeneration.evidence(goal, text);
                        String quote = strictQuote.isEmpty() ? candidate.evidence() : strictQuote;
                        if (!budget.version(slug)) continue;
                        if (!compatibleVersions.containsKey(slug)) compatibleVersions.put(slug,
                                api.getLatestCompatibleVersionBySlug(slug, intent.environment().mcVersion(), intent.environment().loader().name().toLowerCase(Locale.ROOT)) != null);
                        if (!compatibleVersions.get(slug)) continue;
                        String icon = info.path("icon_url").asText();
                        if (!icon.startsWith("https://cdn.modrinth.com/") || icon.length() > 1000) icon = "";
                        for (String line : body.split("\\R")) if (line.toLowerCase(Locale.ROOT).matches(".*(?:incompatible|not.{0,12}compat[ia]ble|不兼容|不能共存).*")) {
                            compatibility.put(slug, line.substring(0, Math.min(400, line.length()))); break;
                        }
                        candidates.put(slug, new ContentOverlap.Candidate(slug, info.path("title").asText(slug),
                                description.substring(0, Math.min(600, description.length())), icon, info.path("downloads").asLong(),
                                candidate.relation(), quote, candidate.scope(), candidate.scopeEvidence(), protectedSlugs.contains(slug), false,
                                "", strictQuote.isEmpty() ? candidate.scopeNote() : ""));
                        if (candidates.size() >= 3) break;
                    }
                } catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
                catch (RuntimeException unavailable) { budget.unknown(); }
                if (candidates.size() >= 2) break;
            }
            var ordered = candidates.values().stream().sorted(Comparator.comparing(ContentOverlap.Candidate::protectedSelection).reversed()
                    .thenComparing(c -> !c.scopeNote().isEmpty())
                    .thenComparing(Comparator.comparingLong(ContentOverlap.Candidate::downloads).reversed()).thenComparing(ContentOverlap.Candidate::slug)).toList();
            if (ordered.isEmpty()) continue;
            var cards = new ArrayList<ContentOverlap.Candidate>();
            for (int i = 0; i < ordered.size(); i++) {
                var c = ordered.get(i);
                cards.add(new ContentOverlap.Candidate(c.slug(), c.title(), c.description(), c.iconUrl(), c.downloads(), c.relation(),
                        c.evidence(), c.scope(), c.scopeEvidence(), c.protectedSelection(), c.protectedSelection() || i == 0,
                        compatibility.getOrDefault(c.slug(), ""), c.scopeNote()));
            }
            groups.add(new ContentOverlap.Group("generation:" + goal.dimension(), goal.label() + "核心世界生成",
                    "为" + goal.label() + "扩展选择生成内容。卡片分别展示介绍依据、实际提及的维度及尚未核实的范围；跨维度总数不能证明每个维度都足够丰富。可选择一个、多个或全部；组合可能改变生成风格，尚未验证运行冲突。", cards, true));
        }
        return groups.stream().anyMatch(g -> g.candidates().size() > 1 || g.candidates().stream().anyMatch(c -> !c.scopeNote().isEmpty()))
                ? List.copyOf(groups) : List.of();
    }
    public static boolean allows(Map<String, List<String>> confirmed, String slug, String facts) {
        for (var choice : confirmed.entrySet()) {
            String dimension = choice.getKey().substring("generation:".length());
            if (!choice.getValue().contains(slug) && (DimensionGeneration.supports(new DimensionGeneration.Goal(dimension, dimension, ""), facts)
                    || ContentOverlap.majorChangeIn(slug, facts, dimension))) return false;
        }
        return true;
    }
}
