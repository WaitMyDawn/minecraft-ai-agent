package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import yagen.waitmydawn.maa.service.MessageJson;
import java.util.*;
import java.util.regex.*;

/** 最终需求展示结论；总量与叶目标分别核对，不把身份保留或局部满足扩大为整包达标。 */
public final class BuildCompletion {
    private BuildCompletion() {}
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public record Quantity(String status, String label, String scope, String precision, Integer target, Integer min,
                           Integer max, Integer actual, Integer acceptedMin, Integer acceptedMax, String display) {}
    public record Targets(int met, int partial, int notMet, int unknown, int waiting, int context, int parentIssues) {}
    public record Summary(int schemaVersion, String outcome, String label, String note, Quantity quantity, Targets targets) {}

    public static Summary assess(JsonNode coverage, JsonNode trace) {
        var quantity = quantity(trace.path("contract"));
        int[] totals = new int[7];
        boolean parentGap = false, parentWaiting = false, parentUnknown = false;
        for (var row : coverage.path("requirements")) {
            if (!row.path("criteria").isEmpty()) {
                for (var child : row.path("criteria")) count(child, totals);
                if (!Set.of("MET", "CONTEXT").contains(outcome(row))) totals[6]++;
                if (Set.of("PARTIAL", "NOT_MET").contains(outcome(row))) parentGap = true;
                if (outcome(row).equals("WAITING")) parentWaiting = true;
                if (outcome(row).equals("UNKNOWN")) parentUnknown = true;
            } else count(row, totals);
        }
        var targets = new Targets(totals[0], totals[1], totals[2], totals[3], totals[4], totals[5], totals[6]);
        int checked = targets.met() + targets.partial() + targets.notMet() + targets.unknown() + targets.waiting();
        boolean absent = !trace.path("contract").path("requirementLedger").isEmpty() && coverage.path("requirements").isEmpty();
        boolean missing = !trace.path("contract").path("missingMustHave").isEmpty();
        String outcome, label;
        var notes = new ArrayList<String>();
        if (quantity.status().equals("NOT_MET")) notes.add("全局数量尚未达标，局部目标满足不能代替总量要求");
        if (targets.partial() + targets.notMet() > 0 || parentGap) notes.add("部分内容目标仍有缺口");
        if (targets.unknown() > 0 || parentUnknown || absent) notes.add("部分要求仍待核对");
        if (missing) notes.add("点名项目尚未全部保留");
        if (trace.path("awaitingInput").asBoolean() || targets.waiting() > 0 || parentWaiting) {
            outcome = "WAITING"; label = "等待确认，尚非最终结果";
        } else if (quantity.status().equals("NOT_MET") || targets.partial() + targets.notMet() > 0 || parentGap || missing) {
            outcome = "PARTIAL"; label = "尚未完全满足构建要求";
        } else if (quantity.status().equals("UNVERIFIED") || targets.unknown() + targets.parentIssues() > 0 || absent) {
            outcome = "UNKNOWN"; label = "整体结果仍待核实";
        } else if (checked == 0) {
            outcome = "CONTEXT"; label = "仅完成基础信息核对";
            notes.add("未记录可逐项核对的内容目标");
        } else {
            outcome = "MET"; label = "已核对的构建要求均满足";
        }
        if (quantity.status().equals("UNVERIFIED")) notes.add("全局计数缺少可靠核对依据");
        return new Summary(1, outcome, label, String.join("；", notes), quantity, targets);
    }
    private static void count(JsonNode row, int[] totals) {
        int i = switch (outcome(row)) { case "MET" -> 0; case "PARTIAL" -> 1; case "NOT_MET" -> 2;
            case "WAITING" -> 4; case "CONTEXT" -> 5; default -> 3; };
        totals[i]++;
    }
    private static String outcome(JsonNode row) {
        if (row.path("status").asText().equals("CONTEXT")) return "CONTEXT";
        var assessment = row.path("assessment");
        if (!assessment.path("schemaVersion").isIntegralNumber() || assessment.path("schemaVersion").asInt() != 1) return "UNKNOWN";
        String result = assessment.path("outcome").asText();
        return Set.of("MET", "PARTIAL", "NOT_MET", "UNKNOWN", "WAITING", "CONTEXT").contains(result) ? result : "UNKNOWN";
    }
    private static Integer integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() && value.asInt() >= 0 && value.asInt() <= 500 ? value.asInt() : null;
    }
    private static Quantity quantity(JsonNode contract) {
        var q = contract.path("quantity");
        if (contract.has("quantity") && q.isNull()) return new Quantity("NO_TARGET", "无额外数量要求", "", "", null, null, null, null, null, null, "本轮未设额外全局数量要求");
        Integer target = integer(q.path("target")), min = integer(q.path("min")), max = integer(q.path("max")), actual = integer(q.path("actual"));
        String scope = q.path("scope").asText(), precision = q.path("precision").asText();
        String unit = switch (scope) { case "TOTAL_FILES" -> "文件总数（含前置）"; case "ROOT_MODS" -> "根模组数（不含自动前置）";
            case "ADDED_ROOTS" -> "本轮新增根模组数"; default -> "全局数量"; };
        if (target == null || !Set.of("TOTAL_FILES", "ROOT_MODS", "ADDED_ROOTS").contains(scope)
                || !Set.of("EXACT", "APPROXIMATE").contains(precision)) return unknownQuantity();
        if (min == null && !q.has("min")) min = target;
        if (max == null && !q.has("max")) max = target;
        if (min == null || max == null || min > target || max < target) return unknownQuantity();
        var count = new BuildIntent.CountTarget(target, BuildIntent.CountScope.valueOf(scope), BuildIntent.Precision.valueOf(precision), min, max);
        int low = count.range() || count.precision() == BuildIntent.Precision.EXACT ? min : (int) Math.ceil(target * .8);
        int high = count.range() || count.precision() == BuildIntent.Precision.EXACT ? max : Math.min(500, (int) Math.floor(target * 1.5));
        String goal = count.range() ? min + "～" + max : (precision.equals("APPROXIMATE") ? "约 " : "") + target;
        boolean known = actual != null && q.path("met").isBoolean();
        String display = unit + "：" + (known ? actual : "待核实") + " / " + goal + " 个";
        if (!count.range() && precision.equals("APPROXIMATE")) display += "；当前数量策略范围 " + low + "～" + high + " 个";
        String status = !known ? "UNVERIFIED" : count.accepts(actual) ? "MET" : "NOT_MET";
        return new Quantity(status, switch (status) { case "MET" -> "数量已达标"; case "NOT_MET" -> "数量未达标"; default -> "数量待核实"; },
                scope, precision, target, min, max, actual, low, high, display);
    }
    private static Quantity unknownQuantity() {
        return new Quantity("UNVERIFIED", "数量待核实", "", "", null, null, null, null, null, null, "历史或当前记录缺少可靠的全局数量核对数据");
    }
    /** 发布及选择收口共用此入口，避免保存的汇总仍引用选择前的文件数或覆盖结果。 */
    public static String enrich(String reply) {
        try {
            String rawTrace = tag(reply, "trace"), rawCoverage = tag(reply, "requirement_report");
            if (rawTrace.isEmpty()) return reply;
            if (rawCoverage.isEmpty() && reply.contains("<requirement_report>")) return reply;
            if (!(JSON.readTree(rawTrace) instanceof ObjectNode trace)) return reply;
            ObjectNode coverage;
            if (rawCoverage.isEmpty()) {
                if (!trace.path("contract").isObject()) return reply;
                coverage = JSON.createObjectNode().put("schemaVersion", 1).put("repairStatus", "NOT_REQUESTED");
                coverage.putArray("requirements"); coverage.putArray("repaired"); coverage.putArray("ledger");
            } else {
                if (!(JSON.readTree(rawCoverage) instanceof ObjectNode parsed) || !parsed.path("schemaVersion").isIntegralNumber() || parsed.path("schemaVersion").asInt() != 1
                        || !parsed.path("requirements").isArray()) return reply;
                coverage = parsed;
            }
            coverage.set("completion", JSON.valueToTree(assess(coverage, trace)));
            trace.set("requirementCoverage", coverage);
            String enriched = replace(reply, "trace", MessageJson.write(trace));
            String encoded = "<requirement_report>" + MessageJson.write(coverage) + "</requirement_report>";
            return rawCoverage.isEmpty() ? enriched + "\n" + encoded : replace(enriched, "requirement_report", MessageJson.write(coverage));
        } catch (RuntimeException invalid) {
            yagen.waitmydawn.maa.logging.MaaLog.app("整体需求展示数据未生成：" + invalid.getClass().getSimpleName());
            return reply;
        }
    }
    private static String tag(String reply, String name) {
        var match = Pattern.compile("(?s)<" + name + ">(.*?)</" + name + ">").matcher(reply);
        return match.find() && match.group(1).length() <= 2_000_000 ? match.group(1) : "";
    }
    private static String replace(String reply, String name, String value) {
        return reply.replaceFirst("(?s)<" + name + ">.*?</" + name + ">", Matcher.quoteReplacement("<" + name + ">" + value + "</" + name + ">"));
    }
}
