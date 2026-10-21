package yagen.waitmydawn.maa.model;

import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.service.ModrinthIdentifiers;
import java.util.*;

/** 可持久化的导入锁定信息；文件须在每次预览中按精确版本重新核对。 */
public record ImportedPack(String loader, String mcVersion, String loaderVersion, List<File> files) {
    public record File(String slug, String projectId, String versionId, String filename, String url,
                       String sha1, String sha512, long size, Map<String, String> env) {
        public File {
            if (!ModrinthIdentifiers.isProjectReference(slug) || !identifier(projectId) || !identifier(versionId)
                    || filename == null || filename.isBlank() || filename.length() > 255
                    || filename.chars().anyMatch(Character::isISOControl)
                    || filename.contains("..") || filename.matches(".*[/\\\\:].*")
                    || sha1 == null || !sha1.matches("[a-fA-F0-9]{40}")
                    || sha512 == null || !sha512.matches("[a-fA-F0-9]{128}") || size < 0)
                throw new IllegalArgumentException("导入文件信息无效");
            try {
                var uri = java.net.URI.create(url);
                if (!"https".equals(uri.getScheme()) || !"cdn.modrinth.com".equals(uri.getHost())
                        || uri.getUserInfo() != null || uri.getPort() != -1 || uri.getQuery() != null || uri.getFragment() != null
                        || !uri.getPath().startsWith("/data/" + projectId + "/versions/" + versionId + "/"))
                    throw new IllegalArgumentException("导入文件来源无效");
            } catch (NullPointerException invalid) { throw new IllegalArgumentException("导入文件来源无效"); }
            env = env == null ? Map.of() : Map.copyOf(env);
            if (env.entrySet().stream().anyMatch(e -> !Set.of("client", "server").contains(e.getKey())
                    || !Set.of("required", "optional", "unsupported").contains(e.getValue())))
                throw new IllegalArgumentException("导入文件环境无效");
        }
        public boolean matches(JsonNode metadata) {
            return filename.equals(metadata.path("filename").asText()) && url.equals(metadata.path("url").asText())
                    && sha1.equalsIgnoreCase(metadata.path("hashes").path("sha1").asText())
                    && sha512.equalsIgnoreCase(metadata.path("hashes").path("sha512").asText())
                    && size == metadata.path("size").asLong(-1);
        }
    }
    private static boolean identifier(String value) {
        return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
    }
    public ImportedPack {
        if (!Set.of("fabric", "forge", "neoforge").contains(loader == null ? "" : loader)
                || mcVersion == null || !mcVersion.matches("[A-Za-z0-9._-]{1,64}")
                || loaderVersion == null || !loaderVersion.matches("[A-Za-z0-9.+_-]{1,64}")
                || files == null || files.size() > 500)
            throw new IllegalArgumentException("导入包环境或文件数量无效");
        files = List.copyOf(files);
        if (files.stream().map(File::slug).distinct().count() != files.size()
                || files.stream().map(File::projectId).distinct().count() != files.size()
                || files.stream().map(f -> f.filename().toLowerCase(Locale.ROOT)).distinct().count() != files.size())
            throw new IllegalArgumentException("同一项目或文件不能重复导入");
    }
    public void requireEnvironment(String loader, String mc) {
        if (!this.loader.equals(loader) || !mcVersion.equals(mc))
            throw new IllegalArgumentException("导入版本锁定与当前环境不同，请明确切换环境后重新选版");
    }
    public Set<String> seeds(Set<String> slugs) {
        Map<String, String> pins = new HashMap<>();
        files.forEach(f -> pins.put(f.slug(), "vid:" + f.versionId()));
        var result = new LinkedHashSet<String>();
        slugs.forEach(slug -> result.add(pins.getOrDefault(slug, slug)));
        return result;
    }
    public Map<String, String> dependencies() {
        return Map.of("minecraft", mcVersion, "fabric".equals(loader) ? "fabric-loader" : loader, loaderVersion);
    }
}
