package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;

/** 请求内审核权限及用途证据账本；片段必须通过作用域、来源和原文核对，不能由报告失败扩大权限。 */
public final class ReviewEvidenceLedger {
    private final List<BuildRequirement> requirements;
    private final Set<String> candidates, evidenceSources;
    private final Map<String, String> facts;
    private final List<AgentContractCodec.ReviewFragments> attempts = new ArrayList<>();
    private final List<ReviewAuthority.Result> authorities = new ArrayList<>();
    private final ReviewAuthority authority;
    private final boolean strictRecovery;
    private final List<AgentContractCodec.FragmentIssue> issues = new ArrayList<>();
    private final AgentContractCodec codec = new AgentContractCodec();
    private int captures;
    private int acceptedAttempt = -1;
    private Set<String> acceptedApprovals = Set.of();

    public ReviewEvidenceLedger(List<BuildRequirement> requirements, Set<String> candidates,
                                Set<String> evidenceSources, Map<String, String> facts) {
        this(requirements, candidates, evidenceSources, facts, "", false, 0);
    }
    public ReviewEvidenceLedger(List<BuildRequirement> requirements, Set<String> candidates,
                                Set<String> evidenceSources, Map<String, String> facts, String request) {
        this(requirements, candidates, evidenceSources, facts, request, true, 0);
    }
    public ReviewEvidenceLedger(List<BuildRequirement> requirements, Set<String> candidates,
                                Set<String> evidenceSources, Map<String, String> facts, String request, int expectedVersion) {
        this(requirements, candidates, evidenceSources, facts, request, true, expectedVersion);
    }
    private ReviewEvidenceLedger(List<BuildRequirement> requirements, Set<String> candidates,
                                Set<String> evidenceSources, Map<String, String> facts, String request, boolean strictRecovery, int expectedVersion) {
        this.requirements = List.copyOf(requirements); this.candidates = Set.copyOf(candidates);
        this.evidenceSources = Set.copyOf(evidenceSources); this.facts = Map.copyOf(facts);
        this.authority = new ReviewAuthority(requirements, candidates, evidenceSources, facts, request, expectedVersion);
        this.strictRecovery = strictRecovery;
    }
    public void capture(String response) {
        if (captures++ >= 4) return;
        try {
            var fragments = codec.readReviewFragments(response, candidates);
            // 新候选的批准尚未通过选模校验，不能先用于证明冲突对象已选或剩余候选已耗尽。
            var inspected = authority.inspect(fragments.schemaVersion(), fragments.rejections(), Set.of(),
                    !fragments.backfillForbidden(), fragments.backfill());
            var claimed = authority.inspect(fragments.schemaVersion(), fragments.rejections(), fragments.approvals(),
                    !fragments.backfillForbidden(), fragments.backfill());
            var pending = new HashSet<>(inspected.pendingVetoes());
            var parsed = fragments.rejections().stream().filter(ReviewDecision.Rejection::global)
                    .map(ReviewDecision.Rejection::slug).collect(java.util.stream.Collectors.toSet());
            fragments.vetoes().stream().filter(s -> !parsed.contains(s)).forEach(pending::add);
            attempts.add(fragments); fragments.issues().forEach(this::issue);
            authorities.add(new ReviewAuthority.Result(inspected.globalVetoes(), inspected.localVetoes(), Set.copyOf(pending),
                    inspected.backfillForbidden(), inspected.backfillUnverified(), claimed.issues()));
            claimed.issues().forEach(this::issue);
        } catch (RuntimeException invalid) {
            issue(new AgentContractCodec.FragmentIssue("/", "UNREADABLE_RESPONSE"));
            if (strictRecovery) {
                attempts.add(new AgentContractCodec.ReviewFragments(List.of(), Set.of(), Set.of(), false, List.of(),
                        2, List.of(), null, Set.of()));
                authorities.add(new ReviewAuthority.Result(Set.of(), Map.of(), Set.of(), false, acceptedAttempt < 0, List.of()));
            }
        }
    }
    private void issue(AgentContractCodec.FragmentIssue issue) { if (issues.size() < 128) issues.add(issue); }
    /** 仅在选模契约与候选白名单校验后调用；最终数量仍由执行策略收敛，不解除用户选择或黑名单。 */
    public void acceptSelection(Collection<String> approved) {
        if (!candidates.containsAll(approved)) throw new IllegalArgumentException("批准项超出候选范围");
        acceptedAttempt = attempts.size() - 1;
        acceptedApprovals = Set.copyOf(approved);
        if (acceptedAttempt >= 0) {
            var attempt = attempts.get(acceptedAttempt);
            authorities.set(acceptedAttempt, authority.inspect(attempt.schemaVersion(), attempt.rejections(), acceptedApprovals,
                    !attempt.backfillForbidden(), attempt.backfill()));
        }
    }
    public Set<String> vetoes() {
        return vetoes(true);
    }
    private Set<String> vetoes(boolean includePending) {
        var result = new LinkedHashSet<String>();
        for (int i=0;i<attempts.size();i++) {
            var attempt = attempts.get(i); var vetoes = new HashSet<>(authorities.get(i).globalVetoes());
            // 同一草稿中的互相矛盾不构成永久裁决；仅后续已验证批准可以消除此歧义。
            if (i < acceptedAttempt) for (String slug : attempt.contradictoryVetoes())
                if (acceptedApprovals.contains(slug)) vetoes.remove(slug);
            result.addAll(vetoes);
            if (includePending && i >= acceptedAttempt) result.addAll(authorities.get(i).pendingVetoes());
        }
        return Set.copyOf(result);
    }
    public boolean backfillForbidden() {
        if (authority.userBackfillForbidden()) return true;
        for (int i=0; i<authorities.size(); i++) if (authorities.get(i).backfillForbidden()
                || (i >= acceptedAttempt && authorities.get(i).backfillUnverified())) return true;
        return false;
    }
    public Map<String, Object> authoritySummary() {
        var local = new LinkedHashMap<String, Set<String>>();
        authorities.forEach(a -> a.localVetoes().forEach((key, values) -> local.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values)));
        var pending = new HashSet<>(vetoes()); pending.removeAll(vetoes(false));
        return Map.of("effectiveBlocked", vetoes(), "confirmedGlobalVetoes", vetoes(false), "pendingVetoes", Set.copyOf(pending),
                "localVetoes", local, "backfillForbidden", backfillForbidden(),
                "verifiedBackfillForbidden", authority.userBackfillForbidden() || authorities.stream().anyMatch(ReviewAuthority.Result::backfillForbidden),
                "expectedVersion", authority.expectedVersion(),
                "versions", attempts.stream().map(AgentContractCodec.ReviewFragments::schemaVersion).distinct().toList(),
                "acceptedAttempt", acceptedAttempt);
    }
    public List<AgentContractCodec.FragmentIssue> issues() { return issues.stream().distinct().toList(); }

    public RequirementCoverage.Verification verify(Set<String> otherVetoes) {
        var blocked = new HashSet<>(otherVetoes); blocked.addAll(vetoes());
        var known = new HashMap<String, BuildRequirement>(); requirements.forEach(r -> known.put(r.id(), r));
        var merged = new LinkedHashMap<String, RequirementReview>();
        var checks = new LinkedHashMap<String, RequirementCoverage.EvidenceCheck>();
        int[] totals = new int[7];
        var local = new LinkedHashMap<String, Set<String>>();
        authorities.forEach(a -> a.localVetoes().forEach((key, values) -> local.computeIfAbsent(key, ignored -> new LinkedHashSet<>()).addAll(values)));
        for (var attempt : attempts) {
            var scoped = new ArrayList<RequirementReview>();
            for (var row : attempt.requirements()) {
                var target = known.get(row.id());
                if (target == null) { issue(new AgentContractCodec.FragmentIssue("/requirements/" + row.id(), "UNKNOWN_REQUIREMENT")); continue; }
                if (target.criteria().isEmpty()) {
                    if (row.criteria().isEmpty()) scoped.add(new RequirementReview(row.id(), sources(row.id(), row.evidence()), row.gap()));
                    else issue(new AgentContractCodec.FragmentIssue("/requirements/" + row.id() + "/criteria", "UNEXPECTED_CRITERIA"));
                } else {
                    if (!row.evidence().isEmpty() || !row.gap().isEmpty())
                        issue(new AgentContractCodec.FragmentIssue("/requirements/" + row.id(), "PARENT_REPORT_IGNORED"));
                    for (int i = 0; i < row.criteria().size(); i++) {
                        var child = row.criteria().get(i);
                        if (target.criteria().stream().noneMatch(t -> t.source().equals(child.source())))
                            issue(new AgentContractCodec.FragmentIssue("/requirements/" + row.id() + "/criteria/" + i, "UNKNOWN_CRITERION"));
                    }
                    var children = row.criteria().stream().filter(c -> target.criteria().stream().anyMatch(t -> t.source().equals(c.source())))
                            .map(c -> new CriterionReview(c.source(), sources(row.id(), c.evidence()), c.gap())).toList();
                    scoped.add(new RequirementReview(row.id(), List.of(), "", children));
                }
            }
            var verified = RequirementCoverage.verify(requirements, authority.scopedReviews(scoped, local), facts, blocked);
            var c = verified.counts(); int[] values = {c.provided(),c.accepted(),c.vetoed(),c.missingFacts(),c.quoteMismatch(),c.unknownRequirement(),c.unknownCriterion()};
            for (int i = 0; i < totals.length; i++) totals[i] += values[i];
            verified.checks().forEach((key, next) -> checks.merge(key, next, ReviewEvidenceLedger::mergeCheck));
            for (var update : verified.reviews()) merged.merge(update.id(), update, ReviewEvidenceLedger::mergeReview);
        }
        return new RequirementCoverage.Verification(List.copyOf(merged.values()), new RequirementCoverage.VerificationCounts(
                totals[0],totals[1],totals[2],totals[3],totals[4],totals[5],totals[6]), Map.copyOf(checks));
    }
    private List<RequirementReview.Contribution> sources(String target, List<RequirementReview.Contribution> input) {
        input.stream().filter(e -> !evidenceSources.contains(e.slug())).forEach(e -> issue(new AgentContractCodec.FragmentIssue(
                "/requirements/" + target + "/evidence/" + e.slug(), "OUTSIDE_EVIDENCE_SOURCES")));
        return input.stream().filter(e -> evidenceSources.contains(e.slug())).toList();
    }
    /** 仅合并已经通过来源与原文核对的响应，空补证不能撤销先前证据。 */
    public static List<RequirementReview> mergeVerifiedReviews(List<RequirementReview> prior, List<RequirementReview> updates) {
        var merged = new LinkedHashMap<String, RequirementReview>();
        prior.forEach(r -> merged.put(r.id(), r));
        updates.forEach(r -> merged.merge(r.id(), r, ReviewEvidenceLedger::mergeReview));
        return List.copyOf(merged.values());
    }
    public static Map<String, RequirementCoverage.EvidenceCheck> mergeVerifiedChecks(
            Map<String, RequirementCoverage.EvidenceCheck> prior, Map<String, RequirementCoverage.EvidenceCheck> updates) {
        var merged = new LinkedHashMap<>(prior);
        updates.forEach((key, check) -> merged.merge(key, check, ReviewEvidenceLedger::mergeCheck));
        return Map.copyOf(merged);
    }
    private static RequirementReview mergeReview(RequirementReview old, RequirementReview next) {
        var children = new LinkedHashMap<String, CriterionReview>(); old.criteria().forEach(c -> children.put(c.source(), c));
        next.criteria().forEach(c -> children.merge(c.source(), c, (a,b) -> new CriterionReview(a.source(),
                union(a.evidence(), b.evidence(), CriterionReview.MAX_EVIDENCE), b.gap())));
        return new RequirementReview(old.id(), union(old.evidence(), next.evidence(), RequirementReview.MAX_EVIDENCE),
                next.gap(), List.copyOf(children.values()));
    }
    private static List<RequirementReview.Contribution> union(List<RequirementReview.Contribution> a,
                                                              List<RequirementReview.Contribution> b, int limit) {
        var values = new LinkedHashMap<String, RequirementReview.Contribution>();
        a.forEach(e -> values.putIfAbsent(e.slug(), e)); b.forEach(e -> values.putIfAbsent(e.slug(), e));
        return values.values().stream().limit(limit).toList();
    }
    private static RequirementCoverage.EvidenceCheck mergeCheck(RequirementCoverage.EvidenceCheck a, RequirementCoverage.EvidenceCheck b) {
        var generation = new ArrayList<>(a.generation()); generation.addAll(b.generation());
        var proofs = new ArrayList<>(a.proofs()); proofs.addAll(b.proofs());
        String reason = a.accepted() + b.accepted() > 0 ? "VERIFIED" : b.reason().equals("NO_EVIDENCE") ? a.reason() : b.reason();
        return new RequirementCoverage.EvidenceCheck(a.provided()+b.provided(), a.accepted()+b.accepted(), reason,
                generation.stream().distinct().toList(), proofs.stream().distinct().toList());
    }
}
