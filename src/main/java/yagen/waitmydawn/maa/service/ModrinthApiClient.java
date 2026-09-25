package yagen.waitmydawn.maa.service;

import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.runtime.DelegationContext;
import yagen.waitmydawn.maa.runtime.DelegationContext.Kind;
import yagen.waitmydawn.maa.runtime.DelegationContext.Outcome;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modrinth 访问门面：决定这一次取数<b>交给谁做</b>。
 *
 * <p>三档优先级，逐层下沉：
 * <ol>
 *   <li><b>用户浏览器</b>——当前线程挂着 {@link DelegationContext}（即正在一次委派任务里）时，
 *       登记需求并挂起，等 /api/chat/task/{id}/facts 把结果送回来。配额算在用户自己的出口 IP 上。</li>
 *   <li><b>服务器自己抓</b>——委派没开、等超时（单轮 5 秒）、或该键已被判定为"上游没有"时，
 *       落到 {@link ModrinthFetcher}：令牌闸排队 + 429 指数退避 + Caffeine 缓存。</li>
 *   <li><b>缓存</b>——第 2 档内部的事，不在这里体现。</li>
 * </ol>
 *
 * <p><b>为什么缓存写在下面那层</b>：{@code @Cacheable} 拦不住——只要方法带注解，Spring 就会把
 * 返回值塞进共享缓存，而这个返回值可能来自不可信通道（浏览器回传）。一份被改过的 version
 * 依赖列表足以让<b>别人</b>生成的整合包变成断链包，那是跨用户污染，不是"他改自己的包自伤"。
 * 所以把带缓存的出网整体下沉到 {@link ModrinthFetcher}，本层在进入它之前就分流。
 *
 * <p>委派只在任务上下文内生效：没有 {@link DelegationContext} 的调用（偏好学习、mrpack 解析、
 * 离线工具）行为与改造前完全一致。
 */
@Service
public class ModrinthApiClient {

    private final ModrinthFetcher fetcher;
    private final DelegationTasks delegationTasks;

    public ModrinthApiClient(ModrinthFetcher fetcher, DelegationTasks delegationTasks) {
        this.fetcher = fetcher;
        this.delegationTasks = delegationTasks;
    }

    /**
     * 「无法确定」异常 —— 限流 429 / 5xx / 超时重试耗尽。
     *
     * <p>语义上必须与「确定查不到」(方法返回 null) 严格区分：
     * 前者是网络故障，后者是 Modrinth 明确告诉你没有这个版本。
     * 把前者当成后者会写出错误的"该模组不支持此版本"结论。
     *
     * <p>位置刻意留在门面上、不跟着出网层走：调用方有一片
     * {@code catch (ModrinthApiClient.UnavailableException)}，挪走要改一圈 catch 子句，收益为零。
     */
    public static class UnavailableException extends RuntimeException {
        public UnavailableException(String message) {
            super(message);
        }
    }

    // ==========================================
    // 四类事实：委派优先，落空则自己抓
    // ==========================================

    /** 项目元数据 */
    public JsonNode getProjectInfo(String slugOrId) {
        DelegationContext.Answer answer = askClient(Kind.PROJECT, slugOrId);
        if (answeredByClient(answer)) return answer.data();
        return fetcher.getProjectInfo(slugOrId);
    }

    /** 按 version_id 精确取版本 */
    public JsonNode getVersionById(String versionId) {
        DelegationContext.Answer answer = askClient(Kind.VERSION, versionId);
        if (answeredByClient(answer)) return answer.data();
        return fetcher.getVersionById(versionId);
    }

    /**
     * 某项目在某 (mc, loader) 下的最新版本。
     *
     * <p>这是 Modrinth 唯一<b>没有批量等价物</b>的查询（实测三种写法都不支持），
     * 所以一次构筑里它占的请求数最多，也正是最需要搬走的那部分。
     */
    public JsonNode getLatestVersion(String projectId, String mcVersion, String loaders) {
        DelegationContext.Answer answer = askClient(Kind.ENV_VERSION, envKey(projectId, mcVersion, loaders));
        if (answeredByClient(answer)) return answer.data();
        return fetcher.getLatestVersion(projectId, mcVersion, loaders);
    }

