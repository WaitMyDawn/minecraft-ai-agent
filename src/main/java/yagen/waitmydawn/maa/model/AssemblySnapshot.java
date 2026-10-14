package yagen.waitmydawn.maa.model;

import java.util.*;

/** 构建选版与来源快照；预览严格复现选版，编辑将选版作为可协调的偏好。 */
public record AssemblySnapshot(String loader, String mcVersion, List<String> roots,
                               Map<String, String> versions, Map<String, Set<String>> origins, boolean rootsKnown) {
    public AssemblySnapshot(String loader, String mcVersion, List<String> roots, Map<String, String> versions, Map<String, Set<String>> origins) {
        this(loader, mcVersion, roots, versions, origins, true);
    }
    private static final Set<String> ROLES = Set.of("CORE", "EXPLICIT_ADD", "CONTENT", "ADDON", "REQUIRED_INTEGRATION", "DEPENDENCY", "RETAINED", "IMPORTED");
    public AssemblySnapshot {
        if (loader == null || !Set.of("fabric", "forge", "neoforge").contains(loader) || mcVersion == null || !mcVersion.matches("[A-Za-z0-9._-]{1,64}")
                || roots == null || versions == null || origins == null || roots.size() > 500 || versions.size() > 500 || origins.size() > 500)
            throw new IllegalArgumentException("构建快照无效");
        if (roots.stream().anyMatch(s -> !project(s)) || versions.entrySet().stream().anyMatch(e -> !project(e.getKey()) || !identifier(e.getValue())))
            throw new IllegalArgumentException("构建标识无效");
        roots = roots.stream().distinct().sorted().toList();
        var normalized = new TreeMap<String, Set<String>>();
        for (var entry : origins.entrySet()) {
            if (!project(entry.getKey()) || entry.getValue() == null || entry.getValue().stream().anyMatch(Objects::isNull)
                    || !ROLES.containsAll(entry.getValue())) throw new IllegalArgumentException("构建来源无效");
            normalized.put(entry.getKey(), Collections.unmodifiableSet(new TreeSet<>(entry.getValue())));
        }
        versions = Collections.unmodifiableMap(new TreeMap<>(versions));
        origins = Collections.unmodifiableMap(normalized);
    }
    private static boolean identifier(String s) { return s != null && s.matches("[A-Za-z0-9_-]{1,128}"); }
    private static boolean project(String s) { return yagen.waitmydawn.maa.service.ModrinthIdentifiers.isProjectReference(s); }
    public boolean matches(String loader, String mc) { return this.loader.equalsIgnoreCase(loader) && mcVersion.equals(mc); }
    public Set<String> rootsIn(Set<String> files) {
        var selected = new LinkedHashSet<String>();
        roots.stream().filter(files::contains).forEach(selected::add);
        files.stream().filter(s -> !versions.containsKey(s) && !origins.containsKey(s)).forEach(selected::add);
        return selected;
    }
    public Set<String> seeds(Set<String> files) {
        var result = new LinkedHashSet<String>();
        files.forEach(s -> result.add(versions.containsKey(s) ? "vid:" + versions.get(s) : s));
        return result;
    }
}
