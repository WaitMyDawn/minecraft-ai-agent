package yagen.waitmydawn.maa.eval;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** 严格检查声明的 JSON Pointer 断言，缺字段不能按零值或空集合通过。 */
final class GoldenAssertions {
    private static final Set<String> OPS = Set.of("equals", "contains", "absent", "min", "max");
    private GoldenAssertions() {}
    static void validateCoverage(JsonNode rule) {
        if (!rule.isObject() || rule.isEmpty()) throw new IllegalArgumentException("coverage 必须是非空对象");
        rule.properties().forEach(e -> {
            if (!Set.of("sourcesContain", "minSupporting", "maxRepairAdds", "noMissingNamed", "retainedEvidence").contains(e.getKey()))
                throw new IllegalArgumentException("未知 coverage 断言: " + e.getKey());
            if (e.getKey().equals("sourcesContain") && (!e.getValue().isArray() || e.getValue().isEmpty()
                    || java.util.stream.StreamSupport.stream(e.getValue().spliterator(), false).anyMatch(v -> !v.isTextual() || v.asText().isBlank())))
                throw new IllegalArgumentException("sourcesContain 必须是非空文本数组");
            if (Set.of("minSupporting", "maxRepairAdds").contains(e.getKey()) && (!e.getValue().isIntegralNumber() || e.getValue().asInt() < 0))
                throw new IllegalArgumentException("coverage 数量必须是非负整数");
            if (Set.of("noMissingNamed", "retainedEvidence").contains(e.getKey()) && !e.getValue().isBoolean())
                throw new IllegalArgumentException("coverage 开关必须是布尔值");
        });
    }
    static List<String> checkCoverage(JsonNode facts, Set<String> finalMods, JsonNode rule) {
        validateCoverage(rule);
        var failures = new ArrayList<String>();
        var report = facts.path("requirement_report");
        if (report.path("schemaVersion").asInt() != 1 || !report.path("requirements").isArray())
            return List.of("缺少有效最终需求报告");
        var rows = report.path("requirements"); var ids = new HashSet<String>(); int supporting = 0;
        for (var row : rows) {
            if (!ids.add(row.path("id").asText())) failures.add("需求报告重复 ID");
            if (Set.of("VERIFIED_PROJECTS", "SUPPORTING_EVIDENCE").contains(row.path("status").asText())) supporting++;
            if (rule.path("noMissingNamed").asBoolean(false)
                    && (!row.path("missing").isArray() || !row.path("missing").isEmpty() || row.path("missingCount").asInt() > 0))
                failures.add("最终需求报告存在未保留点名项目");
            if (rule.path("retainedEvidence").asBoolean(false)) {
                if (!row.path("mods").isArray()) failures.add("需求入包依据不是数组");
                for (var mod : row.path("mods")) {
                    String slug = mod.path("slug").asText();
                    if (!finalMods.contains(slug) || !facts.at("/trace/assembly/versions").has(slug))
                        failures.add("用途依据未在最终选版中留存: " + slug);
                }
            }
            if (row.path("criteria").isArray()) {
                var childIds = new HashSet<String>(); var sources = new HashSet<String>();
                for (var child : row.path("criteria")) {
                    if (!childIds.add(child.path("id").asText()) || !sources.add(child.path("source").asText()))
                        failures.add("子目标报告重复 ID 或原文");
                    if (Set.of("VERIFIED_PROJECTS", "SUPPORTING_EVIDENCE").contains(row.path("status").asText())
                            && Set.of("PARTIAL", "UNVERIFIED", "NOT_RETAINED").contains(child.path("status").asText()))
                        failures.add("父项状态掩盖未覆盖子目标");
                    if (rule.path("retainedEvidence").asBoolean(false)) for (var mod : child.path("mods")) {
                        String slug = mod.path("slug").asText();
                        if (!finalMods.contains(slug) || !facts.at("/trace/assembly/versions").has(slug))
                            failures.add("子目标用途依据未在最终选版中留存: " + slug);
                    }
                    if (rule.path("noMissingNamed").asBoolean(false) && (!child.path("missing").isArray() || !child.path("missing").isEmpty()
                            || child.path("missingCount").asInt() > 0))
                        failures.add("子目标存在未保留点名项目");
                }
            }
        }
        var ledger = facts.at("/trace/contract/requirementLedger");
        if (!ledger.isArray()) failures.add("缺少权威原始需求账本");
        else for (var requirement : ledger) if (!requirement.path("kind").asText().equals("CHANGE")) {
            if (!ids.contains(requirement.path("id").asText())) failures.add("最终报告遗漏原始需求: " + requirement.path("id").asText());
            for (var planned : requirement.path("criteria")) if (!planned.path("kind").asText().equals("CHANGE")) {
                boolean found = false;
                for (var row : rows) if (row.path("id").asText().equals(requirement.path("id").asText()))
                    for (var child : row.path("criteria")) if (child.path("source").asText().equals(planned.path("source").asText())) found = true;
                if (!found) failures.add("最终报告遗漏子目标: " + planned.path("source").asText());
            }
        }
        for (var source : rule.path("sourcesContain")) {
            boolean found = false;
            for (var row : rows) if (row.path("source").asText().contains(source.asText())) found = true;
            if (!found) failures.add("需求报告缺少原文: " + source.asText());
        }
        if (rule.has("minSupporting") && supporting < rule.path("minSupporting").asInt()) failures.add("有依据的要求数量不足: " + supporting);
        if (rule.has("maxRepairAdds") && (!report.path("repaired").isArray() || report.path("repaired").size() > rule.path("maxRepairAdds").asInt()))
            failures.add("补缺数量缺失或超过上限");
        return failures;
    }

