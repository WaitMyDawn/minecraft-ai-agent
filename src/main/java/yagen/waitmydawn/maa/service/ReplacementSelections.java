package yagen.waitmydawn.maa.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 选择票据保存已验证的计划，绑定账号与工作区快照，续建不再调用规划师重写需求。 */
public final class ReplacementSelections {
    public record Pending(Long owner, String snapshot, BuildIntent intent, BuildConstraints constraints,
                          AiAgentService.AgentCallResult architect, List<AgentContractCodec.Failure> failures,
                          List<RequiredModAlternatives.Group> groups) {}
    public record Selection(List<String> slugs, boolean drop, String manualSlug) {}
    public record Resumed(Pending pending, BuildIntent intent, BuildConstraints constraints,
                          Map<String, List<String>> replacements) {}
    private final Cache<String, Pending> pending;
    private static final ObjectMapper JSON = new ObjectMapper();

    public ReplacementSelections() { this(com.github.benmanes.caffeine.cache.Ticker.systemTicker()); }
    ReplacementSelections(com.github.benmanes.caffeine.cache.Ticker ticker) {
        pending = Caffeine.newBuilder().ticker(ticker).expireAfterWrite(java.time.Duration.ofMinutes(15)).build();
    }

    public synchronized String put(Pending value) {
        pending.cleanUp();
        pending.asMap().entrySet().removeIf(entry -> Objects.equals(entry.getValue().owner(), value.owner()));
        if (pending.asMap().size() >= 256) throw new IllegalArgumentException("待确认计划已满，请稍后重新提交需求");
        String ticket = UUID.randomUUID().toString();
        pending.put(ticket, value);
        return ticket;
    }
    /** 入队前消费票据；不可变计划随任务持有，不再受等待票据的过期影响。 */
    public synchronized Resumed resume(String ticket, Long owner, Map<String, String> payload) {
        Pending value = pending.getIfPresent(ticket);
        if (value == null || !Objects.equals(owner, value.owner()) || !snapshot(payload).equals(value.snapshot()))
            throw new IllegalArgumentException("候选选择已过期或整合包状态已变化，请重新提交需求");
        JsonNode choices;
        try { choices = JSON.readTree(payload.getOrDefault("replacementChoices", "")); }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("候选选择格式无效"); }
        if (!choices.isObject() || choices.size() != value.groups().size()) throw new IllegalArgumentException("请处理所有待替换模组");
        Map<String, List<String>> replacements = new LinkedHashMap<>();
        for (var group : value.groups()) {
            JsonNode choice = choices.path(group.original());
            if (!choice.isObject() || !choice.path("slugs").isArray() || !choice.path("drop").isBoolean()
                    || !choice.path("manualSlug").isTextual()) throw new IllegalArgumentException("候选选择格式无效");
            var slugs = new LinkedHashSet<String>();
            var allowed = group.candidates().stream().map(RequiredModAlternatives.Candidate::slug).toList();
            for (JsonNode slug : choice.path("slugs")) {
                if (!slug.isTextual() || !allowed.contains(slug.asText()) || !slugs.add(slug.asText()))
                    throw new IllegalArgumentException("选择包含未提供的候选");
            }
            String manual = choice.path("manualSlug").asText().trim();
            boolean drop = choice.path("drop").asBoolean();
            if (drop && (!slugs.isEmpty() || !manual.isEmpty()) || !manual.isEmpty() && !slugs.isEmpty())
                throw new IllegalArgumentException("请选择候选、补充 slug 或去除要求中的一种");
            if (!manual.isEmpty()) {
                if (!ModrinthIdentifiers.isProjectReference(manual)) throw new IllegalArgumentException("补充的 slug 格式无效");
                slugs.add(manual);
            }
            if (!drop && slugs.isEmpty()) throw new IllegalArgumentException("至少选择一个候选，或明确去除要求");
            if (slugs.stream().anyMatch(value.intent().exclude()::contains)) throw new IllegalArgumentException("选择与已有排除项冲突");
            replacements.put(group.original(), List.copyOf(slugs));
        }
        var i = value.intent();
        BuildIntent resolved = new BuildIntent(i.schemaVersion(), i.mode(), i.reply(), i.name(), i.environment(), i.count(),
                replace(i.mustHave(), replacements), replace(i.add(), replacements), i.remove(), i.exclude(),
                replace(i.expandAddons(), replacements), i.replacementPolicy(), i.searchIntents(), i.excludedCategories(), i.maxDownloads(), i.constraintChanges(),
                remapRequirements(i.requirements(), replacements));
        var c = value.constraints();
        BuildConstraints constraints = new BuildConstraints(c.schemaVersion(), replace(c.mustHave(), replacements), c.exclude(),
                c.excludedCategories(), c.count(), c.replacementPolicy(), c.maxDownloads(),
                remapRequirements(c.requirements(), replacements));
        if (!pending.asMap().remove(ticket, value)) throw new IllegalArgumentException("该选择已提交，请等待构建结果");
        return new Resumed(value, resolved, constraints, Map.copyOf(replacements));
    }
    private static List<BuildRequirement> remapRequirements(
            List<BuildRequirement> entries, Map<String, List<String>> replacements) {
        return entries.stream().filter(r -> !(r.kind() == BuildRequirement.Kind.PROJECTS
                        && !r.projects().isEmpty() && r.projects().stream().allMatch(p -> replacements.getOrDefault(p, List.of(p)).isEmpty())))
                .filter(r -> r.addonCores().isEmpty() || r.addonCores().stream()
                        .anyMatch(p -> !replacements.getOrDefault(p, List.of(p)).isEmpty()))
                .map(r -> r.replace(replacements)).toList();
    }
    private synchronized boolean restore(String ticket, Pending value) {
        pending.cleanUp();
        if (pending.asMap().size() >= 256 || pending.asMap().values().stream().anyMatch(p -> Objects.equals(p.owner(), value.owner())))
            return false;
        pending.put(ticket, value);
        return true;
    }
    /** 提交与失败回滚在同一临界区，队列拒绝期间不能被新票据抢占容量。 */
    public synchronized <T> T admit(String ticket, Long owner, Map<String, String> payload,
                                     java.util.function.Function<Resumed, T> submit) {
        Resumed value = resume(ticket, owner, payload);
        try { return submit.apply(value); }
        catch (RuntimeException rejected) {
            restore(ticket, value.pending());
            throw rejected;
        }
    }
    public synchronized boolean cancel(String ticket, Long owner) {
        Pending value = pending.getIfPresent(ticket);
        return value != null && Objects.equals(owner, value.owner()) && pending.asMap().remove(ticket, value);
    }
    private static List<String> replace(List<String> source, Map<String, List<String>> replacements) {
        var result = new LinkedHashSet<String>();
        source.forEach(slug -> result.addAll(replacements.getOrDefault(slug, List.of(slug))));
        return List.copyOf(result);
    }
    public static String snapshot(Map<String, String> payload) {
        // 仅摘要构建输入，不保存请求中的鉴权信息或个人密钥。
        var fields = new TreeMap<String, String>();
        for (String name : List.of("prompt", "currentMods", "importedPack", "uuid", "mcVersion", "loader", "excludedSlugs", "constraints"))
            fields.put(name, payload.getOrDefault(name, ""));
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(JSON.writeValueAsString(fields).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
