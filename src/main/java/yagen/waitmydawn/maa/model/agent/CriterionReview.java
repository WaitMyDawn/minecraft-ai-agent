package yagen.waitmydawn.maa.model.agent;

import java.util.List;

/** source 精确引用规划子目标；每个目标的用途证据独立核对。 */
public record CriterionReview(String source, List<RequirementReview.Contribution> evidence, String gap) {
    public CriterionReview {
        source = ContractRules.text(source, "criteria.source", 1, 4000);
        evidence = ContractRules.list(evidence, "criteria.evidence", 6);
        ContractRules.projects(evidence.stream().map(RequirementReview.Contribution::slug).toList(), "criteria.evidence");
        gap = ContractRules.text(gap, "criteria.gap", 0, 500);
    }
}
