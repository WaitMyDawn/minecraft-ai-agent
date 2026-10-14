package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MrpackParser {

    private final ObjectMapper objectMapper;
    private final ModrinthApiClient apiClient;

    public MrpackParser(ModrinthApiClient apiClient) {
        this.objectMapper = new ObjectMapper();
        this.apiClient = apiClient;
    }

    /**
     * 在请求结束前读取有界索引，不进行外部网络访问。
     */
    public JsonNode readIndex(MultipartFile file) throws IOException {
        JsonNode index = null;
        long decompressed = 0;
        int entries = 0;

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (++entries > 1000) throw new IOException("整合包 ZIP 条目过多");
                if (!entry.isDirectory() && !"modrinth.index.json".equals(entry.getName()) && !"maa-diagnostics.json".equals(entry.getName()))
                    throw new IOException("该包含覆盖文件；本站仅组合 Modrinth 模组，不能无损导入覆盖内容");
                java.io.ByteArrayOutputStream content = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int read;
                while ((read = zis.read(buffer)) != -1) {
                    decompressed += read;
                    if (decompressed > 16 * 1024 * 1024L) throw new IOException("整合包解压数据超过 16MB 限额");
                    if ("modrinth.index.json".equals(entry.getName())) {
                        if (content.size() + read > 2 * 1024 * 1024) throw new IOException("整合包索引超过 2MB 限额");
                        content.write(buffer, 0, read);
                    }
                }
                if ("modrinth.index.json".equals(entry.getName())) {
                    byte[] bytes = content.toByteArray();
                    JsonNode root;
                    try { root = objectMapper.readTree(bytes); }
                    catch (RuntimeException invalid) { throw new IOException("整合包索引 JSON 无效"); }
                    if (root == null || !root.isObject()) throw new IOException("整合包索引必须为 JSON 对象");
                    JsonNode files = root.path("files");
                    if (!"minecraft".equals(root.path("game").asText()) || !root.path("formatVersion").isIntegralNumber() || root.path("formatVersion").asInt() != 1
                            || !files.isArray() || files.size() > 500) throw new IOException("整合包索引格式无效或模组超过 500 个");

                    if (index != null) throw new IOException("整合包含重复索引");
                    index = root;
                }
                zis.closeEntry();
            }
        }
        if (index == null) throw new IOException("整合包缺少 modrinth.index.json");
        return index;
    }

    public record Result(String name, java.util.List<String> slugs, int count, yagen.waitmydawn.maa.model.ImportedPack importedPack) {}

    /** 精确版本及项目查询在委派上下文中执行，不按文件名猜测项目。 */
    public Result parse(JsonNode index) {
        String name = index.path("name").asText("");
        if (name.isBlank() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("索引中的整合包名称无效");
        var deps = index.path("dependencies");
        String key = null;
        for (String candidate : java.util.List.of("fabric-loader", "forge", "neoforge")) if (deps.has(candidate)) {
            if (key != null) throw new IllegalArgumentException("整合包指定多个加载器");
            key = candidate;
        }
        if (key == null || deps.size() != 2 || !deps.has("minecraft"))
            throw new IllegalArgumentException("不支持该整合包的加载器依赖");
        String loader = "fabric-loader".equals(key) ? "fabric" : key;
        String mc = deps.path("minecraft").asText("");
        new yagen.waitmydawn.maa.model.ImportedPack(loader, mc, deps.path(key).asText(), java.util.List.of());
        var files = new java.util.ArrayList<yagen.waitmydawn.maa.model.ImportedPack.File>();
        var pattern = Pattern.compile("^https://cdn\\.modrinth\\.com/data/([A-Za-z0-9_-]{1,128})/versions/([A-Za-z0-9_-]{1,128})/[^/?#]+$");
        var versions = new LinkedHashSet<String>();
        var projects = new LinkedHashSet<String>();
        for (JsonNode file : index.path("files")) {
            var downloads = file.path("downloads");
            if (!downloads.isArray() || downloads.size() != 1 || !downloads.get(0).isString())
                throw new IllegalArgumentException("仅支持单一 Modrinth CDN 来源，不能无损导入其他文件或镜像");
            var match = pattern.matcher(downloads.get(0).asText());
            if (!match.matches()) throw new IllegalArgumentException("文件不含可核实的 Modrinth 精确版本，不能按文件名猜测");
            versions.add(match.group(2));
            projects.add(match.group(1));
        }
        apiClient.prefetchVersions(new java.util.ArrayList<>(versions));
        apiClient.getProjectsBatch(projects);
        for (JsonNode file : index.path("files")) {
            String url = file.path("downloads").get(0).asText();
            var match = pattern.matcher(url); match.matches();
            String projectId = match.group(1), versionId = match.group(2);
            JsonNode version = apiClient.getVersionById(versionId);
            JsonNode project = apiClient.getProjectInfo(projectId);
            if (version == null || project == null || !projectId.equals(version.path("project_id").asText())
                    || !supports(version.path("game_versions"), mc) || !supports(version.path("loaders"), loader))
                throw new IllegalArgumentException("导入文件的精确版本无法核实或不支持包环境，请重试或检查原包");
            String path = file.path("path").asText("");
            if (!path.startsWith("mods/") || path.substring(5).contains("/"))
                throw new IllegalArgumentException("仅支持 mods 目录中的模组文件");
            var env = new java.util.LinkedHashMap<String, String>();
            if (file.has("env")) {
                if (!file.path("env").isObject()) throw new IllegalArgumentException("文件环境格式无效");
                file.path("env").properties().forEach(e -> env.put(e.getKey(), e.getValue().asText()));
            }
            if (!file.path("fileSize").isIntegralNumber()) throw new IllegalArgumentException("文件大小格式无效");
            var imported = new yagen.waitmydawn.maa.model.ImportedPack.File(project.path("slug").asText(), projectId, versionId,
                    path.substring(5), url, file.path("hashes").path("sha1").asText(), file.path("hashes").path("sha512").asText(),
                    file.path("fileSize").asLong(-1), env);
            boolean found = false;
            for (JsonNode metadata : version.path("files")) if (imported.matches(metadata)) found = true;
            if (!found) throw new IllegalArgumentException("导入文件的哈希、名称或大小与精确版本不一致");
            files.add(imported);
        }
        var imported = new yagen.waitmydawn.maa.model.ImportedPack(loader, mc, deps.path(key).asText(), files);
        var slugs = files.stream().map(yagen.waitmydawn.maa.model.ImportedPack.File::slug).toList();
        return new Result(name, slugs, slugs.size(), imported);
    }
    private boolean supports(JsonNode values, String target) {
        for (JsonNode value : values) if (target.equals(value.asText())) return true;
        return false;
    }

    public Set<String> extractProjectSlugs(MultipartFile file) throws IOException {
        return extractProjectSlugs(readIndex(file));
    }
    public Set<String> extractProjectSlugs(JsonNode index) {
        return new LinkedHashSet<>(parse(index).slugs());
    }
}
