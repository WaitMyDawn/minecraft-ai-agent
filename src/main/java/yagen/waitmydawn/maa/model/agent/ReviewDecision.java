package yagen.waitmydawn.maa.model.agent;

import java.util.List;
import java.util.Set;

/** Critic 的选择与明确否决。未选择和明确拒绝具有不同语义，补位不能覆盖明确拒绝。 */
public record ReviewDecision(int schemaVersion, List<String> approved,
                             List<Rejection> rejected, boolean allowBackfill, List<RequirementReview> requirements) {
    public ReviewDecision(int schemaVersion, List<String> approved, List<Rejection> rejected, boolean allowBackfill) {
        this(schemaVersion, approved, rejected, allowBackfill, List.of());
    }
    public enum Reason { OFF_TOPIC, CONFLICT, DUPLICATE_FUNCTION, CONSTRAINT, INSUFFICIENT_EVIDENCE, OTHER }
    public record Rejection(String slug, Reason reason, String explanation) {
        public Rejection {
            ContractRules.projects(List.of(ContractRules.required(slug, "rejected.slug")), "rejected.slug");
            ContractRules.required(reason, "rejected.reason");
            explanation = ContractRules.text(explanation, "rejected.explanation", 1, ContractRules.MAX_REASON);
        }
    }

    public ReviewDecision {
        ContractRules.version(schemaVersion);
        requirements = RequirementReview.validate(requirements);
        approved = ContractRules.projects(approved, "approved");
        rejected = ContractRules.list(rejected, "rejected", ContractRules.MAX_MODS);
        List<String> rejectedSlugs = rejected.stream().map(Rejection::slug).toList();
        ContractRules.projects(rejectedSlugs, "rejected");
        ContractRules.disjoint(approved, rejectedSlugs, "approved/rejected");
    }

    /** 模型输出必须属于本轮送审候选，额度校验不依赖模型自报数量。 */
    public void validateAgainst(Set<String> candidates, int approvalLimit) {
        ContractRules.required(candidates, "candidates");
        ContractRules.require(approvalLimit >= 0 && approved.size() <= approvalLimit, "approvalLimit");
        ContractRules.require(candidates.containsAll(approved)
                && rejected.stream().allMatch(item -> candidates.contains(item.slug())), "candidateMembership");
    }
}
