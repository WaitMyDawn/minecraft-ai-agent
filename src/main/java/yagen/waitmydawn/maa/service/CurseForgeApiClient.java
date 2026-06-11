package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * CurseForge API 客户端 — 用于从 CurseForge 搜索和下载 Modrinth 上没有的模组
 *
 * CurseForge 使用数字 projectId 和 fileId, 而 Modrinth 使用 slug。
 * 本客户端封装了搜索→获取文件→下载的完整流程。
 *
 * API 文档: https://docs.curseforge.com/
 */
@Service
public class CurseForgeApiClient {

    @Value("${curseforge.api.key:}")
    private String apiKey;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    // Minecraft gameId = 432
    private static final int MC_GAME_ID = 432;
    // 模组分类 classId
    private static final int MOD_CLASS_ID = 6;

    public CurseForgeApiClient(RestClient restClient) {
        this.restClient = restClient;
        this.objectMapper = new ObjectMapper();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * CF loader name → CurseForge modLoaderType ID
     */
    private int getLoaderType(String loader) {
        return switch (loader.toLowerCase()) {
            case "forge" -> 1;
            case "fabric" -> 4;
            case "quilt" -> 5;
            case "neoforge" -> 6;
            default -> 6; // 默认 NeoForge
        };
    }

    /**
     * 按 modid/名称搜索 CurseForge, 返回匹配的模组列表 (最多 limit 个)
     */
    @Cacheable(value = "cfSearch", key = "#query + '_' + #mcVersion + '_' + #loader", sync = true)
    public List<CurseForgeMod> searchMods(String query, String mcVersion, String loader) {
        if (!isConfigured()) return List.of();

        List<CurseForgeMod> results = new ArrayList<>();
        try {
            String url = "https://api.curseforge.com/v1/mods/search"
                    + "?gameId=" + MC_GAME_ID
                    + "&classId=" + MOD_CLASS_ID
                    + "&searchFilter=" + query
                    + "&sortField=2"   // 按流行度排序
                    + "&sortOrder=desc"
                    + "&pageSize=5";

            JsonNode resp = restClient.get()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode data = resp.path("data");
            if (data.isArray()) {
                for (JsonNode mod : data) {
                    CurseForgeMod cf = new CurseForgeMod(
                            mod.path("id").asInt(),
                            mod.path("name").asText(),
                            mod.path("slug").asText()
                    );
                    results.add(cf);
                }
            }
        } catch (Exception e) {
            System.err.println("CurseForge 搜索异常 (" + query + "): " + e.getMessage());
        }
        return results;
    }

    /**
     * 获取模组在指定 MC 版本和加载器下的最新文件信息
     */
    public CurseForgeFile getLatestFile(int projectId, String mcVersion, String loader) {
        if (!isConfigured()) return null;

        try {
            int loaderType = getLoaderType(loader);
            String url = "https://api.curseforge.com/v1/mods/" + projectId
                    + "/files?gameVersion=" + mcVersion
                    + "&modLoaderType=" + loaderType
                    + "&pageSize=3";

            JsonNode resp = restClient.get()
                    .uri(url)
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .body(JsonNode.class);

            JsonNode data = resp.path("data");
            if (data.isArray() && !data.isEmpty()) {
                JsonNode file = data.get(0); // 取最新
                return new CurseForgeFile(
                        file.path("id").asInt(),
                        file.path("fileName").asText(),
                        file.path("downloadUrl").asText(),
                        file.path("fileLength").asLong()
                );
            }
        } catch (Exception e) {
            System.err.println("CurseForge 获取文件异常 (projectId=" + projectId + "): " + e.getMessage());
        }
        return null;
    }

    /**
     * 下载文件到指定目录
     * @return 下载后的文件路径, 失败返回 null
     */
    public Path downloadFile(CurseForgeFile file, Path targetDir) {
        if (!isConfigured()) return null;

        Path targetFile = targetDir.resolve(file.fileName);
        if (Files.exists(targetFile)) {
            System.out.println("  CF 文件已存在, 跳过: " + file.fileName);
            return targetFile;
        }

        try {
            System.out.println("  📥 CurseForge 下载: " + file.fileName + " (" + file.fileLength / 1024 + " KB)");
            try (InputStream in = new java.net.URL(file.downloadUrl).openStream()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.println("  ✅ CF 下载完成: " + file.fileName);
            return targetFile;
        } catch (Exception e) {
            System.err.println("CurseForge 下载失败 (" + file.fileName + "): " + e.getMessage());
            return null;
        }
    }

    /**
     * 一站式: 搜索模组 → 获取文件 → 下载
     * @return 下载后的文件路径, 失败返回 null
     */
    public Path searchAndDownload(String modid, String mcVersion, String loader, Path modsDir) {
        List<CurseForgeMod> mods = searchMods(modid, mcVersion, loader);
        if (mods.isEmpty()) {
            System.out.println("  CurseForge 也未找到: " + modid);
            return null;
        }

        // 优先精确匹配 modid → slug
        CurseForgeMod best = mods.get(0);
        for (CurseForgeMod m : mods) {
            if (m.slug.equalsIgnoreCase(modid)) {
                best = m;
                break;
            }
        }
        System.out.println("  🔍 CurseForge 匹配: " + best.name + " (projectId=" + best.projectId + ")");

        CurseForgeFile file = getLatestFile(best.projectId, mcVersion, loader);
        if (file == null) {
            System.out.println("  CurseForge 无匹配版本: " + modid + " for " + mcVersion + "/" + loader);
            return null;
        }
        return downloadFile(file, modsDir);
    }

    // ===== 数据类 =====

    public record CurseForgeMod(int projectId, String name, String slug) {}

    public record CurseForgeFile(int fileId, String fileName, String downloadUrl, long fileLength) {}
}
