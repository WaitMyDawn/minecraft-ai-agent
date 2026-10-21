package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.*;

/** 生产报告的完成结论；展示端消费结论，不根据名称或检索命中重新推断用途。 */
public final class RequirementAssessment {
    private RequirementAssessment() {}
    private static final JsonMapper JSON=JsonMapper.builder().build();
    public record Result(int schemaVersion, String outcome, String label, String evidenceCode, String evidenceLabel,
                         List<String> userRemoved, List<String> cleanupRemoved, List<String> automaticRemoved) {}
    public static Result assess(String status, RequirementCoverage.EvidenceState state, RequirementCoverage.Quantity quantity,
            int mods, int missing, List<Result> children) {
        String code=state==null ? "UNKNOWN" : state.code();
        String outcome;
        if(status.equals("CONTEXT")) outcome="CONTEXT";
        else if(status.equals("WAITING_SELECTION")) outcome="WAITING";
        else if(missing>0 || code.equals("PROJECT_MISSING")) outcome=mods>0 ? "PARTIAL" : "NOT_MET";
        else if(state!=null && !state.countsComplete() || quantity!=null && !quantity.complete()) outcome="UNKNOWN";
        else if(quantity!=null && quantity.actual()<quantity.min()) outcome=quantity.actual()>0 ? "PARTIAL" : "NOT_MET";
        else if(quantity!=null && quantity.actual()>quantity.max()) outcome="PARTIAL";
        else if(Set.of("VERSION_PENDING","RELATION_PENDING","CORE_GENERATION_PENDING","DISPLAY_TRUNCATED").contains(code))
            outcome=mods>0 && status.equals("PARTIAL") ? "PARTIAL" : "UNKNOWN";
        else if(!children.isEmpty()) {
            var outcomes=children.stream().map(Result::outcome).filter(s -> !s.equals("CONTEXT")).toList();
            outcome=outcomes.isEmpty() ? "CONTEXT" : outcomes.stream().allMatch("MET"::equals) ? "MET"
                    : outcomes.stream().allMatch("NOT_MET"::equals) ? "NOT_MET"
                    : outcomes.stream().anyMatch(s -> s.equals("MET") || s.equals("PARTIAL")) ? "PARTIAL"
                    : outcomes.contains("WAITING") ? "WAITING" : "UNKNOWN";
        } else if(Set.of("VERIFIED_PROJECTS","SUPPORTING_EVIDENCE").contains(status)) outcome=code.equals("VERIFIED") ? "MET" : "UNKNOWN";
        else outcome=status.equals("PARTIAL") && mods>0 ? "PARTIAL" : "UNKNOWN";
        String evidence=switch(code) {
            case "VERIFIED" -> "已有核对依据";
            case "PROJECT_MISSING" -> "点名项目未保留";
            case "VERSION_PENDING" -> "选版尚未核实";
            case "RELATION_PENDING" -> "附属或联动关系待核实";
            case "CORE_GENERATION_PENDING" -> "核心生成依据不足";
            case "NOT_SELECTED", "EVIDENCE_REMOVED" -> "此前证据项目未保留";
            case "VETOED" -> "证据项目已被否决";
            case "QUOTE_MISMATCH", "FACT_CHANGED" -> "原文引用不再匹配";
            case "FACTS_UNAVAILABLE" -> "项目资料尚未取得";
            case "DISPLAY_TRUNCATED" -> "历史证据不完整";
            case "REVIEW_GAP" -> "审核仍有待核对事项";
            default -> "用途依据尚未核实";
        };
        if(quantity!=null && !quantity.complete()) evidence+="；数量证据不完整";
        String label=switch(outcome) { case "MET" -> "已满足"; case "PARTIAL" -> "部分满足"; case "NOT_MET" -> "未满足";
            case "WAITING" -> "待选择"; case "CONTEXT" -> "上下文"; default -> "暂无法核实"; };
        if (outcome.equals("MET") && status.equals("VERIFIED_PROJECTS")) label = "点名项目已保留";
        return new Result(1,outcome,label,code,evidence,List.of(),List.of(),List.of());
    }
    /** 选择后的旧展示快照可能缺少完整输入；重新生成结论并保留明确的选择原因。 */
    public static void refresh(JsonNode coverage, JsonNode previous, Set<String> rejected, Set<String> removed) {
        refresh(coverage,previous,rejected,removed,Set.of());
    }
    public static void refresh(JsonNode coverage, JsonNode previous, Set<String> rejected, Set<String> removed, Set<String> automatic) {
        var old=new HashMap<String,JsonNode>(); index(previous,old);
        for(var row : coverage.path("requirements")) refreshRow(row,old,rejected,removed,automatic);
        for(var proof : coverage.path("ledger")) if(proof instanceof ObjectNode entry) {
            String slug=proof.path("slug").asText();
            if(removed.contains(slug)) entry.put("status","NOT_RETAINED");
            else for(var partner : proof.path("partners")) if(removed.contains(partner.asText())) entry.put("status","RELATION_CHANGED");
        }
    }
    private static void index(JsonNode report, Map<String,JsonNode> rows) {
        for(var row : report.path("requirements")) {
            rows.put(row.path("id").asText(),row);
            for(var child : row.path("criteria")) rows.put(child.path("id").asText(),child);
        }
    }
    private static Result refreshRow(JsonNode value, Map<String,JsonNode> old, Set<String> rejected, Set<String> removed, Set<String> automatic) {
        var children=new ArrayList<Result>(); for(var child : value.path("criteria")) children.add(refreshRow(child,old,rejected,removed,automatic));
        var state=value.path("evidenceState"); var q=value.path("quantity");
        boolean validQuantity=q.isObject() && Set.of("RETAINED_ROOTS","ADDED_ROOTS").contains(q.path("scope").asText());
        var quantity=validQuantity ? new RequirementCoverage.Quantity(q.path("min").asInt(),q.path("max").asInt(),
                yagen.waitmydawn.maa.model.agent.RequirementQuantity.Scope.valueOf(q.path("scope").asText()),q.path("actual").asInt(),List.of(),q.path("complete").asBoolean()) : null;
        var result=assess(value.path("status").asText(),new RequirementCoverage.EvidenceState(state.path("code").asText("UNKNOWN"),List.of(),state.path("countsComplete").asBoolean(true) && (!q.isObject() || validQuantity)),
                quantity,value.path("modCount").asInt(value.path("mods").size()),value.path("missingCount").asInt(value.path("missing").size()),children);
        var former=old.getOrDefault(value.path("id").asText(),value);
        var projects=new TreeSet<String>();
        for(var item : former.path("mods")) {
            projects.add(item.path("slug").asText());
            for(var partner : item.path("partners")) projects.add(partner.asText());
        }
        for(var slug : former.path("quantity").path("projects")) projects.add(slug.asText());
        for(var slug : former.path("missing")) projects.add(slug.asText());
        var user=new TreeSet<String>(); var cleanup=new TreeSet<String>(); var configured=new TreeSet<String>();
        for(var slug : former.path("assessment").path("userRemoved")) user.add(slug.asText());
        for(var slug : former.path("assessment").path("cleanupRemoved")) cleanup.add(slug.asText());
        for(var slug : former.path("assessment").path("automaticRemoved")) configured.add(slug.asText());
        for(String slug : projects) if(automatic.contains(slug)) configured.add(slug); else if(rejected.contains(slug)) user.add(slug); else if(removed.contains(slug)) cleanup.add(slug);
        result=new Result(1,result.outcome(),result.label(),result.evidenceCode(),result.evidenceLabel(),List.copyOf(user),List.copyOf(cleanup),List.copyOf(configured));
        if(value instanceof ObjectNode row) row.set("assessment",JSON.valueToTree(result));
        return result;
    }
}
