package yagen.waitmydawn.maa.model;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖图谱 DAG 数据结构
 * - allSlugs: 所有已解析的模组 slug
 * - dependentsOf: slug → 依赖它的模组集合 (反向边, 用于孤儿子查询)
 * - depsOf: slug → 它依赖的模组集合 (正向边)
 */
public class DependencyGraph {

    public Set<String> allSlugs;
    public Map<String, Set<String>> dependentsOf;
    public Map<String, Set<String>> depsOf;

    public DependencyGraph() {
        this.allSlugs = ConcurrentHashMap.newKeySet();
        this.dependentsOf = new ConcurrentHashMap<>();
        this.depsOf = new ConcurrentHashMap<>();
    }

    public void addEdge(String dependent, String dependency) {
        dependentsOf.computeIfAbsent(dependency, k -> ConcurrentHashMap.newKeySet()).add(dependent);
        depsOf.computeIfAbsent(dependent, k -> ConcurrentHashMap.newKeySet()).add(dependency);
    }

    /** 返回 slug 在图中直接依赖的所有模组 */
    public Set<String> getDependenciesOf(String slug) {
        return depsOf.getOrDefault(slug, Set.of());
    }

    /** 返回依赖 slug 的所有模组 */
    public Set<String> getDependentsOf(String slug) {
        return dependentsOf.getOrDefault(slug, Set.of());
    }

    /** 深拷贝 allSlugs 为有序集合 */
    public Set<String> getOrderedSlugs() {
        return new LinkedHashSet<>(allSlugs);
    }
}
