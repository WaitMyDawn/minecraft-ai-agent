package yagen.waitmydawn.maa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.model.DependencyClosure;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 包清单快照（P6-C）。
 *
 * <p>要解决的问题：preview 与 build 是两次独立请求。旧实现里 build 直接吃前端传来的
 * {@code selectedFiles}，于是有两种不一致：
 * <ol>
 *   <li>两次请求之间上游发布了新版本 → 预览看到的文件 ≠ 导出的文件</li>
 *   <li>客户端可以传任意文件对象进来，服务端没有任何校验</li>
 * </ol>
 *
 * <p>做法：preview 解析完成后把"节点 id → 该节点选定的版本与文件"登记成一份带 TTL 的快照，
 * 返回 {@code manifestId}；build 只按 {@code manifestId + selectedIds} 取件，
 * 于是导出必然等于用户当时看到的那一份。
 *
 * <p>内存实现（有界 + TTL）足够：单体应用、快照只在一次编辑会话内有效。
 * 进程重启或快照过期时必须重新预览；不得采信客户端直传文件绕过快照校验。
 */
@Service
public class PackManifestService {

    private static final Logger log = LoggerFactory.getLogger(PackManifestService.class);
    /** 快照存活时间：一次编辑会话足够，过期即失效（避免内存里长期堆着旧包） */
    private static final long TTL_MS = 30 * 60 * 1000L;
    /**
     * 最多保留多少份快照（LRU 淘汰）。
     *
     * <p>这里原来是 20 —— 单机单人时够用，多用户下不够：这份表是<b>全局共享</b>的，
     * 两个用户交替预览/生成时，后者的快照会把前者的挤出去；前者再点生成，{@code filesFor} 返回 null，
     * 就回退到"逐个模组查版本"的兼容路径 —— 把本该零出网的导出变成几十次出网请求（还要过令牌闸排队）。
     * 快照本身很小（每份就是一组 fileInfo），放宽到 200 的代价可以忽略。
     */
    private static final int MAX_MANIFESTS = 200;

    private record Manifest(long createdAt, String loader, String mcVersion, Long ownerId,
                            Map<String, JsonNode> fileByNodeId, Map<String, String> slugByNodeId, DependencyClosure closure,
                            Map<String, String> dependencies) {
    }

    /** 用 LinkedHashMap + 手工 LRU：快照小、量少，不值得引入额外依赖 */
    private final Map<String, Manifest> manifests = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Manifest> eldest) {
            return size() > MAX_MANIFESTS;
        }
    };

    /**
     * 登记一份快照。
     *
     * @param nodeIds 节点 id（= Modrinth projectId）
     * @param files   与 nodeIds 一一对应的 fileInfo
     * @return manifestId，供前端在 build 时回传
     */
    public synchronized String register(List<String> nodeIds, List<JsonNode> files,
                                        String loader, String mcVersion) {
        return register(nodeIds, files, loader, mcVersion, null);
    }

    public synchronized String register(List<String> nodeIds, List<JsonNode> files,
                                        String loader, String mcVersion, Long ownerId) {
        return register(nodeIds, files, loader, mcVersion, ownerId, Map.of(), null);
    }

    public synchronized String register(List<String> nodeIds, List<JsonNode> files,
                                        String loader, String mcVersion, Long ownerId,
                                        Map<String, String> slugByNodeId, DependencyClosure closure) {
        return register(nodeIds, files, loader, mcVersion, ownerId, slugByNodeId, closure, Map.of());
    }

    public synchronized String register(List<String> nodeIds, List<JsonNode> files,
                                        String loader, String mcVersion, Long ownerId,
                                        Map<String, String> slugByNodeId, DependencyClosure closure, Map<String, String> dependencies) {
        Map<String, JsonNode> map = new LinkedHashMap<>();
        int n = Math.min(nodeIds.size(), files.size());
        for (int i = 0; i < n; i++) {
            JsonNode f = files.get(i);
            if (f != null && !f.isNull() && f.hasNonNull("filename")) {
                map.put(nodeIds.get(i), f.deepCopy());
            }
        }
        String id = UUID.randomUUID().toString();
        manifests.put(id, new Manifest(System.currentTimeMillis(), loader, mcVersion, ownerId, map, Map.copyOf(slugByNodeId), closure, Map.copyOf(dependencies)));
        return id;
    }

    /**
     * 按选择取出快照里的文件。
     *
     * @param selectedIds 用户勾选的节点 id；为空表示全选
     * @return 文件列表；快照不存在/已过期返回 {@code null}（调用方应要求重新预览）
     */
    public synchronized List<JsonNode> filesFor(String manifestId, List<String> selectedIds) {
        Manifest m = manifests.get(manifestId);
        if (m == null) return null;
        if (System.currentTimeMillis() - m.createdAt() > TTL_MS) {
            manifests.remove(manifestId);
            return null;
        }
        List<JsonNode> out = new ArrayList<>();
        if (selectedIds == null || selectedIds.isEmpty()) {
            m.fileByNodeId().values().forEach(f -> out.add(f.deepCopy()));
        } else {
            for (String id : new java.util.LinkedHashSet<>(selectedIds)) {
                JsonNode f = m.fileByNodeId().get(id);
                if (f == null) throw new IllegalArgumentException("勾选项不属于当前清单快照");
                out.add(f.deepCopy());
            }
        }
        log.debug("manifest {} 取件 {} 个（loader={}, mc={}）", manifestId, out.size(),
                m.loader(), m.mcVersion());
        return out;
    }

    public synchronized List<JsonNode> filesFor(String id, List<String> selectedIds, String loader, String mc) {
        Manifest manifest = manifests.get(id);
        if (manifest != null && (!manifest.loader().equals(loader) || !manifest.mcVersion().equals(mc))) {
            throw new IllegalArgumentException("环境与清单快照不一致，请重新预览");
        }
        return filesFor(id, selectedIds);
    }

    public synchronized List<JsonNode> filesFor(String id, List<String> selectedIds, String loader, String mc, Long ownerId) {
        Manifest manifest = manifests.get(id);
        if (manifest != null && !java.util.Objects.equals(manifest.ownerId(), ownerId)) return null;
        return filesFor(id, selectedIds, loader, mc);
    }

    public record Selection(List<JsonNode> files, DependencyClosure.Report closure, Map<String, String> dependencies) {}

    /** 文件和依赖要求取自同一快照；检查不发起网络请求，也不自动改变勾选项。 */
    public synchronized Selection selectionFor(String id, List<String> selectedIds, String loader, String mc, Long ownerId) {
        var files = filesFor(id, selectedIds, loader, mc, ownerId);
        if (files == null) return null;
        Manifest manifest = manifests.get(id);
        var selected = selectedIds == null || selectedIds.isEmpty() ? manifest.fileByNodeId().keySet() : selectedIds;
        var slugs = new java.util.LinkedHashSet<String>();
        for (String nodeId : selected) slugs.add(manifest.slugByNodeId().getOrDefault(nodeId, nodeId));
        var report = manifest.closure() == null
                ? new DependencyClosure.Report(DependencyClosure.Status.UNVERIFIED,
                    List.of(new DependencyClosure.Issue("", "", "UNVERIFIED_METADATA", DependencyClosure.Severity.UNKNOWN)))
                : manifest.closure().inspect(slugs);
        return new Selection(files, report, manifest.dependencies());
    }

    /** 快照里登记了多少个可导出的文件（用于诊断） */
    public synchronized int sizeOf(String manifestId) {
        Manifest m = manifests.get(manifestId);
        return m == null ? -1 : m.fileByNodeId().size();
    }
}
