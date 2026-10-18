package yagen.waitmydawn.maa.runtime;

import org.springframework.core.env.Environment;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** 使用已解析的运行环境判断评测权限，包括额外激活及分组展开的 profile。 */
public final class EvaluationAccess {
    private EvaluationAccess() {}
    public static boolean allowed(Environment environment) {
        if (environment == null) return false;
        Set<String> profiles = Arrays.stream(environment.getActiveProfiles()).map(String::trim)
                .map(p -> p.toLowerCase(java.util.Locale.ROOT)).collect(Collectors.toSet());
        return profiles.contains("eval") && !profiles.contains("prod") && !profiles.contains("production");
    }
}
