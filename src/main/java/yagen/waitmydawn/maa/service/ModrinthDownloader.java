package yagen.waitmydawn.maa.service;

import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

@Component
public class ModrinthDownloader {

    private final ModrinthApiClient apiClient;

    public ModrinthDownloader(ModrinthApiClient apiClient) {
        this.apiClient = apiClient;
    }

    /**
     * 并发下载模组, 带进度回调
     * @param onProgress (doneCount, totalCount, currentSlug)
     */
    public void downloadModsToSandbox(Set<String> slugs, String mcVersion, String loader,
                                       Path modsDir, TriConsumer<Integer, Integer, String> onProgress) {
        String loadersParam = "[\"" + loader.toLowerCase() + "\"]";
        int total = slugs.size();
        AtomicInteger done = new AtomicInteger(0);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            CompletableFuture<?>[] futures = slugs.stream().map(slug -> CompletableFuture.runAsync(() -> {
                downloadOneMod(slug, mcVersion, loadersParam, modsDir, 0);
                int current = done.incrementAndGet();
                onProgress.accept(current, total, slug);
            }, executor)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        }
        onProgress.accept(total, total, "");
    }

    @FunctionalInterface
    public interface TriConsumer<A, B, C> {
        void accept(A a, B b, C c);
    }

    // 向后兼容的无回调版本
    public void downloadModsToSandbox(Set<String> slugs, String mcVersion, String loader, Path modsDir) {
        downloadModsToSandbox(slugs, mcVersion, loader, modsDir, (done, total, slug) -> {});
    }

    private void downloadOneMod(String slug, String mcVersion, String loadersParam,
                                 Path modsDir, int retryCount) {
        final int maxRetries = 3;
        try {
            JsonNode proj = apiClient.getProjectInfo(slug);
            if (proj == null) return;
            String projectId = proj.path("id").asText();

            JsonNode version = apiClient.getLatestVersion(projectId, mcVersion, loadersParam);
            if (version == null || !version.has("files")) return;

            JsonNode fileNode = version.path("files").get(0);
            String downloadUrl = fileNode.path("url").asText();
            String filename = fileNode.path("filename").asText();
            Path targetFile = modsDir.resolve(filename);

            if (Files.exists(targetFile)) {
                if (isValidZip(targetFile)) return;
                System.err.println("⚠️ 损坏: " + filename + ", 重下");
                Files.delete(targetFile);
            }

            try (InputStream in = new java.net.URL(downloadUrl).openStream()) {
                Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);
            }

            if (!isValidZip(targetFile)) {
                System.err.println("❌ ZIP校验失败: " + filename);
                Files.deleteIfExists(targetFile);
                if (retryCount < maxRetries) {
                    Thread.sleep(2000);
                    downloadOneMod(slug, mcVersion, loadersParam, modsDir, retryCount + 1);
                }
            }
        } catch (Exception e) {
            System.err.println("下载失败: " + slug + " - " + e.getMessage());
            if (retryCount < maxRetries) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                downloadOneMod(slug, mcVersion, loadersParam, modsDir, retryCount + 1);
            }
        }
    }

    public static boolean isValidZip(Path file) {
        try {
            if (!Files.exists(file) || Files.size(file) < 100) return false;
            try (ZipFile zf = new ZipFile(file.toFile())) {
                return zf.size() > 0;
            }
        } catch (ZipException e) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
