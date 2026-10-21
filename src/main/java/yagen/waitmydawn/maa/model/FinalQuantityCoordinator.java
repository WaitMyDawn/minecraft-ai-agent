package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.BuildIntent;
import java.util.*;
import java.util.function.*;

/** 使用最终覆盖账本协调根集合；检索提示只影响候选顺序，不参与完成计数。 */
public final class FinalQuantityCoordinator {
    private FinalQuantityCoordinator() {}
    public static final int MAX_TRIALS = 12, MAX_EVALUATIONS = 512, MAX_CANDIDATES = 96;
    public static int minimum(BuildIntent.CountTarget target) {
        return target==null ? 0 : target.range() || target.precision()==BuildIntent.Precision.EXACT
                ? target.min() : (int)Math.ceil(target.value()*.8);
    }
    public static int maximum(BuildIntent.CountTarget target) {
        return target==null ? Integer.MAX_VALUE : target.max() +
                (target.scope()==BuildIntent.CountScope.TOTAL_FILES && target.precision()==BuildIntent.Precision.APPROXIMATE
                        ? Math.min(10,(int)Math.ceil(target.max()*.1)) : 0);
    }
    public record Decision(List<String> added, List<String> removed, List<String> rejected, String reason) {}
    public record Report(String status, int trials, int evaluations, int actual, int minimum, int maximum,
                         Map<String, RequirementCoverage.Quantity> targets, List<Decision> decisions, List<String> gaps) {}
    public record Result(ResolutionResult resolution, Set<String> roots, Report report) {}
    public static Result combine(Result prepared, Result settled) {
        var first=prepared.report(); var last=settled.report(); var changes=new ArrayList<>(first.decisions()); changes.addAll(last.decisions());
        String status=last.status().equals("PARTIAL") && first.status().equals("BUDGET_EXHAUSTED") ? "BUDGET_EXHAUSTED" : last.status();
        return new Result(settled.resolution(),settled.roots(),new Report(status,first.trials()+last.trials(),first.evaluations()+last.evaluations(),
                last.actual(),last.minimum(),last.maximum(),last.targets(),List.copyOf(changes),last.gaps()));
    }
    @FunctionalInterface public interface Resolver {
        ResolutionResult resolve(ResolutionResult baseline, Set<String> roots);
    }
    private record State(ResolutionResult resolution, Set<String> roots, RequirementCoverage.Report coverage) {}

    public static Result coordinate(ResolutionResult baseline, Set<String> roots, Set<String> protectedRoots,
            Set<String> blocked, BuildIntent.CountTarget target, Set<String> original, boolean rootsKnown,
            List<String> removalOrder, List<String> candidates, Map<String, Set<String>> candidateHints,
            BiFunction<ResolutionResult, Set<String>, RequirementCoverage.Report> assess,
            BiPredicate<ResolutionResult, Set<String>> admissible, Resolver resolver) {
        return new Run(target, original, protectedRoots, blocked, removalOrder, assess, admissible, resolver)
                .execute(baseline, roots, rootsKnown, candidates, candidateHints);
    }

