package yagen.waitmydawn.maa.model.agent;

import java.util.List;
import java.util.Set;

/** 审核传输边界：结构严格校验，数量偏差与互斥选择由执行策略保守收敛。 */
public record ReviewResponse(int schemaVersion, List<String> approved,
                             List<ReviewDecision.Rejection> rejected, boolean allowBackfill, List<RequirementReview> requirements, ReviewDecision.Backfill backfill) {
    public ReviewResponse(int version, List<String> approved, List<ReviewDecision.Rejection> rejected, boolean allowBackfill, List<RequirementReview> requirements) {
        this(version, approved, rejected, allowBackfill, requirements, ReviewDecision.Backfill.LEGACY);
    }
    public ReviewResponse(int schemaVersion, List<String> approved, List<ReviewDecision.Rejection> rejected, boolean allowBackfill) {
        this(schemaVersion, approved, rejected, allowBackfill, List.of());
    }
    public ReviewResponse {
        ContractRules.require(schemaVersion == 1 || schemaVersion == 2, "schemaVersion");
        ContractRules.required(backfill, "backfill");
        requirements = RequirementReview.validate(requirements);
        approved = ContractRules.projects(approved, "approved");
        rejected = ReviewDecision.validateRejections(rejected);
    }

    public record Selection(ReviewDecision decision, int overlapRemoved, int excessApproved) {}

    /** 全包否决优先于批准；局部落选不改变其它目标的批准，也不扩大候选范围或本轮执行额度。 */
    public Selection select(Set<String> candidates, int approvalLimit) {
        return select(candidates, approvalLimit, candidates);
    }

    public Selection select(Set<String> candidates, int approvalLimit, Set<String> evidenceSources) {
        // v2 必须先经 ReviewAuthority 核对并转为执行裁决，不能仅凭字段完整就使用模型权限。
        ContractRules.require(schemaVersion == 1, "reviewAuthority");
        ContractRules.required(candidates, "candidates");
        ContractRules.require(approvalLimit >= 0, "approvalLimit");
        var outside = new java.util.ArrayList<String>();
        for (int i = 0; i < approved.size() && outside.size() < 8; i++)
            if (!candidates.contains(approved.get(i))) outside.add("/approved/" + i);
        for (int i = 0; i < rejected.size() && outside.size() < 8; i++)
            if (!candidates.contains(rejected.get(i).slug())) outside.add("/rejected/" + i + "/slug");
        if (!outside.isEmpty()) throw new ContractRules.Violation("candidateMembership",
                String.join("、", outside) + " 不在本次 compatibleCandidates 白名单；移除这些批准或否决项，检查其它同类项。"
                        + "retainedProjects 是只读已有项目，不需要重新批准或拒绝；不能改写 slug 猜测其它候选");
        Set<String> vetoes = Set.copyOf(rejected.stream().filter(ReviewDecision.Rejection::global).map(ReviewDecision.Rejection::slug).toList());
        for (var review : requirements) {
            var evidence = java.util.stream.Stream.concat(review.evidence().stream(),
                    review.criteria().stream().flatMap(c -> c.evidence().stream())).toList();
            ContractRules.require(evidence.stream().allMatch(e -> evidenceSources.contains(e.slug())), "requirements.evidence.source");
            ContractRules.require(evidence.stream().noneMatch(e -> vetoes.contains(e.slug())), "requirements.evidence.rejected");
        }
        List<String> permitted = approved.stream().filter(slug -> !vetoes.contains(slug)).toList();
        return new Selection(new ReviewDecision(schemaVersion, permitted, rejected, allowBackfill, requirements, backfill),
                approved.size() - permitted.size(), Math.max(0, permitted.size() - approvalLimit));
    }
}
