package yagen.waitmydawn.maa.service;

import com.github.benmanes.caffeine.cache.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.model.*;
import java.util.*;
import java.util.function.*;
import java.util.regex.*;

/** 附属确认只保存已装配的有界草案及展示事实，不保存执行线程、Agent 或鉴权材料。 */
public final class AddonSelections {
    public static final int MAX_EXTRA_CANDIDATES = 16;
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private record Pending(Long owner, Long conversation, String snapshot, String reply, AssemblySnapshot assembly,
                           DependencyClosure closure, List<ContentOverlap.Group> groups, Map<String, JsonNode> facts,
                           Map<String, List<String>> categories, PopupPreferences preferences,
                           Map<String, List<String>> generation, SelectionCoverage coverage) {}
    public record Confirmed(Supplier<String> continuation) { public String complete() { return continuation.get(); } }
    private final Cache<String, Pending> pending;
    private final OverlapSelections overlaps;

    public AddonSelections(OverlapSelections overlaps) { this(overlaps, Ticker.systemTicker()); }
    AddonSelections(OverlapSelections overlaps, Ticker ticker) {
        this.overlaps = overlaps;
        pending = Caffeine.newBuilder().ticker(ticker).expireAfterWrite(java.time.Duration.ofMinutes(15))
                .maximumWeight(16 * 1024 * 1024).weigher((String key, Pending value) -> weight(value)).build();
    }
    private static int weight(Pending value) {
        return value.reply().length() * 2 + MessageJson.write(value.facts()).length() * 2
                + MessageJson.write(value.groups()).length() * 2 + (value.coverage() == null ? 0 : MessageJson.write(value.coverage()).length() * 2)
                + value.closure().requirements().size() * 512 + 65536;
    }
    public String prepare(Long owner, Long conversation, Map<String, String> payload, String reply, AssemblySnapshot assembly,
                          DependencyClosure closure, ModrinthApiClient api, BooleanSupplier aborted,
                          Map<String, List<String>> generation, PopupPreferences preferences, Set<String> recommended) {
        return prepare(owner, conversation, payload, reply, assembly, closure, api, aborted, generation, preferences, recommended, null);
    }
    public String prepare(Long owner, Long conversation, Map<String, String> payload, String reply, AssemblySnapshot assembly,
                          DependencyClosure closure, ModrinthApiClient api, BooleanSupplier aborted,
                          Map<String, List<String>> generation, PopupPreferences preferences, Set<String> recommended, SelectionCoverage coverage) {
        if (!preferences.addons() || owner == null || assembly == null || closure == null)
            return overlaps.prepare(owner, conversation, payload, reply, assembly, closure, api, aborted, generation, preferences);
        var files = new HashSet<>(Arrays.asList(tag(reply, "mods").split(",")));
        if (!files.equals(assembly.versions().keySet()))
            return overlaps.prepare(owner, conversation, payload, reply, assembly, closure, api, aborted, generation, preferences);
        var trace = JSON.readTree(tag(reply, "trace"));
        var possible = new LinkedHashSet<String>();
        for (var row : trace.path("addonEvidence")) if (row.path("retained").asBoolean() && row.path("finalVersionVerified").asBoolean()
                && Set.of("ADDON_RELATION", "IN_PACK_INTEGRATION").contains(row.path("finalEvidenceStatus").asText())
                && assembly.roots().contains(row.path("slug").asText())) possible.add(row.path("slug").asText());
        if (possible.isEmpty()) return overlaps.prepare(owner, conversation, payload, reply, assembly, closure, api, aborted, generation, preferences);
        Map<String, JsonNode> loaded;
        try { loaded = api.getProjectsBatch(assembly.versions().keySet()); }
        catch (ModrinthApiClient.PartialProjectBatchException partial) { loaded = partial.projects(); }
        catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
        catch (RuntimeException unavailable) { loaded = Map.of(); }
        var facts = new TreeMap<String, JsonNode>(); var categories = new TreeMap<String, List<String>>();
        loaded.forEach((slug, info) -> {
            if (!assembly.versions().containsKey(slug) || info == null) return;
            var safe = JSON.createObjectNode();
            for (String field : List.of("title", "description", "body", "icon_url")) {
                String text = info.path(field).asText(); int limit = field.equals("body") ? 16000 : field.equals("title") ? 200 : 1000;
                safe.put(field, text.substring(0, Math.min(limit, text.length())));
            }
            safe.put("downloads", Math.max(0, info.path("downloads").asLong()));
            var tags = new ArrayList<String>();
            for (var category : info.path("categories")) if (CategoryRegistry.CATEGORY_SET.contains(category.asText())) tags.add(category.asText());
            safe.set("categories", JSON.valueToTree(tags)); categories.put(slug, List.copyOf(tags)); facts.put(slug, safe);
        });
        if (aborted.getAsBoolean()) return "⛔ 思考已手动终止。";
        var protectedSlugs = new HashSet<String>();
        assembly.origins().forEach((slug, roles) -> {
            if (!Collections.disjoint(roles, Set.of("CORE", "EXPLICIT_ADD", "REQUIRED_INTEGRATION", "IMPORTED", "RETAINED"))) protectedSlugs.add(slug);
        });
        var fixedRoots = new HashSet<>(assembly.roots()); fixedRoots.removeAll(possible); fixedRoots.addAll(protectedSlugs);
        var required = reachable(fixedRoots, closure); required.retainAll(possible); protectedSlugs.addAll(required);
        var grouped = new TreeMap<String, List<ContentOverlap.Candidate>>();
        for (var row : trace.path("addonEvidence")) {
            String slug = row.path("slug").asText();
            if (!possible.contains(slug)) continue;
            var info = facts.getOrDefault(slug, JSON.createObjectNode());
            String icon = info.path("icon_url").asText(); if (!icon.startsWith("https://cdn.modrinth.com/")) icon = "";
            for (var evidence : row.path("finalEvidence")) {
                String core = evidence.path("core").asText();
                if (!assembly.versions().containsKey(core)) continue;
                String quote = evidence.path("quote").asText();
                if (quote.isBlank()) quote = "已选兼容版本声明与 " + core + " 的附属关系";
                var partners = new ArrayList<String>(); evidence.path("partners").forEach(p -> partners.add(p.asText()));
                var candidate = new ContentOverlap.Candidate(slug, info.path("title").asText(slug), info.path("description").asText(), icon,
                        info.path("downloads").asLong(), partners.isEmpty() ? "核心附属" : "包内联动", quote,
                        "", "", protectedSlugs.contains(slug), recommended.contains(slug) || protectedSlugs.contains(slug), "",
                        partners.isEmpty() ? "" : "联动对象：" + String.join("、", partners));
                grouped.computeIfAbsent(core, ignored -> new ArrayList<>()).add(candidate);
            }
        }
        var groups = new ArrayList<ContentOverlap.Group>();
        grouped.forEach((core, candidates) -> {
            var unique = new LinkedHashMap<String, ContentOverlap.Candidate>(); candidates.forEach(c -> unique.putIfAbsent(c.slug(), c));
            String requests = coverage == null ? "" : String.join("；", RequirementQuantities.targets(coverage.requirements()).stream()
                    .filter(r -> r.addonCores().contains(core)).map(r -> r.source().substring(0, Math.min(240, r.source().length()))).distinct().limit(6).toList());
            groups.add(new ContentOverlap.Group("addons:" + core, core + " 的附属", "已核实核心关系与当前环境选版。可保留推荐、另选、多选或不选；点名项、必要联动和固定内容需要的前置受保护。减少选择可能使数量或用途要求不再满足。"
                    + (requests.isEmpty() ? "" : "本轮要求：" + requests), List.copyOf(unique.values()), true));
        });
        var offered = new HashSet<String>(); groups.forEach(g -> g.candidates().forEach(c -> offered.add(c.slug())));
        if (assembly.roots().stream().anyMatch(s -> !recommended.contains(s) && !offered.contains(s)))
            return "⚠️ 附属候选的关系快照不完整，本轮未修改清单，请重试。";
        if (groups.isEmpty()) return overlaps.prepareFromFacts(owner, conversation, payload, reply, assembly, closure, api, aborted, generation, preferences, facts);
        var value = new Pending(owner, conversation, ReplacementSelections.snapshot(payload), reply, assembly, closure, List.copyOf(groups),
                Map.copyOf(facts), Map.copyOf(categories), preferences, Map.copyOf(generation), coverage);
        String ticket = put(value);
        var defaults = new LinkedHashMap<String, ReplacementSelections.Selection>();
        groups.forEach(g -> defaults.put(g.original(), new ReplacementSelections.Selection(g.candidates().stream()
                .filter(c -> c.recommended() || c.protectedSelection()).map(ContentOverlap.Candidate::slug).toList(), false, "")));
        var defaultFiles = reachable(recommended, closure); defaultFiles.retainAll(assembly.versions().keySet());
        return "请确认核心附属，候选草案尚未提交，等待不占用构建名额。\n<replacement_options>"
                + MessageJson.write(Map.of("kind", "ADDONS", "ticket", ticket, "groups", groups, "recommendedChoices", defaults,
                    "loader", assembly.loader(), "mcVersion", assembly.mcVersion(), "candidateFiles", assembly.versions().size(), "recommendedFiles", defaultFiles.size()))
                + "</replacement_options>\n<trace>" + MessageJson.write(Map.of("branch", "AWAITING_INPUT", "awaitingInput", true, "addonGroups", groups.size())) + "</trace>";
    }
    private synchronized String put(Pending value) {
        pending.cleanUp();
        if (weight(value) > 16 * 1024 * 1024) throw new IllegalArgumentException("附属候选草案超过容量，请减少单次构建规模");
        pending.asMap().entrySet().removeIf(e -> Objects.equals(e.getValue().owner(), value.owner()));
        if (pending.asMap().size() >= 256) throw new IllegalArgumentException("待确认草案已满，请稍后重试");
        String ticket = UUID.randomUUID().toString(); pending.put(ticket, value); return ticket;
    }
    public synchronized boolean cancel(String ticket, Long owner) {
        var value = pending.getIfPresent(ticket);
        return value != null && Objects.equals(value.owner(), owner) && pending.asMap().remove(ticket, value);
    }
    public synchronized <T> T admit(String ticket, Long owner, Long conversation, Map<String, String> payload,
                                    ModrinthApiClient api, BooleanSupplier aborted, Function<Confirmed, T> submit) {
        var value = pending.getIfPresent(ticket);
        if (value == null || !Objects.equals(owner, value.owner()) || !Objects.equals(conversation, value.conversation())
                || !ReplacementSelections.snapshot(payload).equals(value.snapshot())) throw new IllegalArgumentException("附属确认已过期或工作区已变化，请重新构建");
        Set<String> rejected = choices(value, payload.getOrDefault("replacementChoices", ""));
        var copiedPayload = Map.copyOf(payload);
        T task = submit.apply(new Confirmed(() -> {
            if (aborted.getAsBoolean()) return "⛔ 思考已手动终止。";
            String result = OverlapSelections.finishSnapshot(value.reply(), value.assembly(), value.closure(), value.categories(), rejected);
            var trace = (ObjectNode) JSON.readTree(tag(result, "trace"));
            yagen.waitmydawn.maa.runtime.BuildProgress.stage("应用附属选择与重算需求覆盖");
            trace.set("addonSelection", JSON.valueToTree(Map.of("rejected", rejected, "candidateGroups", value.groups().size())));
            var selected = JSON.treeToValue(trace.path("assembly"), AssemblySnapshot.class);
            if (value.coverage() != null) {
                var report = JSON.valueToTree(value.coverage().assess(selected));
                var removed=new HashSet<>(value.assembly().versions().keySet()); removed.removeAll(selected.versions().keySet());
                RequirementAssessment.refresh(report,JSON.readTree(tag(value.reply(),"requirement_report")),rejected,removed);
                trace.set("requirementCoverage", report);
                result = result.replaceFirst("(?s)<requirement_report>.*?</requirement_report>", Matcher.quoteReplacement("<requirement_report>" + MessageJson.write(report) + "</requirement_report>"));
            }
            result = result.replaceFirst("(?s)<trace>.*?</trace>", Matcher.quoteReplacement("<trace>" + MessageJson.write(trace) + "</trace>"));
            result = BuildCompletion.enrich(result);
            var closure = new DependencyClosure(selected.versions(), value.closure().requirements().stream()
                    .filter(r -> selected.versions().containsKey(r.dependent())).toList(), value.closure().unresolved().stream()
                    .filter(i -> selected.versions().containsKey(i.dependent())).toList());
            return overlaps.prepareFromFacts(owner, conversation, copiedPayload, result, selected, closure, api, aborted,
                    value.generation(), value.preferences(), value.facts());
        }));
        pending.asMap().remove(ticket, value); return task;
    }
    private static Set<String> choices(Pending value, String raw) {
        if (raw.length() > 512000) throw new IllegalArgumentException("附属选择超过输入限额");
        JsonNode choices;
        try { choices = JSON.readTree(raw); } catch (RuntimeException invalid) { throw new IllegalArgumentException("附属选择格式无效"); }
        if (choices == null || !choices.isObject() || choices.size() != value.groups().size()) throw new IllegalArgumentException("请处理全部附属分组");
        var states = new HashMap<String, Boolean>();
        for (var group : value.groups()) {
            var choice = choices.path(group.original()); var selected = new HashSet<String>();
            if (!choice.path("slugs").isArray() || !choice.path("drop").isBoolean() || choice.path("drop").asBoolean()
                    || !choice.path("manualSlug").isTextual() || !choice.path("manualSlug").asText().isEmpty()) throw new IllegalArgumentException("附属确认只能选择已提供的候选");
            var allowed = group.candidates().stream().map(ContentOverlap.Candidate::slug).toList();
            for (var slug : choice.path("slugs")) if (!slug.isTextual() || !allowed.contains(slug.asText()) || !selected.add(slug.asText())) throw new IllegalArgumentException("附属选择包含未知或重复项目");
            for (var candidate : group.candidates()) {
                boolean keep = selected.contains(candidate.slug());
                if (!keep && candidate.protectedSelection()) throw new IllegalArgumentException("点名附属、必要联动和固定内容需要的前置必须保留");
                Boolean prior = states.putIfAbsent(candidate.slug(), keep);
                if (prior != null && prior != keep) throw new IllegalArgumentException("同一附属在不同核心组的选择不一致");
            }
        }
        var rejected = new HashSet<String>(); states.forEach((slug, keep) -> { if (!keep) rejected.add(slug); });
        var roots = new HashSet<>(value.assembly().roots()); roots.removeAll(rejected);
        if (!Collections.disjoint(reachable(roots, value.closure()), rejected)) throw new IllegalArgumentException("保留项目需要已取消的附属前置，请同时调整相关选择");
        for (var row : JSON.readTree(tag(value.reply(), "trace")).path("addonEvidence")) if (roots.contains(row.path("slug").asText()))
            for (var evidence : row.path("finalEvidence")) for (var partner : evidence.path("partners"))
                if (rejected.contains(partner.asText())) throw new IllegalArgumentException("保留附属的联动对象已取消，请同时调整相关选择");
        return Set.copyOf(rejected);
    }
    private static Set<String> reachable(Set<String> roots, DependencyClosure closure) {
        var result = new HashSet<>(roots); var queue = new ArrayDeque<>(roots); var children = new HashMap<String, List<String>>();
        closure.requirements().forEach(r -> children.computeIfAbsent(r.dependent(), ignored -> new ArrayList<>()).add(r.dependency()));
        while (!queue.isEmpty()) for (String dependency : children.getOrDefault(queue.removeFirst(), List.of())) if (result.add(dependency)) queue.addLast(dependency);
        return result;
    }
    private static String tag(String reply, String name) {
        var matcher = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(reply);
        return matcher.find() ? matcher.group(1) : "{}";
    }
}
