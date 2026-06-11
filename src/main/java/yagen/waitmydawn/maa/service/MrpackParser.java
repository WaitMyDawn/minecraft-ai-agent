package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class MrpackParser {

    private final ObjectMapper objectMapper;
    private final ModrinthApiClient apiClient;

    // 匹配版本后缀: -1.0.0, -2.1, -1.21.1, -1.0.0+1.21.1, -neoforge, -fabric 等
    private static final Pattern VERSION_SUFFIX = Pattern.compile(
            "-\\d+\\.\\d+(\\.\\d+)?([+-].*)?$");

    public MrpackParser(ModrinthApiClient apiClient) {
        this.objectMapper = new ObjectMapper();
        this.apiClient = apiClient;
    }

    /**
     * 从 .mrpack 文件中提取模组项目 slug
     * .mrpack 本质是一个 ZIP, 内含 modrinth.index.json
     */
    public Set<String> extractProjectSlugs(MultipartFile file) throws IOException {
        Set<String> slugs = new LinkedHashSet<>();

        try (ZipInputStream zis = new ZipInputStream(file.getInputStream())) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("modrinth.index.json".equals(entry.getName())) {
                    byte[] bytes = zis.readAllBytes();
                    JsonNode root = objectMapper.readTree(bytes);
                    JsonNode files = root.path("files");

                    if (files.isArray()) {
                        for (JsonNode fileNode : files) {
                            // 1. 优先从 filename 提取
                            String filename = fileNode.path("filename").asText();
                            String derivedSlug = deriveSlugFromFilename(filename);

                            // 2. 尝试验证
                            String confirmed = confirmSlug(derivedSlug);
                            if (confirmed != null) {
                                slugs.add(confirmed);
                            } else {
                                // 3. 回退: 尝试从 download URL 提取 project ID
                                String downloadUrl = getDownloadUrl(fileNode);
                                if (downloadUrl != null) {
                                    String fromUrl = extractSlugFromUrl(downloadUrl);
                                    if (fromUrl != null) {
                                        // 用 project ID 查 slug
                                        JsonNode proj = apiClient.getProjectInfo(fromUrl);
                                        if (proj != null) {
                                            String realSlug = proj.path("slug").asText();
                                            if (realSlug != null && !realSlug.isEmpty()) {
                                                slugs.add(realSlug);
                                                continue;
                                            }
                                        }
                                    }
                                }
                                // 4. 无法确认, 直接加入 tentative slug
                                System.err.println("⚠️ 无法验证 slug, 直接使用推测值: " + derivedSlug);
                                slugs.add(derivedSlug);
                            }
                        }
                    }
                    break;
                }
                zis.closeEntry();
            }
        }
        return slugs;
    }

    /**
     * 从文件名衍生推测 slug
     * 例如: "irons-spells-n-spellbooks-1.21.1-3.4.0.5.jar" → "irons-spells-n-spellbooks"
     */
    private String deriveSlugFromFilename(String filename) {
        if (filename == null) return "unknown";
        // 去掉 .jar 后缀
        String name = filename;
        if (name.endsWith(".jar")) {
            name = name.substring(0, name.length() - 4);
        }

        // 尝试去掉版本后缀
        Matcher m = VERSION_SUFFIX.matcher(name);
        if (m.find()) {
            String candidate = name.substring(0, m.start());
            // 确保剩下的看起来像 slug (小写字母+连字符)
            if (candidate.matches("[a-z][a-z0-9-]*[a-z0-9]")) {
                return candidate;
            }
        }
        return name;
    }

    /**
     * 验证推测 slug 是否在 Modrinth 上存在
     */
    private String confirmSlug(String tentative) {
        try {
            JsonNode proj = apiClient.getProjectInfo(tentative);
            if (proj != null) {
                String slug = proj.path("slug").asText();
                return (slug != null && !slug.isEmpty()) ? slug : null;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String getDownloadUrl(JsonNode fileNode) {
        // 查找 URL
        String url = fileNode.path("url").asText(null);
        if (url != null && !url.isBlank()) return url;
        // .mrpack 格式可能把 URL 存在 downloads 数组里
        JsonNode downloads = fileNode.path("downloads");
        if (downloads.isArray() && !downloads.isEmpty()) {
            return downloads.get(0).asText(null);
        }
        return null;
    }

    /**
     * 从 Modrinth CDN URL 中提取 project ID 或 slug
     * URL 格式如: https://cdn.modrinth.com/data/XXXX/versions/YYYY/file.jar
     */
    private String extractSlugFromUrl(String url) {
        if (url == null) return null;
        Pattern p = Pattern.compile("modrinth\\.com/data/([^/]+)/");
        Matcher m = p.matcher(url);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }
}