    static void validateOptimization(JsonNode rule) {
        if (!rule.isObject() || rule.isEmpty()) throw new IllegalArgumentException("optimization 必须是非空对象");
        rule.properties().forEach(e -> {
            switch (e.getKey()) {
                case "minMods", "maxRenderers" -> { if (!e.getValue().isIntegralNumber() || e.getValue().asInt() < 0) throw new IllegalArgumentException("优化数量必须非负整数"); }
                case "referenceActive", "retainedEvidence" -> { if (!e.getValue().isBoolean()) throw new IllegalArgumentException("优化开关须为布尔值"); }
                case "side" -> { if (!Set.of("CLIENT", "SERVER", "BOTH").contains(e.getValue().asText())) throw new IllegalArgumentException("未知优化侧别"); }
                case "rolesContain" -> {
                    if (!e.getValue().isArray() || e.getValue().isEmpty()) throw new IllegalArgumentException("优化用途须为数组");
                    e.getValue().forEach(role -> { try { yagen.waitmydawn.maa.model.OptimizationPortfolio.Role.valueOf(role.asText()); }
                        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("未知优化用途"); } });
                }
                default -> throw new IllegalArgumentException("未知优化断言");
            }
        });
    }
    static List<String> checkOptimization(JsonNode facts, Set<String> selected, JsonNode rule) {
        validateOptimization(rule); var failures = new ArrayList<String>();
        var report = facts.at("/build_report/optimization");
        if (report.path("schemaVersion").asInt() != 1 || !report.path("entries").isArray() || !report.path("targets").isArray()
                || !report.path("modCount").isIntegralNumber()) return List.of("缺少有效优化组合报告");
        var counted = new HashSet<String>(); int renderers = 0;
        for (var entry : report.path("entries")) {
            String slug = entry.path("slug").asText();
            if (slug.isBlank() || !counted.add(slug) || !entry.path("roles").isObject() || entry.path("roles").isEmpty()) failures.add("优化项身份或用途无效");
            if (entry.path("roles").has("RENDERER")) renderers++;
            if (rule.path("retainedEvidence").asBoolean(false) && (!selected.contains(slug)
                    || entry.path("versionId").asText().isBlank() || !facts.at("/trace/assembly/versions").path(slug).asText().equals(entry.path("versionId").asText())))
                failures.add("优化用途依据未在最终选版保留: " + slug);
        }
        if (counted.size() != report.path("modCount").asInt()) failures.add("优化项计数不一致");
        if (rule.has("minMods") && counted.size() < rule.path("minMods").asInt()) failures.add("优化根数量不足");
        if (rule.has("maxRenderers") && renderers > rule.path("maxRenderers").asInt()) failures.add("可选渲染器数量超标");
        if (rule.has("referenceActive") && (!report.path("referenceActive").isBoolean() || rule.path("referenceActive").asBoolean() != report.path("referenceActive").asBoolean())) failures.add("软参考适用范围不符");
        var covered = new HashSet<String>(); boolean targetFound = false;
        for (var target : report.path("targets")) if (!rule.has("side") || rule.path("side").asText().equals(target.path("side").asText())) {
            targetFound = true; target.path("covered").forEach(role -> covered.add(role.asText()));
        }
        if (!targetFound) failures.add("缺少目标侧别的优化要求");
        for (var role : rule.path("rolesContain")) if (!covered.contains(role.asText())) failures.add("目标侧别尚无优化用途依据: " + role.asText());
        return failures;
    }

    static void validate(JsonNode assertions) {
        if (!assertions.isObject()) throw new IllegalArgumentException("facts 必须是对象");
        assertions.properties().forEach(entry -> {
            if (!entry.getKey().startsWith("/")) throw new IllegalArgumentException("facts 必须使用 JSON Pointer");
            JsonNode rule = entry.getValue();
            if (!rule.isObject() || rule.isEmpty()) throw new IllegalArgumentException("空断言: " + entry.getKey());
            rule.properties().forEach(op -> {
                if (!OPS.contains(op.getKey())) throw new IllegalArgumentException("未知断言: " + op.getKey());
                if (Set.of("contains", "absent").contains(op.getKey()) && !op.getValue().isArray())
                    throw new IllegalArgumentException("集合断言必须是数组");
                if (Set.of("min", "max").contains(op.getKey()) && !op.getValue().isNumber())
                    throw new IllegalArgumentException("范围断言必须是数字");
            });
        });
    }

    static List<String> check(JsonNode actual, JsonNode assertions) {
        validate(assertions);
        var failures = new ArrayList<String>();
        assertions.properties().forEach(entry -> {
            String pointer = entry.getKey();
            JsonNode value = actual.at(pointer), rule = entry.getValue();
            if (value.isMissingNode()) { failures.add("缺少字段 " + pointer); return; }
            if (rule.has("equals") && !rule.get("equals").equals(value)) failures.add(pointer + " 值不符: " + value);
            for (String op : List.of("contains", "absent")) if (rule.has(op)) {
                if (!value.isArray()) { failures.add(pointer + " 不是数组"); continue; }
                for (JsonNode expected : rule.get(op)) {
                    boolean found = false;
                    for (JsonNode item : value) if (item.equals(expected)) found = true;
                    if (found != op.equals("contains")) failures.add(pointer + " " + op + " 不满足: " + expected);
                }
            }
            for (String op : List.of("min", "max")) if (rule.has(op)) {
                if (!value.isNumber()) failures.add(pointer + " 不是数字");
                else if (op.equals("min") ? value.asDouble() < rule.get(op).asDouble() : value.asDouble() > rule.get(op).asDouble())
                    failures.add(pointer + " " + op + " 不满足: " + value);
            }
        });
        return failures;
    }
}
