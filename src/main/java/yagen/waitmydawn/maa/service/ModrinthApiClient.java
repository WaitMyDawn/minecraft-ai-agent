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
 *   <li><b>服务器自己抓</b>——委派没开、等超时（预算见 {@code maa.delegation.per-call-budget-ms}）、
 *       或浏览器明确回报无法获取时，
 *       落到 {@link ModrinthFetcher}：令牌闸排队 + 429 指数退避 + Caffeine 缓存。</li>
 *   <li><b>缓存</b>——第 2 档内部的事，不在这里体现。</li>
 * </ol>
 *
 * <p><b>为什么缓存写在下面那层</b>：{@code @Cacheable} 拦不住——只要方法带注解，Spring 就会把
 * 返回值塞进共享缓存，而这个返回值可能来自不可信通道（浏览器回传）。一份被改过的 version
 * 依赖列表足以让<b>别人</b>生成的整合包变成断链包，那是跨用户污染，不是"他改自己的包自伤"。
 * 所以把带缓存的出网整体下沉到 {@link ModrinthFetcher}，本层在进入它之前就分流。
 *
 * <p>网站构筑、预览、抽卡、导入、偏好和卡片查询均在委派任务中执行。
 * 没有浏览器参与的离线工具仍使用受限流保护的服务器访问层。
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
     * 前者是网络故障，后者是 Modrinth 明确返回没有这个版本。
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
    /** 批量读取部分失败时，已核实资料与未核实状态分开传递，不把失败当作项目不存在。 */
    public static final class PartialProjectBatchException extends UnavailableException {
        private final Map<String, JsonNode> projects;
        public PartialProjectBatchException(Map<String, JsonNode> projects) {
            super("部分项目资料未能核实");
            this.projects = Map.copyOf(projects);
        }
        public Map<String, JsonNode> projects() { return projects; }
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
    public JsonNode getCompatibleVersions(String projectId, String mcVersion, String loaders) {
        DelegationContext.Answer answer = askClient(Kind.ENV_VERSIONS, envKey(projectId, mcVersion, loaders));
        if (answeredByClient(answer)) return answer.data();
        return fetcher.getCompatibleVersions(projectId, mcVersion, loaders);
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

    /**
     * 关键词搜索。
     *
     * <p>搜索的"事实"就是"在哪个 URL 上查"——所以这一类的 key 直接是完整 URL，
     * 由 {@link ModrinthFetcher#buildSearchUrl} 统一拼装，委派与自抓共用同一条口径。
     *
     * <p>之前不下放是因为它参数最多；实测下来它恰恰是服务器侧最后的出网来源
     * （"加附属"和"平替抢救"路径），不下放就永远做不到服务器零出网。
     */
    public JsonNode search(String query, int limit, String facets) {
        return searchPage(query, limit, 0, null, facets);
    }

    public JsonNode searchPage(String query, int limit, int offset, String index, String facets) {
        String url = ModrinthFetcher.buildSearchUrl(query, limit, offset, index, facets);
        DelegationContext.Answer answer = askClient(Kind.SEARCH, url);
        if (answeredByClient(answer)) return answer.data();
        return fetcher.searchByUrl(url);
    }

    /**
     * 批量预取项目元数据。
     *
     * <p>委派上下文里<b>直接跳过</b>：预取的意义是"一次请求灌一批进缓存"，而委派路径下每个节点
     * 本来就会各自去问浏览器、由浏览器批量——两条批量机制叠在一起只会白发一次服务器请求。
     */
    public void prefetchProjects(Collection<String> idsOrSlugs) {
        if (DelegationContext.current() != null && delegationTasks.isEnabled()) return;
        fetcher.prefetchProjects(idsOrSlugs);
    }

    /** 同上 */
    public void prefetchVersions(Collection<String> versionIds) {
        if (DelegationContext.current() != null && delegationTasks.isEnabled()) return;
        fetcher.prefetchVersions(versionIds);
    }

    /**
     * 批量取项目元数据。
     *
     * <p>委派上下文里退回逐键处理（走委派）：批量端点属于"服务器自己抓"那一档。
     * 并发登记项目需求，由浏览器合并成批量查询，避免逐键产生 HTTP 往返。
     */
    public Map<String, JsonNode> getProjectsBatch(Collection<String> idsOrSlugs) {
        Map<String, JsonNode> out = new LinkedHashMap<>();
        if (idsOrSlugs == null || idsOrSlugs.isEmpty()) return out;
        var context = DelegationContext.current();
        if (context != null) context.checkActive();
        if (context != null && delegationTasks.isEnabled()) {
            // 同时登记需求，使浏览器能使用批量端点；串行等待会产生每项目一次往返。
            var results = new java.util.concurrent.ConcurrentHashMap<String, JsonNode>();
            boolean incomplete = false;
            try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = idsOrSlugs.stream().distinct().map(key ->
                        yagen.waitmydawn.maa.runtime.ScopedExecutors.runAsync(() -> {
                            JsonNode value = getProjectInfo(key);
                            if (value != null) results.put(key, value);
                        }, executor)).toList();
                try { java.util.concurrent.CompletableFuture.allOf(futures.toArray(java.util.concurrent.CompletableFuture[]::new)).join(); }
                catch (java.util.concurrent.CompletionException unavailable) {
                    context.checkActive();
                    if (unavailable.getCause() instanceof Error fatal) throw fatal;
                    incomplete = true;
                }
            }
            for (String key : idsOrSlugs) if (results.containsKey(key)) out.put(key, results.get(key));
            context.checkActive();
            if (incomplete) throw new PartialProjectBatchException(out);
            return out;
        }
        var result = fetcher.getProjectsBatch(idsOrSlugs);
        if (context != null) context.checkActive();
        return result;
    }

    // ==========================================
    // 内部
    // ==========================================

    /**
     * 问一次浏览器。
     *
     * @return null 表示"这次没走委派"（开关关闭 / 不在任务里），调用方应自己抓；非 null 时看
     *         {@link Outcome}：GOT 用它的数据，ABSENT 就是"上游确实没有"，
     *         TIMEOUT 可退回服务器取数；取消任务必须直接结束。
     */
    private DelegationContext.Answer askClient(Kind kind, String key) {
        DelegationContext ctx = DelegationContext.current();
        if (ctx == null) return null;
        ctx.checkActive();
        if (!delegationTasks.isEnabled()) return null;
        ctx.checkActive();
        DelegationContext.Answer answer = ctx.await(kind, key, delegationTasks.callBudgetMs());
        ctx.checkActive();
        return answer;
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
