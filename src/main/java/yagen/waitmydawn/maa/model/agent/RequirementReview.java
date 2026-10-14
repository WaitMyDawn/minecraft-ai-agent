package yagen.waitmydawn.maa.model.agent;

import java.util.List;

/** 审核用途关联；原文摘录须由 Java 对照项目元数据，不能以模型解释替代证据。 */
public record RequirementReview(String id, List<Contribution> evidence, String gap, List<CriterionReview> criteria) {
    public RequirementReview(String id, List<Contribution> evidence, String gap) { this(id, evidence, gap, List.of()); }
    public record Contribution(String slug, String quote, String purpose) {
        public Contribution {
            ContractRules.projects(List.of(slug), "evidence.slug");
            quote = ContractRules.text(quote, "evidence.quote", 4, 280);
            purpose = ContractRules.text(purpose, "evidence.purpose", 1, 300);
        }
    }
    public RequirementReview {
        ContractRules.require(id != null && id.matches("r-[a-f0-9]{16}"), "requirements.id");
        evidence = ContractRules.list(evidence, "requirements.evidence", 12);
        ContractRules.projects(evidence.stream().map(Contribution::slug).toList(), "requirements.evidence");
        gap = ContractRules.text(gap, "requirements.gap", 0, 500);
        criteria = ContractRules.list(criteria, "requirements.criteria", 16);
        ContractRules.require(criteria.stream().map(CriterionReview::source).distinct().count() == criteria.size(), "requirements.criteria.duplicates");
    }
    public static List<RequirementReview> validate(List<RequirementReview> rows) {
        var result = ContractRules.list(rows, "requirements", 64);
        ContractRules.require(result.stream().map(RequirementReview::id).distinct().count() == result.size(), "requirements.duplicates");
        ContractRules.require(result.stream().mapToInt(r -> r.criteria().size()).sum() <= 128, "requirements.criteria.total");
        return result;
    }
}
