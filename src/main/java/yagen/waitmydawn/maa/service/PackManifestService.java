package yagen.waitmydawn.maa.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

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
 * 进程重启或快照过期时 build 走兼容路径（前端仍可传 selectedFiles）。
 */
@Service
public class PackManifestService {

    private static final Logger log = LoggerFactory.getLogger(PackManifestService.class);
    /** 快照存活时间：一次编辑会话足够，过期即失效（避免内存里长期堆着旧包） */
    private static final long TTL_MS = 30 * 60 * 1000L;
    /** 最多保留多少份快照（LRU 淘汰） */
    private static final int MAX_MANIFESTS = 20;

    private record Manifest(long createdAt, String loader, String mcVersion,
                            Map<String, JsonNode> fileByNodeId) {
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
        Map<String, JsonNode> map = new LinkedHashMap<>();
        int n = Math.min(nodeIds.size(), files.size());
        for (int i = 0; i < n; i++) {
            JsonNode f = files.get(i);
            if (f != null && !f.isNull() && f.hasNonNull("filename")) {
                map.put(nodeIds.get(i), f);
            }
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        manifests.put(id, new Manifest(System.currentTimeMillis(), loader, mcVersion, map));
        return id;
    }

    /**
     * 按选择取出快照里的文件。
     *
     * @param selectedIds 用户勾选的节点 id；为空表示全选
     * @return 文件列表；快照不存在/已过期返回 {@code null}（调用方应回退兼容路径）
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
            out.addAll(m.fileByNodeId().values());
        } else {
            for (String id : selectedIds) {
                JsonNode f = m.fileByNodeId().get(id);
                if (f != null) out.add(f);
            }
        }
        log.debug("manifest {} 取件 {} 个（loader={}, mc={}）", manifestId, out.size(),
                m.loader(), m.mcVersion());
        return out;
    }

    /** 快照里登记了多少个可导出的文件（用于诊断） */
    public synchronized int sizeOf(String manifestId) {
        Manifest m = manifests.get(manifestId);
        return m == null ? -1 : m.fileByNodeId().size();
    }
}
