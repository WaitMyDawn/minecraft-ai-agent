package yagen.waitmydawn.maa.model;

import yagen.waitmydawn.maa.model.agent.BuildRequirement;
import java.util.*;

/** 从目标关系派生执行阶段，不按模组名称或玩法类别固定工作流。 */
public final class RequirementExecution {
    public enum Phase { SUBJECT_CONTENT, CORE_ADDONS, PACK_RELATIONS }
    public record Target(String id, Phase phase, List<String> cores, List<String> dependsOn, String scope) {}
    public record Relation(String slug, String versionId, String core, List<String> partners, String status) {}
    public record Report(List<Target> targets, Map<String,String> evaluatedVersions, Map<String,String> currentVersions,
                         List<Relation> relations, String status) {}
    private final List<Target> targets;
    public RequirementExecution(List<BuildRequirement> requirements) {
        var flat=RequirementQuantities.targets(requirements);
        targets=flat.stream().map(r -> {
            var phase=r.kind()==BuildRequirement.Kind.PACK_INTEGRATIONS ? Phase.PACK_RELATIONS
                    : r.kind()==BuildRequirement.Kind.ADDONS ? Phase.CORE_ADDONS : Phase.SUBJECT_CONTENT;
            var dependencies=new TreeSet<String>();
            flat.stream().filter(other -> !other.id().equals(r.id()))
                    .filter(other -> phase==Phase.PACK_RELATIONS && other.kind()!=BuildRequirement.Kind.PACK_INTEGRATIONS
                            || other.kind()!=BuildRequirement.Kind.PACK_INTEGRATIONS && other.kind()!=BuildRequirement.Kind.ADDONS
                                && !Collections.disjoint(other.projects(),r.addonCores()))
                    .map(BuildRequirement::id).forEach(dependencies::add);
            return new Target(r.id(),phase,List.copyOf(r.addonCores()),List.copyOf(dependencies),
                    phase==Phase.PACK_RELATIONS ? "RETAINED_SUBJECT_VERSIONS" : phase==Phase.CORE_ADDONS ? "CORE_VERSION" : "ENVIRONMENT");
        }).toList();
    }
    public List<Target> targets() { return targets; }
    public Set<String> relationCores() {
        var result=new LinkedHashSet<String>();targets.stream().filter(t -> t.phase()==Phase.PACK_RELATIONS).forEach(t -> result.addAll(t.cores()));
        return Set.copyOf(result);
    }
    public boolean earlyCore(String core) {
        return !relationCores().contains(core) || targets.stream().anyMatch(t -> t.phase()==Phase.CORE_ADDONS && t.cores().contains(core));
    }
    public boolean deferred(Set<String> cores, boolean integration) {
        return !Collections.disjoint(cores,relationCores()) && (integration || cores.stream().noneMatch(this::earlyCore));
    }
    public int reserve(int available, boolean otherContent) {
        if (relationCores().isEmpty() || available<=0) return 0;
        return Math.min(otherContent ? available/3 : available,Math.min(8,Math.max(1,relationCores().size()*2)));
    }
    public Report report(Map<String,String> scope, List<Relation> relations, String status) {
        return new Report(targets,Collections.unmodifiableMap(new TreeMap<>(scope)),Collections.unmodifiableMap(new TreeMap<>(scope)),List.copyOf(relations),status);
    }
    public static Report afterSelection(Report report, AssemblySnapshot selected) {
        var relations=report.relations().stream().map(r -> new Relation(r.slug(),r.versionId(),r.core(),r.partners(),
                !selected.versions().containsKey(r.slug()) ? "NOT_RETAINED"
                    : !selected.versions().containsKey(r.core()) || !selected.versions().keySet().containsAll(r.partners()) ? "PARTNER_NOT_RETAINED"
                    : !Objects.equals(r.versionId(),selected.versions().get(r.slug())) || !Objects.equals(report.evaluatedVersions().get(r.core()),selected.versions().get(r.core()))
                        || r.partners().stream().anyMatch(p -> !Objects.equals(report.evaluatedVersions().get(p),selected.versions().get(p)))
                            ? "VERSION_RECHECK_REQUIRED" : r.status())).toList();
        var current=new TreeMap<String,String>();report.evaluatedVersions().keySet().forEach(s -> {if(selected.versions().containsKey(s)) current.put(s,selected.versions().get(s));});
        return new Report(report.targets(),report.evaluatedVersions(),Collections.unmodifiableMap(current),relations,"RECHECKED_AFTER_SELECTION");
    }
}
