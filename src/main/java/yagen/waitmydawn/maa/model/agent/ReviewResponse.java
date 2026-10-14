package yagen.waitmydawn.maa.model.agent;

import java.util.List;
import java.util.Set;

/** 审核传输边界：结构严格校验，数量偏差与互斥选择由执行策略保守收敛。 */
public record ReviewResponse(int schemaVersion, List<String> approved,
                             List<ReviewDecision.Rejection> rejected, boolean allowBackfill, List<RequirementReview> requirements) {
    public ReviewResponse(int schemaVersion, List<String> approved, List<ReviewDecision.Rejection> rejected, boolean allowBackfill) {
        this(schemaVersion, approved, rejected, allowBackfill, List.of());
    }
    public ReviewResponse {
        ContractRules.version(schemaVersion);
        requirements = RequirementReview.validate(requirements);
        approved = ContractRules.projects(approved, "approved");
        rejected = ContractRules.list(rejected, "rejected", ContractRules.MAX_MODS);
        ContractRules.projects(rejected.stream().map(ReviewDecision.Rejection::slug).toList(), "rejected");
    }

    public record Selection(ReviewDecision decision, int overlapRemoved, int excessApproved) {}

    /** 否决优先于批准；只容忍池内选择的偏差，不扩大候选范围或本轮执行额度。 */
    public Selection select(Set<String> candidates, int approvalLimit) {
        ContractRules.required(candidates, "candidates");
        ContractRules.require(approvalLimit >= 0, "approvalLimit");
        ContractRules.require(candidates.containsAll(approved)
                && rejected.stream().allMatch(item -> candidates.contains(item.slug())), "candidateMembership");
        Set<String> vetoes = Set.copyOf(rejected.stream().map(ReviewDecision.Rejection::slug).toList());
        List<String> permitted = approved.stream().filter(slug -> !vetoes.contains(slug)).toList();
        return new Selection(new ReviewDecision(schemaVersion, permitted, rejected, allowBackfill, requirements),
                approved.size() - permitted.size(), Math.max(0, permitted.size() - approvalLimit));
    }
}
