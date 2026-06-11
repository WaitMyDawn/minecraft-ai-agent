package yagen.waitmydawn.maa;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import yagen.waitmydawn.maa.service.ModrinthTool;

import java.util.*;

@Service
public class ModDependencyResolver {

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ModrinthTool modrinthTool;

    // 缓存，避免重复请求
    private final Map<String, JsonNode> projectCache = new HashMap<>();
    private final Map<String, List<JsonNode>> versionCache = new HashMap<>();

    public ModDependencyResolver(ModrinthTool modrinthTool) {
        this.modrinthTool = modrinthTool;
    }

    public static class ModVersionInfo {
        public String projectId;
        public String slug;
        public String versionId;
        public String filename;
        public String downloadUrl;
        public String sha1;
        public String sha512;
        public long fileSize;
        public String loader;
        public String gameVersion;
        public List<Dependency> dependencies;

        public static class Dependency {
            public String projectId;
            public String versionId;
            public String dependencyType;
            public String slug; // 添加slug字段便于调试
        }
    }

    /**
     * 解析模组的所有依赖（使用BFS算法深度解析）
     */
    public Map<String, ModVersionInfo> resolveAllDependencies(
            List<String> coreModSlugs,
            String gameVersion,
            String loader) {

        Map<String, ModVersionInfo> resolvedMods = new LinkedHashMap<>();
        Queue<String> toProcess = new LinkedList<>();
        Set<String> processed = new HashSet<>();

        // 首先处理所有核心模组
        for (String slug : coreModSlugs) {
            toProcess.add(slug);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("User-Agent", "MAA-Agent/1.0 (contact@example.com)");
        HttpEntity<String> entity = new HttpEntity<>(headers);

        int maxIterations = 100; // 防止无限循环
        int iteration = 0;

        while (!toProcess.isEmpty() && iteration < maxIterations) {
            iteration++;
            String slugOrId = toProcess.poll();

            // 避免重复处理
            String key = slugOrId;
            if (processed.contains(key)) continue;
            processed.add(key);

            System.out.println("🔍 处理模组: " + slugOrId);

            try {
                // 1. 获取项目信息（如果还没有）
                JsonNode projectInfo = getProjectInfo(slugOrId);
                if (projectInfo == null) {
                    System.out.println("   ❌ 无法获取项目信息: " + slugOrId);
                    continue;
                }

                String projectId = projectInfo.path("id").asText();
                String slug = projectInfo.path("slug").asText();

                // 2. 查找适配的版本
                ModVersionInfo versionInfo = findBestVersion(
                        projectId, slug, gameVersion, loader, entity
                );

                if (versionInfo == null) {
                    // 尝试备用加载器
                    String altLoader = getAlternativeLoader(loader);
                    if (altLoader != null) {
                        System.out.println("   🔄 尝试备用加载器: " + altLoader);
                        versionInfo = findBestVersion(
                                projectId, slug, gameVersion, altLoader, entity
                        );
                        if (versionInfo != null) {
                            versionInfo.loader = altLoader;
                        }
                    }
                }

                if (versionInfo != null) {
                    resolvedMods.put(slug, versionInfo);
                    System.out.println("   ✅ 找到版本: " + versionInfo.filename);

                    // 3. 处理依赖
                    for (ModVersionInfo.Dependency dep : versionInfo.dependencies) {
                        if ("required".equals(dep.dependencyType) || "embedded".equals(dep.dependencyType)) {
                            // 获取依赖的slug
                            String depSlug = getSlugFromProjectId(dep.projectId, entity);
                            if (depSlug != null && !resolvedMods.containsKey(depSlug) && !toProcess.contains(depSlug)) {
                                System.out.println("   🔗 添加依赖: " + depSlug + " (来自 " + slug + ")");
                                toProcess.add(depSlug);
                            }
                        } else if ("incompatible".equals(dep.dependencyType)) {
                            System.out.println("   ⚠️  发现冲突: " + slug + " 与 " + dep.projectId + " 不兼容");
                        }
                    }
                } else {
                    System.out.println("   ⚠️  未找到 " + slugOrId + " 适配 " + gameVersion + " " + loader + " 的版本");

                    // 尝试搜索相似的模组
                    String similarMod = searchSimilarMod(slugOrId, gameVersion, loader);
                    if (similarMod != null && !processed.contains(similarMod)) {
                        System.out.println("   💡 找到相似模组: " + similarMod);
                        toProcess.add(similarMod);
                    }
                }

            } catch (Exception e) {
                System.out.println("   ❌ 处理失败: " + e.getMessage());
                e.printStackTrace();
            }
        }

        return resolvedMods;
    }

    private JsonNode getProjectInfo(String slugOrId) {
        if (projectCache.containsKey(slugOrId)) {
            return projectCache.get(slugOrId);
        }

        try {
            String url = "https://api.modrinth.com/v2/project/" + slugOrId;
            String jsonResponse = restTemplate.getForObject(url, String.class);
            JsonNode result = objectMapper.readTree(jsonResponse);
            projectCache.put(slugOrId, result);
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    private ModVersionInfo findBestVersion(String projectId, String slug,
                                           String gameVersion, String loader,
                                           HttpEntity<String> entity) {
        try {
            // 获取项目的所有版本
            List<JsonNode> versions = getProjectVersions(projectId, entity);

            ModVersionInfo bestMatch = null;
            int bestScore = -1;

            for (JsonNode version : versions) {
                // 检查游戏版本
                JsonNode gameVersions = version.path("game_versions");
                boolean versionMatch = false;
                for (JsonNode gv : gameVersions) {
                    String gvStr = gv.asText();
                    if (gvStr.equals(gameVersion) || gvStr.startsWith(gameVersion)) {
                        versionMatch = true;
                        break;
                    }
                }
                if (!versionMatch) continue;

                // 检查加载器
                JsonNode loaders = version.path("loaders");
                boolean loaderMatch = false;
                int loaderScore = 0;
                for (JsonNode l : loaders) {
                    String loaderName = l.asText();
                    if (loaderName.equalsIgnoreCase(loader)) {
                        loaderMatch = true;
                        loaderScore = 2; // 精确匹配
                        break;
                    } else if (loaderName.equalsIgnoreCase(getAlternativeLoader(loader))) {
                        loaderMatch = true;
                        loaderScore = 1; // 备用匹配
                    }
                }
                if (!loaderMatch) continue;

                // 计算总分
                int totalScore = loaderScore;

                // 优先选择稳定版（非alpha/beta）
                String versionNumber = version.path("version_number").asText();
                if (!versionNumber.contains("alpha") && !versionNumber.contains("beta")) {
                    totalScore += 1;
                }

                if (totalScore > bestScore) {
                    bestScore = totalScore;
                    bestMatch = createVersionInfo(projectId, slug, version, loader, gameVersion);
                }
            }

            return bestMatch;

        } catch (Exception e) {
            System.out.println("查找版本时出错: " + e.getMessage());
            return null;
        }
    }

    private List<JsonNode> getProjectVersions(String projectId, HttpEntity<String> entity) {
        if (versionCache.containsKey(projectId)) {
            return versionCache.get(projectId);
        }

        try {
            String url = "https://api.modrinth.com/v2/project/" + projectId + "/version";
            ResponseEntity<String> response = restTemplate.exchange(
                    url, HttpMethod.GET, entity, String.class
            );
            JsonNode versions = objectMapper.readTree(response.getBody());
            List<JsonNode> versionList = new ArrayList<>();
            if (versions.isArray()) {
                for (JsonNode v : versions) {
                    versionList.add(v);
                }
            }
            versionCache.put(projectId, versionList);
            return versionList;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    private ModVersionInfo createVersionInfo(String projectId, String slug,
                                             JsonNode version, String loader,
                                             String gameVersion) {
        ModVersionInfo info = new ModVersionInfo();
        info.projectId = projectId;
        info.slug = slug;
        info.versionId = version.path("id").asText();
        info.loader = loader;
        info.gameVersion = gameVersion;

        JsonNode files = version.path("files");
        if (files.isArray() && files.size() > 0) {
            JsonNode file = files.get(0);
            info.filename = file.path("filename").asText();
            info.downloadUrl = file.path("url").asText();
            info.sha1 = file.path("hashes").path("sha1").asText();
            info.sha512 = file.path("hashes").path("sha512").asText();
            info.fileSize = file.path("size").asLong();
        }

        // 解析依赖
        info.dependencies = new ArrayList<>();
        JsonNode deps = version.path("dependencies");
        for (JsonNode dep : deps) {
            ModVersionInfo.Dependency d = new ModVersionInfo.Dependency();
            d.projectId = dep.path("project_id").asText();
            d.versionId = dep.path("version_id").asText();
            d.dependencyType = dep.path("dependency_type").asText();
            info.dependencies.add(d);
        }

        return info;
    }

    private String getSlugFromProjectId(String projectId, HttpEntity<String> entity) {
        try {
            JsonNode projectInfo = getProjectInfo(projectId);
            if (projectInfo != null) {
                return projectInfo.path("slug").asText();
            }
        } catch (Exception e) {
            // 忽略
        }
        return null;
    }

    private String getAlternativeLoader(String loader) {
        if ("neoforge".equalsIgnoreCase(loader)) {
            return "forge";
        } else if ("forge".equalsIgnoreCase(loader)) {
            return "neoforge";
        } else if ("fabric".equalsIgnoreCase(loader)) {
            return "quilt";
        }
        return null;
    }

    private String searchSimilarMod(String originalSlug, String gameVersion, String loader) {
        // 处理常见别名
        Map<String, String> aliases = new HashMap<>();
        aliases.put("alexs-mobs", "alexsmobs");
        aliases.put("alexs mobs", "alexsmobs");
        aliases.put("alex's mobs", "alexsmobs");
        aliases.put("sophisticated-storage", "sophisticatedstorage");
        aliases.put("sophisticated storage", "sophisticatedstorage");
        aliases.put("iron's spells", "irons-spells-n-spellbooks");
        aliases.put("irons spellbook", "irons-spells-n-spellbooks");

        String normalized = originalSlug.toLowerCase().replace(" ", "-");
        if (aliases.containsKey(normalized)) {
            return aliases.get(normalized);
        }

        // 尝试搜索
        try {
            String url = "https://api.modrinth.com/v2/search?query=" + originalSlug + "&limit=3";
            String jsonResponse = restTemplate.getForObject(url, String.class);
            JsonNode root = objectMapper.readTree(jsonResponse);
            JsonNode hits = root.path("hits");

            if (hits.isArray() && hits.size() > 0) {
                // 返回第一个结果
                return hits.get(0).path("slug").asText();
            }
        } catch (Exception e) {
            // 忽略
        }

        return null;
    }

    /**
     * 获取最新版本的加载器
     */
    public String getLatestLoaderVersion(String loader, String gameVersion) {
        // 简化版本，实际应该从API获取
        if ("neoforge".equalsIgnoreCase(loader)) {
            return "21.1.218";
        } else if ("fabric".equalsIgnoreCase(loader)) {
            return "0.16.5";
        } else if ("forge".equalsIgnoreCase(loader)) {
            return "51.0.30";
        }
        return "latest";
    }
}