package yagen.waitmydawn.maa.model.agent;

import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** Java 提供的审核权限与静态可用性事实；候选资格和最终内容缺口分别判断。 */
public record ReviewInputScope(Mode mode, BuildIntent.Environment environment,
                               List<String> compatibleCandidates, List<String> retainedProjects) {
    public enum Mode { SELECT_AND_REVIEW, REVIEW_MISSING_CONTENT }
    public ReviewInputScope {
        ContractRules.required(mode, "reviewScope.mode");
        ContractRules.required(environment, "reviewScope.environment");
        compatibleCandidates = ContractRules.projects(compatibleCandidates, "reviewScope.compatibleCandidates");
        retainedProjects = ContractRules.projects(retainedProjects, "reviewScope.retainedProjects");
    }
    public static ReviewInputScope of(Mode mode, String loader, String mcVersion,
                                      Collection<String> candidates, Collection<String> retained) {
        return new ReviewInputScope(mode, new BuildIntent.Environment(mcVersion,
                BuildIntent.Loader.valueOf(loader.toUpperCase(Locale.ROOT)), false),
                candidates.stream().sorted().toList(), retained.stream().sorted().toList());
    }
}