    private static final class Run {
        final BuildIntent.CountTarget target;
        final Set<String> original, protectedRoots, blocked;
        final List<String> removalOrder;
        final BiFunction<ResolutionResult, Set<String>, RequirementCoverage.Report> assess;
        final BiPredicate<ResolutionResult, Set<String>> admissible;
        final Resolver resolver;
        final List<Decision> decisions = new ArrayList<>();
        int evaluations, trials;
        Run(BuildIntent.CountTarget target, Set<String> original, Set<String> protectedRoots, Set<String> blocked,
                List<String> removalOrder, BiFunction<ResolutionResult, Set<String>, RequirementCoverage.Report> assess,
                BiPredicate<ResolutionResult, Set<String>> admissible, Resolver resolver) {
            this.target=target; this.original=Set.copyOf(original); this.protectedRoots=Set.copyOf(protectedRoots);
            this.blocked=Set.copyOf(blocked); this.removalOrder=List.copyOf(removalOrder);
            this.assess=assess; this.admissible=admissible; this.resolver=resolver;
        }
        State state(ResolutionResult graph, Set<String> roots) {
            evaluations++;
            return new State(graph, Set.copyOf(roots), assess.apply(graph, roots));
        }
        Result execute(ResolutionResult baseline, Set<String> roots, boolean known, List<String> candidates,
                       Map<String, Set<String>> hints) {
            var current=state(baseline, roots);
            if (!known || !baseline.graph().allSlugs.containsAll(roots)) return finish(current,"UNKNOWN_ROOT_IDENTITY");
            if (!SelectionRecovery.complete(baseline, roots)) return finish(current,"STATIC_CHECK_INCOMPLETE");
            current=trim(current, current, false);
            var pending=new ArrayList<>(candidates.stream().distinct().filter(s -> !blocked.contains(s))
                    .filter(s -> !baseline.graph().allSlugs.contains(s)).limit(MAX_CANDIDATES).toList());
            int batchLimit=8;
            while (needsMore(current) && !pending.isEmpty() && trials<MAX_TRIALS && evaluations<MAX_EVALUATIONS) {
                var quantities=quantities(current.coverage());
                if(actual(current)>=minimum()) {
                    pending.removeIf(s -> hints.getOrDefault(s,Set.of()).stream().map(quantities::get).filter(Objects::nonNull)
                            .noneMatch(q -> q.actual()<q.min()));
                    if(pending.isEmpty()) break;
                }
                pending.sort(Comparator.comparingInt((String s) -> -hints.getOrDefault(s,Set.of()).stream()
                        .map(quantities::get).filter(Objects::nonNull).mapToInt(q -> Math.max(0,q.min()-q.actual())).sum()));
                int shortage=Math.max(0,minimum()-actual(current));
                for (var q : quantities.values()) shortage=Math.max(shortage,q.min()-q.actual());
                var additions=List.copyOf(pending.subList(0,Math.min(pending.size(),Math.min(batchLimit,Math.max(1,shortage)))));
                var nextRoots=new LinkedHashSet<>(current.roots()); nextRoots.addAll(additions);
                ResolutionResult solved;
                trials++;
                try { solved=resolver.resolve(current.resolution(),Set.copyOf(nextRoots)); }
                catch (java.util.concurrent.CancellationException canceled) { throw canceled; }
                catch (RuntimeException unavailable) { solved=null; }
                String reason=rejection(current,solved,nextRoots);
                State trial=null;
                if (reason.isEmpty()) {
                    trial=state(solved,nextRoots);
                    // 新增候选的依赖膨胀可与可选旧内容交换，但不得牺牲原有目标下限和用途。
                    trial=trim(trial,current,true);
                    if (!safe(current,trial) || actual(trial)<Math.min(actual(current),minimum())
                            || !overagesPreserved(current,trial) || !improves(current,trial)) reason="QUANTITY_OR_COVERAGE_REJECTED";
                }
                if (reason.isEmpty()) {
                    var added=difference(trial.roots(),current.roots()); var removed=difference(current.roots(),trial.roots());
                    decisions.add(new Decision(added,removed,List.of(),"FINAL_QUANTITY_APPLIED"));
                    current=trial; pending.removeAll(additions); batchLimit=8;
                } else {
                    decisions.add(new Decision(List.of(),List.of(),additions,reason));
                    if(additions.size()>1) batchLimit=Math.max(1,additions.size()/2);
                    else { pending.removeAll(additions); batchLimit=8; }
                }
            }
            return finish(current,needsMore(current) || overflow(current)>0 ?
                    (trials>=MAX_TRIALS || evaluations>=MAX_EVALUATIONS ? "BUDGET_EXHAUSTED" : "PARTIAL") : "WITHIN_POLICY");
        }
        State trim(State current, State floor, boolean provisional) {
            boolean changed;
            do {
                changed=false;
                var order=new LinkedHashSet<>(removalOrder); order.addAll(new TreeSet<>(current.roots()));
                for (String slug : order) {
                    if (overflow(current)==0 || evaluations>=MAX_EVALUATIONS) break;
                    if (protectedRoots.contains(slug) || !current.roots().contains(slug)) continue;
                    if(actual(current)<=maximum() && quantities(current.coverage()).values().stream()
                            .noneMatch(q -> q.actual()>q.max() && q.projects().contains(slug))) continue;
                    var roots=new LinkedHashSet<>(current.roots()); roots.remove(slug);
                    var trial=state(PackQuantityPolicy.retain(current.resolution(),roots),roots);
                    if (overflow(trial)>=overflow(current) || !safe(floor,trial) || !admissible.test(trial.resolution(),trial.roots())) continue;
                    if (!provisional) decisions.add(new Decision(List.of(),List.of(slug),List.of(),"OPTIONAL_OVERAGE_REMOVED"));
                    current=trial; changed=true;
                }
            } while(changed && overflow(current)>0 && evaluations<MAX_EVALUATIONS);
            return current;
        }
        String rejection(State before, ResolutionResult trial, Set<String> roots) {
            if (trial==null) return "TRIAL_UNVERIFIED";
            if (!SelectionRecovery.complete(trial, roots)) return "DEPENDENCY_TRIAL_INCOMPLETE";
            if (!trial.graph().allSlugs.containsAll(roots) || !trial.graph().allSlugs.containsAll(before.resolution().graph().allSlugs))
                return "ROOT_OR_RETENTION_REJECTED";
            for (var e : before.resolution().graph().selectedVersions.entrySet()) {
                var v=trial.graph().selectedVersions.get(e.getKey());
                if (v==null || !v.path("id").asText().equals(e.getValue().path("id").asText())) return "VERSION_CHANGE_REJECTED";
            }
            if (trial.graph().allSlugs.stream().anyMatch(blocked::contains) || trial.graph().selectedVersions.values().stream()
                    .anyMatch(v -> blocked.contains(v.path("project_id").asText()))) return "EXCLUDED_PROJECT_REJECTED";
            return admissible.test(trial,roots) ? "" : "CANDIDATE_ADMISSION_REJECTED";
        }
        boolean safe(State before, State after) {
            if (!after.resolution().graph().allSlugs.containsAll(protectedRoots.stream()
                    .filter(before.resolution().graph().allSlugs::contains).toList())) return false;
            var next=quantities(after.coverage());
            for (var e : quantities(before.coverage()).entrySet()) {
                var q=next.get(e.getKey());
                if(q==null || q.actual()<Math.min(e.getValue().actual(),e.getValue().min())
                        || e.getValue().complete() && !q.complete()) return false;
            }
            // 未设数量的有效用途也不能因总量裁剪而全部消失；复合目标逐个子项比较。
            var good=goodTargets(before.coverage()); good.removeAll(goodTargets(after.coverage()));
            return good.isEmpty();
        }
        boolean overagesPreserved(State before, State after) {
            if(Math.max(0,actual(after)-maximum())>Math.max(0,actual(before)-maximum())) return false;
            var old=quantities(before.coverage());
            return quantities(after.coverage()).entrySet().stream().allMatch(e -> Math.max(0,e.getValue().actual()-e.getValue().max())
                    <= (old.containsKey(e.getKey()) ? Math.max(0,old.get(e.getKey()).actual()-old.get(e.getKey()).max()) : 0));
        }
        int actual(State s) {
            if (target==null || target.scope()==BuildIntent.CountScope.TOTAL_FILES) return s.resolution().graph().allSlugs.size();
            return (int)s.roots().stream().filter(p -> target.scope()!=BuildIntent.CountScope.ADDED_ROOTS || !original.contains(p)).count();
        }
        int minimum() { return FinalQuantityCoordinator.minimum(target); }
        int maximum() { return FinalQuantityCoordinator.maximum(target); }
        int overflow(State s) {
            return Math.max(0,actual(s)-maximum())+quantities(s.coverage()).values().stream().mapToInt(q -> Math.max(0,q.actual()-q.max())).sum();
        }
        int deficit(State s) { return quantities(s.coverage()).values().stream().mapToInt(q -> Math.max(0,q.min()-q.actual())).sum(); }
        boolean needsMore(State s) { return actual(s)<minimum() || deficit(s)>0; }
        boolean improves(State before, State after) {
            return deficit(after)<deficit(before) || deficit(after)<=deficit(before)
                    && Math.max(0,minimum()-actual(after))<Math.max(0,minimum()-actual(before));
        }
        Result finish(State s,String status) {
            var gaps=new ArrayList<String>();
            if(actual(s)<minimum()) gaps.add("GLOBAL_BELOW_MINIMUM");
            if(actual(s)>maximum()) gaps.add("PROTECTED_GLOBAL_OVERAGE");
            quantities(s.coverage()).forEach((id,q) -> {
                if(q.actual()<q.min()) gaps.add(id+":BELOW_MINIMUM");
                if(q.actual()>q.max()) gaps.add(id+":PROTECTED_OVERAGE");
                if(!q.complete()) gaps.add(id+":COUNT_EVIDENCE_INCOMPLETE");
            });
            if(status.equals("WITHIN_POLICY") && !gaps.isEmpty()) status="PARTIAL";
            return new Result(s.resolution(),s.roots(),new Report(status,trials,evaluations,actual(s),minimum(),
                    target==null ? -1 : maximum(),quantities(s.coverage()),List.copyOf(decisions),List.copyOf(gaps)));
        }
    }
    public static Map<String,RequirementCoverage.Quantity> quantities(RequirementCoverage.Report report) {
        var result=new LinkedHashMap<String,RequirementCoverage.Quantity>();
        if(report!=null) for(var row : report.requirements()) {
            if(row.quantity()!=null) result.put(row.id(),row.quantity());
            for(var child : row.criteria()) if(child.quantity()!=null) result.put(child.id(),child.quantity());
        }
        return Collections.unmodifiableMap(result);
    }
    private static Set<String> goodTargets(RequirementCoverage.Report report) {
        var result=new HashSet<String>();
        if(report!=null) for(var row : report.requirements()) {
            if(row.quantity()==null && row.criteria().isEmpty() && Set.of("SUPPORTING_EVIDENCE","VERIFIED_PROJECTS").contains(row.status())) result.add(row.id());
            for(var child : row.criteria()) if(child.quantity()==null && Set.of("SUPPORTING_EVIDENCE","VERIFIED_PROJECTS").contains(child.status())) result.add(child.id());
        }
        return result;
    }
    private static List<String> difference(Set<String> a,Set<String> b) { return a.stream().filter(s -> !b.contains(s)).sorted().toList(); }
}
