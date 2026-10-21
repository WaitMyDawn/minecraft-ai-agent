package yagen.waitmydawn.maa.model;

import java.util.*;

/** 请求内有界变更账本；仅记录身份和决策元数据，不保存项目原文、提示或鉴权材料。 */
public final class CandidateProvenance {
    public enum Source { INPUT, RETAINED, EXPLICIT, IMPORTED, IDENTITY_REPLACEMENT, RETRIEVAL, ADDON_DISCOVERY, CRITIC_APPROVED,
        BACKFILL, REQUIREMENT_REPAIR, ADDON_CHOICE_EXTENSION, LATE_RELATION_DISCOVERY, PACK_RELATION, DEPENDENCY }
    public record Change(String stage, String slug, String action, String version, String priorVersion) {}
    public record Entry(String slug, Set<Source> sources, Set<String> protection, boolean retained, boolean root,
                        String version, List<String> requiredBy, List<String> requirements, List<String> decisions) {}
    public record Report(int schemaVersion, List<Entry> projects, List<Change> changes, boolean truncated) {}
    public static Report afterSelection(Report before, AssemblySnapshot selected, DependencyClosure closure) {
        var changes = new ArrayList<>(before.changes());
        var entries = new ArrayList<Entry>(); boolean truncated = before.truncated();
        for (var row : before.projects()) {
            boolean kept = selected.versions().containsKey(row.slug());
            if (row.retained() && !kept) {
                if (changes.size() < MAX_CHANGES) changes.add(new Change("USER_SELECTION", row.slug(), "REMOVED", "", row.version()));
                else truncated = true;
            }
            var reasons = new TreeSet<>(row.decisions());
            if (row.retained() && !kept) reasons.add("USER_SELECTION_OR_ORPHAN_CLEANUP");
            var requiredBy = closure.requirements().stream().filter(r -> r.dependency().equals(row.slug()) && selected.versions().containsKey(r.dependent()))
                    .map(DependencyClosure.Requirement::dependent).distinct().sorted().toList();
            entries.add(new Entry(row.slug(),row.sources(),row.protection(),kept,selected.roots().contains(row.slug()),
                    selected.versions().getOrDefault(row.slug(),""),requiredBy,row.requirements(),List.copyOf(reasons)));
        }
        return bounded(entries,changes,truncated);
    }
    private static final int MAX_PROJECTS = 2000, MAX_CHANGES = 6000;
    private static final int MAX_REPORT_CHARS = 131072;
    private final Map<String, Set<Source>> sources = new TreeMap<>();
    private final Map<String, Set<String>> decisions = new TreeMap<>();
    private final List<Change> changes = new ArrayList<>();
    private Map<String, String> previous = new TreeMap<>();
    private boolean truncated;

    public void offer(Collection<String> slugs, Source source) { slugs.forEach(s -> offer(s, source)); }
    public void offer(String slug, Source source) {
        if (slug == null || slug.isBlank()) return;
        if (!sources.containsKey(slug) && sources.size() >= MAX_PROJECTS) { truncated = true; return; }
        sources.computeIfAbsent(slug, ignored -> EnumSet.noneOf(Source.class)).add(source);
    }
    public void decide(String slug, String reason) {
        if (!sources.containsKey(slug)) return;
        var values = decisions.computeIfAbsent(slug, ignored -> new TreeSet<>());
        if (values.size() < 24) values.add(reason); else truncated = true;
    }
    public void canonicalize(Map<String, String> aliases) {
        for (String ref : new ArrayList<>(sources.keySet())) {
            String slug = aliases.getOrDefault(ref, ref);
            if (slug.equals(ref)) continue;
            var found = sources.remove(ref); sources.computeIfAbsent(slug, ignored -> EnumSet.noneOf(Source.class)).addAll(found);
            var old = decisions.remove(ref); if (old != null) decisions.computeIfAbsent(slug, ignored -> new TreeSet<>()).addAll(old);
        }
        var canonical = new TreeMap<String, String>(); previous.forEach((s,v) -> canonical.put(aliases.getOrDefault(s,s),v)); previous = canonical;
    }
    public void checkpoint(String stage, Collection<String> selected, Map<String, tools.jackson.databind.JsonNode> versions) {
        var next = new TreeMap<String, String>();
        for (String slug : new TreeSet<>(selected)) {
            if (!sources.containsKey(slug)) offer(slug, Source.DEPENDENCY);
            if (!sources.containsKey(slug)) continue;
            next.put(slug, versions.containsKey(slug) ? versions.get(slug).path("id").asText() : "");
        }
        var all = new TreeSet<>(previous.keySet()); all.addAll(next.keySet());
        for (String slug : all) {
            String action = !next.containsKey(slug) ? "REMOVED" : !previous.containsKey(slug) ? "ADDED"
                    : !next.get(slug).equals(previous.get(slug)) ? previous.get(slug).isBlank() ? "VERSION_RESOLVED" : "VERSION_CHANGED" : "";
            if (!action.isBlank()) {
                if (changes.size() < MAX_CHANGES) changes.add(new Change(stage,slug,action,next.getOrDefault(slug,""),previous.getOrDefault(slug,"")));
                else truncated = true;
            }
        }
        previous = next;
    }
    public Report report(DependencyGraph graph, Set<String> selected, Set<String> roots,
                         Map<String, Set<String>> protection, Map<String, Set<String>> requirements) {
        var entries = new ArrayList<Entry>();
        sources.forEach((slug, origins) -> entries.add(new Entry(slug, Set.copyOf(origins), Set.copyOf(protection.getOrDefault(slug,Set.of())),
                selected.contains(slug), roots.contains(slug), selected.contains(slug) && graph.selectedVersions.containsKey(slug)
                    ? graph.selectedVersions.get(slug).path("id").asText() : "",
                graph.getDependentsOf(slug).stream().filter(selected::contains).sorted().toList(),
                requirements.getOrDefault(slug,Set.of()).stream().sorted().toList(), List.copyOf(decisions.getOrDefault(slug,Set.of())))));
        return bounded(entries,changes,truncated);
    }
    private static Report bounded(List<Entry> entries, List<Change> changes, boolean truncated) {
        var kept = new ArrayList<>(entries); var history = new ArrayList<>(changes);
        for (;;) {
            var report = new Report(1,List.copyOf(kept),List.copyOf(history),truncated);
            if (yagen.waitmydawn.maa.service.MessageJson.write(report).length() <= MAX_REPORT_CHARS) return report;
            truncated = true;
            int unretained = (int) kept.stream().filter(r -> !r.retained()).count();
            // 优先保留最终项目，限制诊断占用的历史消息存储预算。
            if (unretained > 0) {
                int remove = Math.max(1,unretained / 2);
                for (int i = kept.size()-1; i >= 0 && remove > 0; i--) if (!kept.get(i).retained()) { kept.remove(i); remove--; }
            } else if (!history.isEmpty()) history.subList(0,Math.max(1,history.size()/2)).clear();
            else if (!kept.isEmpty()) kept.subList(kept.size()-Math.max(1,kept.size()/4),kept.size()).clear();
            else return report;
        }
    }
}
