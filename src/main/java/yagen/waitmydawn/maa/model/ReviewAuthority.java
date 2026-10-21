package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.regex.Pattern;

/** 否决与补位权限的请求内事实边界；局部落选不产生全包禁入权限。 */
public final class ReviewAuthority {
    private final Set<String> candidates, sources, targets;
    private final Map<String, String> facts;
    private final String request;
    private final List<BuildRequirement> requirements;
    private final boolean userBackfillForbidden;
    private final int expectedVersion;
    private static final Pattern NO_FILL = Pattern.compile(
            "(?:请|please\\s+)?(?:(?:不要|禁止|不允许|不需要|无需)(?:再|继续)?(?:自动|额外|自行)?"
                    + "(?:补位|(?:补充|添加|加入)(?:任何|额外|其他|其它|更多)?模组)|"
                    + "(?:仅|只)(?:添加|加入|保留|使用)(?:点名|指定|列出)(?:的)?模组|"
                    + "(?:no|without)\\s+(?:any\\s+)?(?:extra|additional|automatic)\\s+(?:mods|backfill|additions)|"
                    + "(?:do not|don't|never)\\s+(?:automatically\\s+)?(?:backfill|add (?:extra|additional) mods)|"
                    + "only\\s+(?:the\\s+)?(?:named|specified|listed)\\s+mods)(?:\\s*please|谢谢)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONFLICT = Pattern.compile(
            "\\b(?:incompatible with|not compatible with|conflicts? with|cannot be used with|does not work with)\\b|不兼容|不能.{0,6}(?:同时使用|共存)|与.{0,40}冲突", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROHIBITION = Pattern.compile("不要|禁止|不允许|不加入|排除|不使用|\\b(?:exclude|avoid|without|never|do not|don't|no)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SOFT_OR_QUANTITY = Pattern.compile("尽量|最好|优先|偏好|如果可以|至少|最多|不超过|不要超过|配额|数量|"
            + "\\d+\\s*(?:-|~|～|至|到)\\s*\\d+|\\b(?:prefer|ideally|if possible|at least|at most|quota|count)\\b", Pattern.CASE_INSENSITIVE);

    public record Result(Set<String> globalVetoes, Map<String, Set<String>> localVetoes, Set<String> pendingVetoes,
                         boolean backfillForbidden, boolean backfillUnverified, List<AgentContractCodec.FragmentIssue> issues) {}

    public ReviewAuthority(List<BuildRequirement> requirements, Set<String> candidates, Set<String> sources,
                           Map<String, String> facts, String request) {
        this(requirements, candidates, sources, facts, request, 0);
    }
    public ReviewAuthority(List<BuildRequirement> requirements, Set<String> candidates, Set<String> sources,
                           Map<String, String> facts, String request, int expectedVersion) {
        this.requirements = List.copyOf(requirements); this.candidates = Set.copyOf(candidates);
        this.sources = Set.copyOf(sources); this.facts = Map.copyOf(facts); this.request = Objects.requireNonNullElse(request, "");
        this.targets = targetSources(requirements).keySet();
        this.expectedVersion = expectedVersion;
        this.userBackfillForbidden = Arrays.stream(this.request.split("[,，;；.!?。！？\\n]+"))
                .map(ReviewAuthority::clauseKey).anyMatch(clause -> NO_FILL.matcher(clause).matches());
    }
    public boolean userBackfillForbidden() { return userBackfillForbidden; }
    public int expectedVersion() { return expectedVersion; }
    public static Map<String, String> targetSources(List<BuildRequirement> requirements) {
        var result = new LinkedHashMap<String, String>();
        requirements.forEach(r -> { if (r.criteria().isEmpty()) result.put(r.id(), r.source());
            else r.criteria().forEach(c -> result.put(c.key(r.id()), c.source())); });
        return Collections.unmodifiableMap(result);
    }

    public Result inspect(int version, Collection<ReviewDecision.Rejection> rejections, Set<String> approvals,
                          boolean allowBackfill, ReviewDecision.Backfill backfill) {
        if (expectedVersion != 0 && version != expectedVersion) return new Result(Set.of(), Map.of(),
                rejections.stream().map(ReviewDecision.Rejection::slug).filter(candidates::contains).collect(java.util.stream.Collectors.toUnmodifiableSet()),
                userBackfillForbidden, true, List.of(new AgentContractCodec.FragmentIssue("/schemaVersion", "REVIEW_SCHEMA_VERSION_MISMATCH")));
        var globals = new LinkedHashSet<String>(); var pending = new LinkedHashSet<String>();
        var locals = new LinkedHashMap<String, Set<String>>(); var issues = new ArrayList<AgentContractCodec.FragmentIssue>();
        var selected = new HashSet<>(approvals);
        rejections.stream().filter(ReviewDecision.Rejection::global).map(ReviewDecision.Rejection::slug).forEach(selected::remove);
        int i = 0;
        for (var rejection : rejections) {
            String issue = rejectionIssue(version, rejection, selected);
            if (!issue.isEmpty()) {
                if (candidates.contains(rejection.slug())) pending.add(rejection.slug());
                issues.add(new AgentContractCodec.FragmentIssue("/rejected/" + i + "/authority", issue));
            } else if (rejection.global()) globals.add(rejection.slug());
            else locals.computeIfAbsent(rejection.authority().targetId(), ignored -> new LinkedHashSet<>()).add(rejection.slug());
            i++;
        }
        String backfillIssue = backfillIssue(version, allowBackfill, backfill, selected, globals);
        if (!backfillIssue.isEmpty()) issues.add(new AgentContractCodec.FragmentIssue("/backfill", backfillIssue));
        var frozen = new LinkedHashMap<String, Set<String>>(); locals.forEach((key, value) -> frozen.put(key, Set.copyOf(value)));
        return new Result(Set.copyOf(globals), Map.copyOf(frozen), Set.copyOf(pending),
                userBackfillForbidden || (!allowBackfill && backfillIssue.isEmpty()), !backfillIssue.isEmpty(), List.copyOf(issues));
    }

    private String rejectionIssue(int version, ReviewDecision.Rejection r, Set<String> approvals) {
        if (!candidates.contains(r.slug())) return "OUTSIDE_CANDIDATES";
        var a = r.authority();
        if (version == 1) return a.equals(ReviewDecision.Authority.LEGACY) ? "" : "LEGACY_SCOPE_MISMATCH";
        if (a.scope() == ReviewDecision.Scope.LEGACY) return "MISSING_REJECTION_AUTHORITY";
        if (!quoted(facts.get(r.slug()), a.quote(), 4)) return "REJECTION_QUOTE_UNVERIFIED";
        if (a.scope() == ReviewDecision.Scope.GOAL) {
            if (!targets.contains(a.targetId())) return "UNKNOWN_REJECTION_TARGET";
            if (!a.requestQuote().isEmpty() || !a.relatedSlug().isEmpty()) return "UNEXPECTED_LOCAL_AUTHORITY";
            return "";
        }
        if (!a.targetId().isEmpty()) return "PACK_REJECTION_HAS_TARGET";
        if (r.reason() == ReviewDecision.Reason.CONFLICT) {
            if (a.relatedSlug().equals(r.slug()) || !sources.contains(a.relatedSlug())
                    || (candidates.contains(a.relatedSlug()) && !approvals.contains(a.relatedSlug()))) return "CONFLICT_TARGET_NOT_SELECTED";
            if (!CONFLICT.matcher(a.quote()).find() || !mentions(a.quote(), a.relatedSlug())
                    || !ProjectBehavior.owns(facts.get(r.slug()), a.quote())) return "CONFLICT_RELATION_UNVERIFIED";
            if (!a.requestQuote().isEmpty()) return "UNEXPECTED_CONFLICT_REQUEST";
        } else if (r.reason() == ReviewDecision.Reason.CONSTRAINT) {
            if (!quoted(request, a.requestQuote(), 4)) return "REQUEST_CONSTRAINT_UNVERIFIED";
            if (!Arrays.stream(request.split("[,，;；.!?。！？\\n]+"))
                    .map(ReviewAuthority::clauseKey).anyMatch(clause -> clause.equals(clauseKey(a.requestQuote()))
                            && PROHIBITION.matcher(clause).find() && !SOFT_OR_QUANTITY.matcher(clause).find())) return "REQUEST_NOT_GLOBAL_PROHIBITION";
            if (!ProjectBehavior.owns(facts.get(r.slug()), a.quote())) return "REJECTION_NOT_OWN_BEHAVIOR";
            if (!a.relatedSlug().isEmpty()) return "UNEXPECTED_RELATED_PROJECT";
        } else return "REASON_REQUIRES_LOCAL_SCOPE";
        return "";
    }
    private String backfillIssue(int version, boolean allowed, ReviewDecision.Backfill proof, Set<String> approvals, Set<String> globals) {
        if (userBackfillForbidden && allowed) return "USER_BACKFILL_OPT_OUT_MUST_BE_PRESERVED";
        if (proof == null) return "MISSING_BACKFILL_AUTHORITY";
        if (version == 1) return proof.equals(ReviewDecision.Backfill.LEGACY) ? "" : "LEGACY_BACKFILL_MISMATCH";
        if (userBackfillForbidden && proof.basis() != ReviewDecision.BackfillBasis.USER_REQUEST) return "USER_BACKFILL_OPT_OUT_SOURCE_REQUIRED";
        if (allowed) return proof.basis() == ReviewDecision.BackfillBasis.ALLOW && proof.quote().isEmpty() ? "" : "BACKFILL_FLAG_MISMATCH";
        return switch (proof.basis()) {
            case USER_REQUEST -> quoted(request, proof.quote(), 4) && NO_FILL.matcher(clauseKey(proof.quote())).matches()
                    && Arrays.stream(request.split("[,，;；.!?。！？\\n]+"))
                        .anyMatch(clause -> clauseKey(clause).equals(clauseKey(proof.quote()))) ? "" : "USER_BACKFILL_REQUEST_UNVERIFIED";
            case POOL_EXHAUSTED -> proof.quote().isEmpty() && candidates.stream().allMatch(s -> approvals.contains(s) || globals.contains(s))
                    ? "" : "BACKFILL_POOL_NOT_EXHAUSTED";
            default -> "BACKFILL_FLAG_MISMATCH";
        };
    }
    private static boolean mentions(String text, String slug) {
        return Pattern.compile("(?<![a-z0-9_-])" + Pattern.quote(slug) + "(?![a-z0-9_-])", Pattern.CASE_INSENSITIVE).matcher(text).find();
    }
    private static String clauseKey(String text) {
        return normalized(text).replaceAll("[,，;；.!?。！？]+$", "")
                .replaceFirst("^(?:[-*•]\\s*|\\d+[)）.、]\\s*)", "");
    }
    private static String normalized(String text) { return text.replaceAll("(?U)\\s+", " ").trim(); }
    private static boolean quoted(String text, String quote, int min) {
        return text != null && quote != null && normalized(quote).replace(" ", "").length() >= min
                && normalized(text).contains(normalized(quote));
    }

    /** 只将已核对的全包否决交给既有执行器，局部范围留在审核账本中。 */
    public ReviewResponse checked(ReviewResponse response) {
        var result = inspect(response.schemaVersion(), response.rejected(), Set.copyOf(response.approved()), response.allowBackfill(), response.backfill());
        if (!result.issues().isEmpty()) throw new IllegalArgumentException("审核权限依据无效：" + result.issues().stream().limit(8)
                .map(i -> i.pointer() + "=" + i.code()).reduce((a,b) -> a + ";" + b).orElse("UNKNOWN")
                + "；只修正有误的作用域或事实依据，不能以局部不适用、配额已满代替全包否决/禁止补位");
        var global = response.rejected().stream().filter(ReviewDecision.Rejection::global)
                .map(r -> new ReviewDecision.Rejection(r.slug(), r.reason(), r.explanation())).toList();
        return new ReviewResponse(1, response.approved(), global, response.allowBackfill(),
                scopedReviews(response.requirements(), result.localVetoes()));
    }

    public List<RequirementReview> scopedReviews(List<RequirementReview> reviews, Map<String, Set<String>> locals) {
        var byId = new HashMap<String, BuildRequirement>(); requirements.forEach(r -> byId.put(r.id(), r));
        return reviews.stream().map(r -> {
            var parent = byId.get(r.id());
            var children = r.criteria().stream().map(c -> {
                var target = parent == null ? Optional.<RequirementCriterion>empty()
                        : parent.criteria().stream().filter(t -> t.source().equals(c.source())).findFirst();
                String key = target.map(t -> t.key(r.id())).orElse("");
                return new CriterionReview(c.source(), filter(c.evidence(), locals.getOrDefault(key, Set.of())), c.gap());
            }).toList();
            return new RequirementReview(r.id(), filter(r.evidence(), locals.getOrDefault(r.id(), Set.of())), r.gap(), children);
        }).toList();
    }
    private static List<RequirementReview.Contribution> filter(List<RequirementReview.Contribution> evidence, Set<String> blocked) {
        return evidence.stream().filter(e -> !blocked.contains(e.slug())).toList();
    }
}
