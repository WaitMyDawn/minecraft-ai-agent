package yagen.waitmydawn.maa.service;

import com.github.benmanes.caffeine.cache.*;
import tools.jackson.databind.*;
import tools.jackson.databind.node.*;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.regex.*;

/** 等待期间只持有有界不可变草案；确认不重新调用 Agent，不持有执行槽或个人密钥。 */
public final class OverlapSelections {
    private static final ObjectMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private record Pending(Long owner, Long conversation, String snapshot, String reply, AssemblySnapshot assembly,
                           DependencyClosure closure, List<ContentOverlap.Group> groups, Map<String, List<String>> categories) {}
    public record Confirmed(String reply, Runnable acknowledge) {
        public String complete() { acknowledge.run(); return reply; }
    }
    private final Cache<String, Boolean> acknowledgements;
    private final Cache<String, Pending> pending;
    public OverlapSelections() { this(Ticker.systemTicker()); }
    OverlapSelections(Ticker ticker) {
        acknowledgements = Caffeine.newBuilder().ticker(ticker).maximumSize(4096).expireAfterWrite(java.time.Duration.ofHours(24)).build();
        pending = Caffeine.newBuilder().ticker(ticker).expireAfterWrite(java.time.Duration.ofMinutes(15))
                .maximumWeight(16 * 1024 * 1024).weigher((String key, Pending value) -> weight(value)).build();
    }
    private static int weight(Pending value) {
        return value.reply().length() * 2 + MessageJson.write(value.groups()).length() * 2
                + value.closure().requirements().size() * 512 + value.closure().unresolved().size() * 512 + 65536;
    }
    private static String snapshot(Map<String, String> payload) {
        try {
            return ReplacementSelections.snapshot(payload) + ":" + HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(payload.getOrDefault("assembly", "").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public String prepare(Long owner, Long conversation, Map<String, String> payload, String reply,
                          AssemblySnapshot assembly, DependencyClosure closure, ModrinthApiClient api) {
        return prepare(owner, conversation, payload, reply, assembly, closure, api, () -> false);
    }
    public String prepare(Long owner, Long conversation, Map<String, String> payload, String reply,
                          AssemblySnapshot assembly, DependencyClosure closure, ModrinthApiClient api, java.util.function.BooleanSupplier aborted) {
        return prepare(owner, conversation, payload, reply, assembly, closure, api, aborted, Map.of());
    }
    public String prepare(Long owner, Long conversation, Map<String, String> payload, String reply,
                          AssemblySnapshot assembly, DependencyClosure closure, ModrinthApiClient api, java.util.function.BooleanSupplier aborted,
                          Map<String, List<String>> confirmedGeneration) {
        if (owner == null || assembly == null || closure == null) return reply;
        var selected = assembly.versions().keySet();
        Set<String> outputFiles = new HashSet<>(Arrays.asList(tag(reply, "mods").split(",")));
        if (!selected.equals(outputFiles)) return reply + "\n部分文件缺少选版事实，暂未执行内容重叠确认。";
        var protectedSlugs = new HashSet<String>();
        assembly.origins().forEach((slug, roles) -> {
            if (!Collections.disjoint(roles, Set.of("CORE", "EXPLICIT_ADD", "REQUIRED_INTEGRATION", "IMPORTED"))) protectedSlugs.add(slug);
        });
        // 必要前置不可单独取消，避免其它保留根重新将它补入而违背选择。
        closure.requirements().stream().filter(r -> selected.contains(r.dependent()) && selected.contains(r.dependency()))
                .map(DependencyClosure.Requirement::dependency).forEach(protectedSlugs::add);
        var projects = new ArrayList<ContentOverlap.Project>();
        var categories = new TreeMap<String, List<String>>();
        int unknown = 0;
        Map<String, JsonNode> facts;
        try { facts = api.getProjectsBatch(selected); }
        catch (ModrinthApiClient.PartialProjectBatchException partial) { facts = partial.projects(); }
        catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
        catch (RuntimeException unavailable) { facts = Map.of(); }
        if (aborted.getAsBoolean()) return "⛔ 思考已手动终止。";
        for (String slug : selected) {
            if (aborted.getAsBoolean()) return "⛔ 思考已手动终止。";
            try {
                var info = facts.get(slug);
                if (info == null) { unknown++; continue; }
                var tags = new LinkedHashSet<String>();
                for (var tag : info.path("categories")) if (tag.isTextual() && CategoryRegistry.CATEGORY_SET.contains(tag.asText())) tags.add(tag.asText());
                categories.put(slug, List.copyOf(tags));
                if (tags.contains("library")) continue;
                String icon = info.path("icon_url").asText();
                if (!icon.startsWith("https://cdn.modrinth.com/") || icon.length() > 1000) icon = "";
                if (info.path("description").asText().isBlank() && info.path("body").asText().isBlank()) { unknown++; continue; }
                projects.add(new ContentOverlap.Project(slug, bounded(info.path("title").asText(slug), 200),
                        bounded(info.path("description").asText(), 600), bounded(info.path("body").asText(), 16000), icon,
                        info.path("downloads").asLong()));
            } catch (RuntimeException unavailable) { unknown++; }
        }
        var groups = ContentOverlap.detectByDimension(projects, protectedSlugs, confirmedGeneration).stream().filter(group -> acknowledgements.getIfPresent(
                fingerprint(owner, conversation, payload.getOrDefault("uuid", ""), assembly, group, Set.of())) == null).toList();
        var updatedTrace = (ObjectNode) JSON.readTree(tag(reply, "trace"));
        updatedTrace.set("upstream", JSON.readTree(yagen.waitmydawn.maa.runtime.RequestScope.toTraceJson()));
        updatedTrace.set("contentOverlapAudit", JSON.valueToTree(Map.of("groups", groups.size(), "unverifiedDescriptions", unknown)));
        reply = replaceTag(reply, "trace", MessageJson.write(updatedTrace));
        if (groups.isEmpty()) return reply;
        var value = new Pending(owner, conversation, snapshot(payload), reply, assembly, closure, groups, Map.copyOf(categories));
        String ticket = put(value);
        return "发现需要确认的内容组合，请查看介绍依据后选择保留的项目。当前清单尚未修改，等待不会占用构建名额。"
                + "\n<replacement_options>" + MessageJson.write(Map.of("kind", "OVERLAP", "ticket", ticket, "groups", groups,
                    "loader", assembly.loader(), "mcVersion", assembly.mcVersion())) + "</replacement_options>"
                + "\n<trace>" + MessageJson.write(Map.of("branch", "AWAITING_INPUT", "awaitingInput", true,
                    "overlapGroups", groups.size(), "unverifiedDescriptions", unknown)) + "</trace>";
    }
    private synchronized String put(Pending value) {
        pending.cleanUp();
        if (weight(value) > 16 * 1024 * 1024) throw new IllegalArgumentException("确认草案超过容量，请减少单次构建规模");
        pending.asMap().entrySet().removeIf(e -> Objects.equals(value.owner(), e.getValue().owner()));
        if (pending.asMap().size() >= 256) throw new IllegalArgumentException("待确认草案已满，请稍后重新构建");
        String ticket = UUID.randomUUID().toString(); pending.put(ticket, value); return ticket;
    }
    public synchronized boolean cancel(String ticket, Long owner) {
        var value = pending.getIfPresent(ticket);
        return value != null && Objects.equals(value.owner(), owner) && pending.asMap().remove(ticket, value);
    }
    /** 成功入队才消费票据；校验失败、账号冲突与队列拒绝均保留原选择。 */
    public synchronized <T> T admit(String ticket, Long owner, Long conversation, Map<String, String> payload,
                                     java.util.function.Function<Confirmed, T> submit) {
        var value = pending.getIfPresent(ticket);
        if (value == null || !Objects.equals(owner, value.owner()) || !Objects.equals(conversation, value.conversation())
                || !snapshot(payload).equals(value.snapshot())) throw new IllegalArgumentException("组合确认已过期或工作区已变化，请重新构建");
        Set<String> rejected = choices(value, payload.getOrDefault("replacementChoices", ""));
        String result = finish(value, rejected);
        String uuid = payload.getOrDefault("uuid", "");
        T accepted = submit.apply(new Confirmed(result, () -> {
            for (var group : value.groups()) acknowledgements.put(fingerprint(owner, conversation,
                    uuid, value.assembly(), group, rejected), true);
        }));
        pending.asMap().remove(ticket, value);
        return accepted;
    }
    private static Set<String> choices(Pending value, String raw) {
        if (raw.length() > 512000) throw new IllegalArgumentException("组合选择超过输入限额");
        JsonNode choices;
        try { choices = JSON.readTree(raw); } catch (RuntimeException invalid) { throw new IllegalArgumentException("组合选择格式无效"); }
        if (choices == null || !choices.isObject() || choices.size() != value.groups().size()) throw new IllegalArgumentException("请处理每个组合分组");
        var states = new HashMap<String, Boolean>();
        for (var group : value.groups()) {
            var choice = choices.path(group.original());
            if (!choice.path("slugs").isArray() || !choice.path("drop").isBoolean() || choice.path("drop").asBoolean()
                    || !choice.path("manualSlug").isTextual() || !choice.path("manualSlug").asText().isEmpty())
                throw new IllegalArgumentException("组合确认只能选择本组项目");
            var members = group.candidates().stream().map(ContentOverlap.Candidate::slug).collect(java.util.stream.Collectors.toSet());
            var selected = new HashSet<String>();
            for (var slug : choice.path("slugs")) if (!slug.isTextual() || !members.contains(slug.asText()) || !selected.add(slug.asText()))
                throw new IllegalArgumentException("组合选择包含未知或重复项目");
            if (selected.isEmpty()) throw new IllegalArgumentException("每组至少保留一个项目");
            for (var candidate : group.candidates()) {
                boolean kept = selected.contains(candidate.slug());
                if (candidate.protectedSelection() && !kept) throw new IllegalArgumentException("点名核心、锁定版本及必要前置不能在组合确认中取消");
                Boolean prior = states.putIfAbsent(candidate.slug(), kept);
                if (prior != null && prior != kept) throw new IllegalArgumentException("同一项目在不同分组的选择不一致");
            }
        }
        return states.entrySet().stream().filter(e -> !e.getValue()).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toSet());
    }
    private static String finish(Pending value, Set<String> rejected) {
        if (rejected.isEmpty()) {
            var acceptedTrace = (ObjectNode) JSON.readTree(tag(value.reply(), "trace"));
            acceptedTrace.set("overlapSelection", JSON.valueToTree(Map.of("rejected", List.of(), "groups", decisionEvidence(value, rejected))));
            return replaceTag(value.reply(), "trace", MessageJson.write(acceptedTrace)) + "\n已确认保留所选内容组合。";
        }
        var roots = new LinkedHashSet<>(value.assembly().roots()); roots.removeAll(rejected);
        Set<String> selected = value.assembly().rootsKnown() ? reachable(roots, value.closure()) : new LinkedHashSet<>(value.assembly().versions().keySet());
        selected.removeAll(rejected); selected.retainAll(value.assembly().versions().keySet());
        var removed = new TreeSet<>(value.assembly().versions().keySet()); removed.removeAll(selected);
        var versions = new TreeMap<>(value.assembly().versions()); versions.keySet().retainAll(selected);
        var origins = new TreeMap<>(value.assembly().origins()); origins.keySet().retainAll(selected);
        var assembly = new AssemblySnapshot(value.assembly().loader(), value.assembly().mcVersion(), List.copyOf(roots), versions, origins, value.assembly().rootsKnown());
        var trace = (ObjectNode) JSON.readTree(tag(value.reply(), "trace"));
        var report = (ObjectNode) JSON.readTree(tag(value.reply(), "build_report"));
        var coverage = JSON.readTree(tag(value.reply(), "requirement_report"));
        var invalidatedEvidence = new HashSet<>(removed);
        for (var addon : trace.path("addonEvidence")) for (var evidence : addon.path("finalEvidence"))
            for (var partner : evidence.path("partners")) if (removed.contains(partner.asText())) invalidatedEvidence.add(addon.path("slug").asText());
        adjustCoverage(coverage, removed, invalidatedEvidence);
        var categoryCounts = new TreeMap<String, Integer>();
        selected.forEach(slug -> value.categories().getOrDefault(slug, List.of()).forEach(cat -> categoryCounts.merge(cat, 1, Integer::sum)));
        int unclassified = (int) selected.stream().filter(s -> value.categories().getOrDefault(s, List.of()).isEmpty()).count();
        if (unclassified > 0) categoryCounts.put("未分类", unclassified);
        report.put("total", selected.size()).set("categories", JSON.valueToTree(categoryCounts));
        if (report.path("optimization").isObject()) {
            var portfolio = JSON.treeToValue(report.path("optimization"), yagen.waitmydawn.maa.model.OptimizationPortfolio.Report.class);
            report.set("optimization", JSON.valueToTree(yagen.waitmydawn.maa.model.OptimizationPortfolio.retain(portfolio, selected, roots)));
        }
        var issues = JSON.createArrayNode();
        for (var issue : report.path("issues")) if (issue.path("dropped").asBoolean() || selected.contains(issue.path("mod").asText())) issues.add(issue);
        report.set("issues", issues);
        var closure = value.closure().inspect(selected);
        trace.set("assembly", JSON.valueToTree(assembly)); trace.set("requirementCoverage", coverage);
        if (trace.path("resolution") instanceof ObjectNode resolution) {
            resolution.put("status", closure.status() == DependencyClosure.Status.COMPLETE ? "COMPLETE" : "INCOMPLETE"); resolution.put("unresolved", closure.issues().size());
        }
        trace.set("overlapSelection", JSON.valueToTree(Map.of("rejected", rejected, "removedIncludingOrphans", removed, "staticClosure", closure, "groups", decisionEvidence(value, rejected))));
        trace.set("quantity", JSON.valueToTree(Map.of("status", "USER_SELECTION", "totalFiles", selected.size(), "roots", roots.size(),
                "dependencies", Math.max(0, selected.size() - roots.size()), "trimmed", removed, "rootsKnown", assembly.rootsKnown())));
        for (var row : trace.path("addonEvidence")) if (row instanceof ObjectNode object) {
            boolean retained = selected.contains(row.path("slug").asText()); object.put("retained", retained);
            if (invalidatedEvidence.contains(row.path("slug").asText())) {
                object.set("draftFinalEvidence", row.path("finalEvidence").deepCopy());
                object.set("finalEvidence", JSON.createArrayNode());
                object.put("finalEvidenceStatus", retained ? "REQUIRES_RECHECK_AFTER_SELECTION" : "NOT_RETAINED");
                object.put("finalVersionVerified", retained && row.path("finalVersionVerified").asBoolean());
            }
        }
        for (var row : trace.path("goalEvidence")) if (row instanceof ObjectNode object) {
            var kept = JSON.createArrayNode(); for (var slug : row.path("selected")) if (selected.contains(slug.asText())) kept.add(slug);
            object.set("selected", kept); object.put("status", kept.isEmpty() ? "NO_RETAINED_RETRIEVAL_HINT" : "RETAINED_RETRIEVAL_HINT");
            if (row.path("selectedProjectFacts").isArray()) {
                var facts = JSON.createArrayNode();
                for (var fact : row.path("selectedProjectFacts")) if (selected.contains(fact.path("slug").asText())) facts.add(fact);
                object.set("selectedProjectFacts", facts);
            }
        }
        var codec = new AgentContractCodec();
        var old = codec.readConstraints(tag(value.reply(), "constraints"));
        var exclusions = new TreeSet<>(old.exclude()); exclusions.addAll(rejected);
        var constraints = new BuildConstraints(old.schemaVersion(), old.mustHave(), List.copyOf(exclusions), old.excludedCategories(), old.count(),
                old.replacementPolicy(), old.maxDownloads(), old.requirements());
        if (trace.path("contract").path("quantity") instanceof ObjectNode quantity) {
            String scope = quantity.path("scope").asText();
            if (scope.equals("ADDED_ROOTS") || (!assembly.rootsKnown() && !scope.equals("TOTAL_FILES"))) {
                quantity.putNull("actual"); quantity.putNull("met");
            } else {
                int actual = scope.equals("TOTAL_FILES") ? selected.size() : roots.size();
                quantity.put("actual", actual);
                if (old.count() != null && !quantity.path("met").isNull()) quantity.put("met", old.count().accepts(actual));
            }
        }
        return "已按你的选择保留 " + selected.size() + " 个模组文件；已移除未选项目，根来源核实后的孤立前置一并清理。"
                + "\n" + optionalTag(value.reply(), "replacement_choices") + optionalTag(value.reply(), "ops_summary") + optionalTag(value.reply(), "name") + "\n<mc>" + assembly.mcVersion() + "</mc><loader>" + assembly.loader() + "</loader>"
                + "\n<build_report>" + MessageJson.write(report) + "</build_report>"
                + (coverage.isMissingNode() ? "" : "\n<requirement_report>" + MessageJson.write(coverage) + "</requirement_report>")
                + "\n<trace>" + MessageJson.write(trace) + "</trace>\n<mods>" + String.join(",", new TreeSet<>(selected)) + "</mods>"
                + "\n<constraints>" + codec.write(constraints) + "</constraints>";
    }
    private static List<Map<String, Object>> decisionEvidence(Pending value, Set<String> rejected) {
        return value.groups().stream().map(group -> Map.<String, Object>of("id", group.original(), "explanation", group.explanation(),
                "members", group.candidates().stream().map(c -> Map.of("slug", c.slug(), "quote", c.evidence(), "scope", c.scope(), "scopeQuote", c.scopeEvidence(),
                        "scopeNote", c.scopeNote(), "selected", !rejected.contains(c.slug()))).toList())).toList();
    }
    private static String fingerprint(Long owner, Long conversation, String uuid, AssemblySnapshot assembly,
                                      ContentOverlap.Group group, Set<String> rejected) {
        var members = group.candidates().stream().filter(c -> !rejected.contains(c.slug())).sorted(Comparator.comparing(ContentOverlap.Candidate::slug)).map(c -> Map.of("slug", c.slug(),
                "version", assembly.versions().get(c.slug()), "quote", c.evidence(), "scopeQuote", c.scopeEvidence())).toList();
        String raw = MessageJson.write(Map.of("owner", owner, "conversation", conversation == null ? "" : conversation.toString(),
                "uuid", uuid, "group", group.original(), "members", members));
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Set<String> reachable(Set<String> roots, DependencyClosure closure) {
        var result = new LinkedHashSet<>(roots); var queue = new ArrayDeque<>(roots);
        var byParent = new HashMap<String, Set<String>>();
        closure.requirements().forEach(r -> byParent.computeIfAbsent(r.dependent(), ignored -> new HashSet<>()).add(r.dependency()));
        while (!queue.isEmpty()) for (String dep : byParent.getOrDefault(queue.removeFirst(), Set.of())) if (result.add(dep)) queue.addLast(dep);
        return result;
    }
    private static boolean adjustCoverage(JsonNode node, Set<String> removed, Set<String> invalidatedRelations) {
        if (node == null || node.isMissingNode()) return false;
        boolean childChanged = false;
        if (node.path("requirements").isArray()) for (var row : node.path("requirements")) childChanged |= adjustCoverage(row, removed, invalidatedRelations);
        if (node.path("criteria").isArray()) for (var row : node.path("criteria")) childChanged |= adjustCoverage(row, removed, invalidatedRelations);
        if (!(node instanceof ObjectNode row) || !row.path("mods").isArray()) return childChanged;
        var kept = JSON.createArrayNode(); var missing = new LinkedHashSet<String>();
        row.path("missing").forEach(n -> missing.add(n.asText()));
        int previousMissingCount = row.path("missingCount").asInt();
        boolean missingTruncated = previousMissingCount > missing.size();
        int addedMissing = 0;
        boolean changed = false;
        boolean relationChanged = false;
        boolean truncated = row.path("modCount").asInt() > row.path("mods").size();
        for (var item : row.path("mods")) {
            String slug = item.path("slug").asText();
            String type = item.path("evidenceType").asText();
            boolean removedPartner = false;
            for (var partner : item.path("partners")) removedPartner |= removed.contains(partner.asText());
            boolean relationInvalid = (invalidatedRelations.contains(slug) || removedPartner) && (type.equals("VERSION_RELATION") || type.equals("DESCRIPTION_RELATION")
                    || (type.isEmpty() && item.path("quote").asText().isBlank()));
            if (!removed.contains(slug) && !relationInvalid) { kept.add(item); continue; }
            changed = true;
            relationChanged |= !removed.contains(slug) && relationInvalid;
            if (removed.contains(slug) && type.equals("PROJECT_IDENTITY") && missing.add(slug)) addedMissing++;
        }
        boolean generationChanged = false;
        for (var realm : row.path("evidenceState").path("generation"))
            for (var project : realm.path("projects")) generationChanged |= removed.contains(project.asText());
        if (!changed && !childChanged && !generationChanged && !(truncated && !invalidatedRelations.isEmpty())) return false;
        row.set("mods", kept); row.put("modCount", kept.size());
        row.set("missing", JSON.valueToTree(missing)); row.put("missingCount", Math.max(previousMissingCount + addedMissing, missing.size()));
        boolean pendingChild = false;
        for (var child : row.path("criteria"))
            if (Set.of("PARTIAL", "UNVERIFIED", "NOT_RETAINED").contains(child.path("status").asText())) pendingChild = true;
        boolean supporting = false;
        for (var item : kept) if (!item.path("quote").asText().isBlank()
                || item.path("evidenceType").asText().equals("VERSION_RELATION")) supporting = true;
        String previous = row.path("status").asText();
        var state = row.path("evidenceState").isObject() ? ((ObjectNode)row.path("evidenceState")).deepCopy() : JSON.createObjectNode();
        if (!state.has("projects")) state.set("projects", JSON.createArrayNode());
        boolean complete = !truncated && !missingTruncated
                && state.path("countsComplete").asBoolean(true);
        for (var child : row.path("criteria")) complete &= child.path("evidenceState").path("countsComplete").asBoolean(true);
        state.put("countsComplete", complete);
        boolean generationMissing = false;
        var generation = JSON.createArrayNode();
        for (var realm : state.path("generation")) {
            var item = (ObjectNode) realm.deepCopy();
            var projects = JSON.createArrayNode();
            for (var project : realm.path("projects")) if (!removed.contains(project.asText())) projects.add(project.asText());
            item.set("projects", projects); generation.add(item);
            generationMissing |= projects.isEmpty();
        }
        if (state.has("generation")) state.set("generation", generation);
        if (row.path("missingCount").asInt() > 0) {
            row.put("status", kept.isEmpty() ? "NOT_RETAINED" : "PARTIAL");
            state.put("code", "PROJECT_MISSING").set("projects", JSON.valueToTree(missing));
            row.put("note", "组合选择后有点名项目未保留");
        } else if (generationMissing) {
            row.put("status", kept.isEmpty() ? "UNVERIFIED" : "PARTIAL");
            state.put("code", "CORE_GENERATION_PENDING");
            row.put("note", "组合选择后核心世界生成仍待核实；保留局部结构或生物不能替代该依据");
        } else if (previous.equals("SUPPORTING_EVIDENCE") && supporting && !pendingChild) {
            state.put("code", "VERIFIED");
            row.put("note", "组合选择后仍保留有效用途或关系依据");
        } else if ((changed || pendingChild || (truncated && previous.equals("VERIFIED_PROJECTS")))
                && !(state.path("code").asText().equals("REVIEW_GAP") && supporting && !pendingChild)) {
            if (!previous.equals("CONTEXT")) row.put("status", kept.isEmpty() ? "UNVERIFIED" : "PARTIAL");
            state.put("code", truncated ? "DISPLAY_TRUNCATED" : relationChanged ? "RELATION_PENDING" : "EVIDENCE_REMOVED");
            row.put("note", relationChanged ? "组合选择后联动对象发生变化，关系仍待核实" : "组合选择后部分关联已移除，当前用途待核实");
        }
        row.set("evidenceState", state);
        return true;
    }
    private static String replaceTag(String reply, String name, String content) {
        return reply.replaceFirst("(?s)<" + name + ">.*?</" + name + ">", Matcher.quoteReplacement("<" + name + ">" + content + "</" + name + ">"));
    }
    private static String tag(String reply, String name) {
        var match = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(reply);
        return match.find() ? match.group(1) : "{}";
    }
    private static String optionalTag(String reply, String name) {
        var match = Pattern.compile("<" + name + ">.*?</" + name + ">", Pattern.DOTALL).matcher(reply);
        return match.find() ? match.group() : "";
    }
    private static String bounded(String value, int max) { return value.substring(0, Math.min(value.length(), max)); }
}
