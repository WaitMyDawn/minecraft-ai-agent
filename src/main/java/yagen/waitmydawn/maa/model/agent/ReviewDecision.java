package yagen.waitmydawn.maa.model.agent;

import java.util.List;
import java.util.Set;

/** Critic 的选择、全包否决与局部落选；补位不能覆盖已经核实的全包否决。 */
public record ReviewDecision(int schemaVersion, List<String> approved,
                             List<Rejection> rejected, boolean allowBackfill, List<RequirementReview> requirements, Backfill backfill) {
    public ReviewDecision(int version, List<String> approved, List<Rejection> rejected, boolean allowBackfill, List<RequirementReview> requirements) {
        this(version, approved, rejected, allowBackfill, requirements, Backfill.LEGACY);
    }
    public ReviewDecision(int schemaVersion, List<String> approved, List<Rejection> rejected, boolean allowBackfill) {
        this(schemaVersion, approved, rejected, allowBackfill, List.of());
    }
    public enum Reason { OFF_TOPIC, CONFLICT, DUPLICATE_FUNCTION, CONSTRAINT, INSUFFICIENT_EVIDENCE, OTHER }
    public enum Scope { PACK, GOAL, LEGACY }
    public enum BackfillBasis { ALLOW, USER_REQUEST, POOL_EXHAUSTED, LEGACY }
    public record Authority(Scope scope, String targetId, String quote, String requestQuote, String relatedSlug) {
        public static final Authority LEGACY = new Authority(Scope.LEGACY, "", "", "", "");
        public Authority {
            ContractRules.required(scope, "authority.scope");
            targetId = ContractRules.text(targetId, "authority.targetId", 0, 64);
            quote = ContractRules.text(quote, "authority.quote", 0, 280);
            requestQuote = ContractRules.text(requestQuote, "authority.requestQuote", 0, 500);
            relatedSlug = ContractRules.text(relatedSlug, "authority.relatedSlug", 0, 128);
        }
    }
    public record Backfill(BackfillBasis basis, String quote) {
        public static final Backfill LEGACY = new Backfill(BackfillBasis.LEGACY, "");
        public Backfill {
            ContractRules.required(basis, "backfill.basis");
            quote = ContractRules.text(quote, "backfill.quote", 0, 500);
        }
    }
    public record Rejection(String slug, Reason reason, String explanation, Authority authority) {
        public Rejection(String slug, Reason reason, String explanation) { this(slug, reason, explanation, Authority.LEGACY); }
        public Rejection {
            ContractRules.projects(List.of(ContractRules.required(slug, "rejected.slug")), "rejected.slug");
            ContractRules.required(reason, "rejected.reason");
            explanation = ContractRules.text(explanation, "rejected.explanation", 1, ContractRules.MAX_REASON);
            ContractRules.required(authority, "rejected.authority");
        }
        public boolean global() { return authority.scope() != Scope.GOAL; }
    }

    public ReviewDecision {
        ContractRules.require(schemaVersion == 1 || schemaVersion == 2, "schemaVersion");
        ContractRules.required(backfill, "backfill");
        requirements = RequirementReview.validate(requirements);
        approved = ContractRules.projects(approved, "approved");
        rejected = validateRejections(rejected);
        ContractRules.disjoint(approved, rejected.stream().filter(Rejection::global).map(Rejection::slug).toList(), "approved/rejected");
    }
    static List<Rejection> validateRejections(List<Rejection> values) {
        var result = ContractRules.list(values, "rejected", ContractRules.MAX_MODS);
        var keys = new java.util.HashSet<String>();
        for (var item : result) ContractRules.require(keys.add(item.slug() + "\n"
                + (item.global() ? "PACK" : item.authority().targetId())), "rejected");
        return result;
    }

    /** 模型输出必须属于本轮送审候选，额度校验不依赖模型自报数量。 */
    public void validateAgainst(Set<String> candidates, int approvalLimit) {
        ContractRules.required(candidates, "candidates");
        ContractRules.require(approvalLimit >= 0 && approved.size() <= approvalLimit, "approvalLimit");
        ContractRules.require(candidates.containsAll(approved)
                && rejected.stream().allMatch(item -> candidates.contains(item.slug())), "candidateMembership");
    }
}
