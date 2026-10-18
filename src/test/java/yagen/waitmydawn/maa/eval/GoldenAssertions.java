package yagen.waitmydawn.maa.eval;

import tools.jackson.databind.JsonNode;
import java.util.*;

/** 严格检查声明的 JSON Pointer 断言，缺字段不能按零值或空集合通过。 */
final class GoldenAssertions {
    private static final Set<String> OPS = Set.of("equals", "contains", "absent", "min", "max");
    private GoldenAssertions() {}
    static void validateComposition(JsonNode rule) {
        if (!rule.isObject() || rule.isEmpty()) throw new IllegalArgumentException("composition 必须是非空对象");
        rule.properties().forEach(e -> {
            if (!Set.of("retainedRelations", "noKnownAddonGaps").contains(e.getKey()) || !e.getValue().isBoolean())
                throw new IllegalArgumentException("composition 只支持布尔值 retainedRelations/noKnownAddonGaps");
        });
    }
    static List<String> checkComposition(JsonNode facts, Set<String> selected, JsonNode rule) {
        validateComposition(rule); var failures = new ArrayList<String>();
        if (rule.path("noKnownAddonGaps").asBoolean()) {
            var checks = facts.at("/trace/addonApplicability");
            if (!checks.isObject()) failures.add("缺少附属适用范围审计");
            else checks.properties().forEach(e -> {
                if (!selected.contains(e.getKey())) return;
                var check = e.getValue();
                if (!check.path("missingPartners").isArray() || !check.path("introducedContent").isArray())
                    failures.add("附属适用范围字段不完整: " + e.getKey());
                else if (check.path("status").asText().equals("PARTNERS_UNVERIFIED") || !check.path("missingPartners").isEmpty() || !check.path("introducedContent").isEmpty())
                    failures.add("最终仍保留已知适用对象缺口或额外内容: " + e.getKey());
            });
        }
        if (rule.path("retainedRelations").asBoolean()) {
            var rows = facts.at("/requirement_report/requirements");
            var trace = facts.at("/trace/addonEvidence");
            if (!rows.isArray() || !trace.isArray()) failures.add("缺少最终联动关系报告");
            var pending = new ArrayDeque<JsonNode>(); rows.forEach(pending::add);
            while (!pending.isEmpty()) {
                var row = pending.removeFirst(); row.path("criteria").forEach(pending::add);
                for (var item : row.path("mods")) if (Set.of("VERSION_RELATION", "DESCRIPTION_RELATION").contains(item.path("evidenceType").asText())) {
                    checkRelationProject(item.path("slug").asText(), selected, facts, failures);
                    checkRelationPartners(item, selected, facts, failures);
                    if (item.path("partners").isArray() && item.path("partners").isEmpty()) failures.add("需求关系缺少实际对象");
                    if (item.path("evidenceType").asText().equals("DESCRIPTION_RELATION") && item.path("quote").asText().isBlank()) failures.add("介绍关系缺少原文");
                }
            }
            for (var addon : trace) for (var relation : addon.path("finalEvidence")) {
                checkRelationProject(addon.path("slug").asText(), selected, facts, failures);
                checkRelationProject(relation.path("core").asText(), selected, facts, failures);
                checkRelationPartners(relation, selected, facts, failures);
                if (relation.path("relationSource").asText().equals("DESCRIPTION_RELATION") && relation.path("quote").asText().isBlank()) failures.add("最终介绍关系缺少原文");
            }
        }
        return failures;
    }
    private static void checkRelationPartners(JsonNode item, Set<String> selected, JsonNode facts, List<String> failures) {
        if (!item.path("partners").isArray()) { failures.add("关系缺少明确的对象列表"); return; }
        for (var partner : item.path("partners")) checkRelationProject(partner.asText(), selected, facts, failures);
    }
    private static void checkRelationProject(String slug, Set<String> selected, JsonNode facts, List<String> failures) {
        if (slug.isBlank() || !selected.contains(slug) || facts.at("/trace/assembly/versions").path(slug).asText().isBlank())
            failures.add("关系对象未在最终选版留存: " + slug);
    }
    static void validateGeneration(JsonNode rule) {
        if (!rule.isObject() || rule.isEmpty()) throw new IllegalArgumentException("generation 必须是非空对象");
        rule.properties().forEach(e -> {
            if (!Set.of("required", "forbidden").contains(e.getKey()) || !e.getValue().isArray() || e.getValue().isEmpty())
                throw new IllegalArgumentException("generation 需要 required/forbidden 非空维度数组");
            var seen = new HashSet<String>();
            for (var dimension : e.getValue()) if (!dimension.isTextual() || dimension.asText().isBlank() || !seen.add(dimension.asText()))
                throw new IllegalArgumentException("generation 维度必须是唯一非空文本");
        });
        for (var dimension : rule.path("required")) for (var forbidden : rule.path("forbidden"))
            if (dimension.equals(forbidden)) throw new IllegalArgumentException("同一维度不能同时要求和禁止");
    }
    static List<String> checkGeneration(JsonNode facts, Set<String> selected, JsonNode rule) {
        validateGeneration(rule);
        var report = facts.path("requirement_report");
        if (report.path("schemaVersion").asInt() != 1 || !report.path("requirements").isArray() || report.path("requirements").isEmpty()) return List.of("缺少维度生成需求报告");
        var pending = new ArrayDeque<JsonNode>(); report.path("requirements").forEach(pending::add);
        var targets = new HashSet<String>(); var covered = new HashSet<String>(); var failures = new ArrayList<String>();
        while (!pending.isEmpty()) {
            var row = pending.removeFirst(); row.path("criteria").forEach(pending::add);
            for (var generation : row.path("evidenceState").path("generation")) {
                String dimension = generation.path("dimension").asText(); targets.add(dimension);
                if (!generation.path("projects").isArray() || generation.path("projects").isEmpty()) continue;
                boolean retained = true;
                for (var project : generation.path("projects")) {
                    String slug = project.asText();
                    if (!project.isTextual() || !selected.contains(slug) || facts.at("/trace/assembly/versions").path(slug).asText("").isBlank()) {
                        failures.add("核心生成依据未留存在最终选版: " + slug); retained = false;
                    }
                }
                if (retained) covered.add(dimension);
            }
        }
        for (var dimension : rule.path("required")) if (!covered.contains(dimension.asText())) failures.add("缺少已留存核心生成依据: " + dimension.asText());
        for (var dimension : rule.path("forbidden")) if (targets.contains(dimension.asText())) failures.add("意外新增核心生成目标: " + dimension.asText());
        return failures;
    }
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