    /** 按 slug 查最新兼容版本；slug 路由失败时退回 projectInfo + id 的那一步在出网层内部完成，不再二次委派 */
    public JsonNode getLatestCompatibleVersionBySlug(String slug, String mcVersion, String loader) {
        DelegationContext.Answer answer = askClient(Kind.ENV_VERSION,
                envKey(slug, mcVersion, "[\"" + loader + "\"]"));
        if (answeredByClient(answer)) return answer.data();
        return fetcher.getLatestCompatibleVersionBySlug(slug, mcVersion, loader);
    }

    // ==========================================
    // 搜索与批量
    // ==========================================

    /** 搜索暂不下放：调用点少、量小，且它的"事实"要带一串查询参数，留给下一轮再收 */
    public JsonNode search(String query, int limit, String facets) {
        return fetcher.search(query, limit, facets);
    }

    public JsonNode searchPage(String query, int limit, int offset, String index, String facets) {
        return fetcher.searchPage(query, limit, offset, index, facets);
    }

    /**
     * 批量预取项目元数据。
     *
     * <p>委派上下文里<b>直接跳过</b>：预取的意义是"一次请求灌一批进缓存"，而委派路径下每个节点
     * 本来就会各自去问浏览器、由浏览器批量——两条批量机制叠在一起只会白发一次服务器请求。
     */
    public void prefetchProjects(Collection<String> idsOrSlugs) {
        if (DelegationContext.current() != null) return;
        fetcher.prefetchProjects(idsOrSlugs);
    }

    /** 同上 */
    public void prefetchVersions(Collection<String> versionIds) {
        if (DelegationContext.current() != null) return;
        fetcher.prefetchVersions(versionIds);
    }

    /**
     * 批量取项目元数据。
     *
     * <p>委派上下文里退回逐键处理（走委派）：批量端点属于"服务器自己抓"那一档。
     * 这条路只有偏好学习在用，不在构筑热路径上，逐键的开销可以接受。
     */
    public Map<String, JsonNode> getProjectsBatch(Collection<String> idsOrSlugs) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (idsOrSlugs == null || idsOrSlugs.isEmpty()) return out;
        if (DelegationContext.current() != null) {
            for (String key : idsOrSlugs) {
                JsonNode v = getProjectInfo(key);
                if (v != null) out.put(key, v);
            }
            return out;
        }
        return fetcher.getProjectsBatch(idsOrSlugs);
    }

    // ==========================================
    // 内部
    // ==========================================

    /**
     * 问一次浏览器。
     *
     * @return null 表示"这次没走委派"（开关关闭 / 不在任务里），调用方应自己抓；非 null 时看
     *         {@link Outcome}：GOT 用它的数据，ABSENT 就是"上游确实没有"，
     *         TIMEOUT / CANCELLED 仍然要自己抓。
     */
    private DelegationContext.Answer askClient(Kind kind, String key) {
        DelegationContext ctx = DelegationContext.current();
        if (ctx == null || !delegationTasks.isEnabled()) return null;
        return ctx.await(kind, key, delegationTasks.callBudgetMs());
    }

    /** true 表示"这个键的结果已经由浏览器给出"（数据可能为 null = 上游确实没有） */
    private static boolean answeredByClient(DelegationContext.Answer answer) {
        return answer != null
                && (answer.outcome() == Outcome.GOT || answer.outcome() == Outcome.ABSENT);
    }

    /** ENV_VERSION 的键必须带上环境，否则不同 mc/loader 会互相串味 */
    private static String envKey(String idOrSlug, String mcVersion, String loaders) {
        return idOrSlug + "|" + mcVersion + "|" + loaders;
    }
}
