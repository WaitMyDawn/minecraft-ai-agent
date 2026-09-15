package yagen.waitmydawn.maa.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MAA 黄金测试集执行器（离线评测闭环）。
 *
 * 设计依据：固定题集 + 预先写死的判分标准 + 程序自动判分 + 可重复对比。
 * 题集见 eval/golden-set.json，本类只负责"跑题 + 判定 + 出报告"，期望值全部写在题集里。
 *
 * 性质：黑盒 HTTP 评测，被测对象是已经启动的应用（默认 http://localhost:8080）。
 * 默认不参与 mvn test，必须显式打开开关：
 *   mvnw -o test -Dtest=GoldenSetEval -Deval=true -Deval.layer=offline
 *   mvnw -o test -Dtest=GoldenSetEval -Deval=true -Deval.layer=agent -Deval.repeat=5 -Deval.label=baseline
 *
 * 可选参数：
 *   -Deval.layer=offline|agent|all  跑哪一层（默认 offline）
 *   -Deval.cases=A01,A02            只跑指定用例
 *   -Deval.repeat=5                 每题重复次数，用于稳定性统计（默认 1）
 *   -Deval.label=baseline           本轮标签，写进消融对比表
 *   -Deval.base=http://host:port    被测地址
 *
 * 输出：target/eval/&lt;label&gt;-&lt;时间戳&gt;.json（逐题明细）与 target/eval/summary.csv（追加一行）。
 */
@EnabledIfSystemProperty(named = "eval", matches = "true")
class GoldenSetEval {

    private static final String BASE = System.getProperty("eval.base", "http://localhost:8080");
    private static final Path SET_PATH = Paths.get(System.getProperty("eval.set", "eval/golden-set.json"));
    private static final String LAYER = System.getProperty("eval.layer", "offline");
    private static final int REPEAT = Math.max(1, Integer.parseInt(System.getProperty("eval.repeat", "1")));
    private static final String LABEL = System.getProperty("eval.label", "current");
    /**
     * 模型层用的 Key，通过 {@code X-LLM-Api-Key} 请求头传给后端。
     *
     * <p>用法：{@code -Deval.apikey=sk-xxx}，需要在启动应用时打开
     * {@code maa.eval.allow-key-override=true}（默认关闭）。这样评测不必把 Key 写进 .env。
     */
    private static final String API_KEY = resolveApiKey();

    /** Key 优先取 -Deval.apikey，其次取环境变量 DEEPSEEK_API_KEY（避免把明文 Key 写进命令行） */
    private static String resolveApiKey() {
        String direct = System.getProperty("eval.apikey", "");
        if (!direct.isBlank()) return direct;
        String env = System.getenv("DEEPSEEK_API_KEY");
        return env == null ? "" : env.trim();
    }
    private static final Set<String> ONLY = new LinkedHashSet<>();

    /** 过程层信号：回复里出现这些字样说明触发了对应诊断分支，是"过程可观测"的证据 */
    private static final List<String> PROCESS_SIGNALS = List.of(
            "无效的模组类别", "类别配比未完全兑现", "未能向 Modrinth 校验", "是保留模组的必需前置");

    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private CaseRun lastRun;

    static {
        for (String s : System.getProperty("eval.cases", "").split(",")) {
            if (!s.isBlank()) ONLY.add(s.trim());
        }
    }

    @Test
    void runGoldenSet() throws Exception {
        JsonNode root = mapper.readTree(Files.readAllBytes(SET_PATH));
        checkServerAlive();

        List<CaseRun> runs = new ArrayList<>();
        for (JsonNode c : root.path("cases")) {
            String id = c.path("id").asText();
            String layer = c.path("layer").asText();
            if (!onlyThis(layer) || (!ONLY.isEmpty() && !ONLY.contains(id))) continue;
            runs.add(runCase(c));
        }
        if (runs.isEmpty()) {
            throw new IllegalStateException("没有匹配的用例，检查 -Deval.layer / -Deval.cases 参数");
        }
        report(runs);
    }

    private boolean onlyThis(String layer) {
        return "all".equals(LAYER) || LAYER.equals(layer);
    }

    private void checkServerAlive() {
        try {
            HttpResponse<String> r = http.send(
                    HttpRequest.newBuilder(URI.create(BASE + "/api/meta/categories"))
                            .timeout(Duration.ofSeconds(10)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) {
                throw new IllegalStateException("被测服务返回 " + r.statusCode());
            }
        } catch (Exception e) {
            throw new IllegalStateException("被测服务不可达（" + BASE + "）：请先启动应用再跑评测。" + e.getMessage(), e);
        }
    }

    // ==================== 单题执行 ====================

    private CaseRun runCase(JsonNode c) {
        String id = c.path("id").asText();
        String layer = c.path("layer").asText();
        boolean agent = "agent".equals(layer);
        JsonNode a = c.path("assert");

        int passedRuns = 0;
        long totalMs = 0;
        CaseRun best = null;
        List<String> firstFail = null;
        Set<String> signals = new LinkedHashSet<>();

        for (int i = 0; i < REPEAT; i++) {
            CaseRun run = agent ? callChat(c, a) : callPreview(c, a);
            totalMs += run.elapsedMs;
            signals.addAll(run.signals);
            if (run.failures.isEmpty()) {
                passedRuns++;
                if (best == null) best = run;
            } else if (firstFail == null) {
                firstFail = new ArrayList<>(run.failures);
            }
        }
        CaseRun result = best != null ? best : lastRun;
        result.id = id;
        result.layer = layer;
        result.difficulty = c.path("difficulty").asText("");
        result.title = c.path("title").asText("");
        result.knownGap = c.path("knownGap").asText("");
        result.passedRuns = passedRuns;
        result.repeat = REPEAT;
        result.avgMs = totalMs / REPEAT;
        result.signals = signals;
        if (passedRuns == 0 && firstFail != null) {
            result.failures.clear();
            result.failures.addAll(firstFail);
        }
        return result;
    }

    // ==================== 离线层：/api/modpack/preview ====================

    private CaseRun callPreview(JsonNode c, JsonNode a) {
        CaseRun run = new CaseRun();
        JsonNode req = c.path("request");
        ObjectNode body = mapper.createObjectNode();
        body.put("name", req.path("name").asText("eval"));
        body.put("mcVersion", req.path("mcVersion").asText("1.21.1"));
        body.put("loader", req.path("loader").asText("neoforge"));
        ArrayNode slugs = body.putArray("modSlugs");
        req.path("modSlugs").forEach(n -> slugs.add(n.asText()));
        ArrayNode excluded = body.putArray("excludedSlugs");
        req.path("excludedSlugs").forEach(n -> excluded.add(n.asText()));

        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<String> res = post("/api/modpack/preview", body.toString());
            run.elapsedMs = System.currentTimeMillis() - t0;
            if (a.path("httpOk").asBoolean(true) && res.statusCode() != 200) {
                run.failures.add("HTTP " + res.statusCode());
                lastRun = run;
                return run;
            }
            JsonNode json = mapper.readTree(res.body());
            Set<String> nodeSlugs = new LinkedHashSet<>();
            Set<String> nodeIds = new LinkedHashSet<>();
            Set<String> missing = new LinkedHashSet<>();
            Set<String> requiredBy = new LinkedHashSet<>();
            int nodeCount = 0, dupIds = 0, conflicts = 0;
            for (JsonNode n : json.path("nodes")) {
                nodeCount++;
                nodeSlugs.add(n.path("slug").asText());
                if (!nodeIds.add(n.path("id").asText())) dupIds++;
            }
            for (JsonNode cf : json.path("conflicts")) {
                conflicts++;
                missing.add(cf.path("missing").asText());
                requiredBy.add(cf.path("requiredBy").asText());
            }
            run.nodeCount = nodeCount;

            expectContain(run, "nodesContain", a, nodeSlugs);
            expectAbsent(run, "nodesNotContain", a, nodeSlugs);
            if (a.has("minNodes") && nodeCount < a.path("minNodes").asInt()) {
                run.failures.add("nodes=" + nodeCount + " < minNodes=" + a.path("minNodes").asInt());
            }
            if (a.has("maxNodes") && nodeCount > a.path("maxNodes").asInt()) {
                run.failures.add("nodes=" + nodeCount + " > maxNodes=" + a.path("maxNodes").asInt());
            }
            if (a.path("conflictsEmpty").asBoolean(false) && conflicts > 0) {
                run.failures.add("期望无断链，实际 " + conflicts + " 条: " + missing);
            }
            expectContain(run, "conflictsMissing", a, missing);
            expectContain(run, "conflictsRequiredBy", a, requiredBy);
            if (a.path("uniqueNodeIds").asBoolean(false) && dupIds > 0) {
                run.failures.add("节点 id 重复 " + dupIds + " 次");
            }
        } catch (Exception e) {
            run.failures.add("请求异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        lastRun = run;
        return run;
    }

    // ==================== 模型层：/api/chat ====================

    private CaseRun callChat(JsonNode c, JsonNode a) {
        CaseRun run = new CaseRun();
        StringBuilder current = new StringBuilder();
        for (JsonNode n : c.path("currentMods")) {
            if (!current.isEmpty()) current.append(',');
            current.append(n.asText());
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", c.path("prompt").asText(""));
        body.put("currentMods", current.toString());
        body.put("uuid", "eval-" + c.path("id").asText());
        body.put("excludedSlugs", "");
        // F01：把包环境一起发过去（对应前端 sendMessage 的 mcVersion/loader 字段）
        JsonNode env = c.path("currentEnv");
        if (env.isObject()) {
            body.put("mcVersion", env.path("mcVersion").asText(""));
            body.put("loader", env.path("loader").asText(""));
        }

        long t0 = System.currentTimeMillis();
        try {
            HttpResponse<String> res = post("/api/chat", body.toString());
            run.elapsedMs = System.currentTimeMillis() - t0;
            String text = res.body() == null ? "" : res.body();
            run.rawText = text;
            if (a.path("httpOk").asBoolean(true) && res.statusCode() != 200) {
                run.failures.add("HTTP " + res.statusCode());
                lastRun = run;
                return run;
            }
            Set<String> finalMods = collectMods(text);
            run.nodeCount = finalMods.size();
            run.envMc = tag(text, "mc");
            run.envLoader = tag(text, "loader");
            readTrace(run, text);

            expectContain(run, "modsContain", a, finalMods);
            expectAbsent(run, "modsNotContain", a, finalMods);
            if (a.has("minMods") && finalMods.size() < a.path("minMods").asInt()) {
                run.failures.add("mods=" + finalMods.size() + " < minMods=" + a.path("minMods").asInt());
            }
            if (a.has("maxMods") && finalMods.size() > a.path("maxMods").asInt()) {
                run.failures.add("mods=" + finalMods.size() + " > maxMods=" + a.path("maxMods").asInt());
            }
            if (a.hasNonNull("envMc") && !a.path("envMc").asText().equals(run.envMc)) {
                run.failures.add("envMc=" + run.envMc + " 期望 " + a.path("envMc").asText());
            }
            if (a.hasNonNull("envLoader") && !a.path("envLoader").asText().equals(run.envLoader)) {
                run.failures.add("envLoader=" + run.envLoader + " 期望 " + a.path("envLoader").asText());
            }
            if (a.has("maxMs") && run.elapsedMs > a.path("maxMs").asInt()) {
                run.failures.add("耗时 " + run.elapsedMs + "ms > maxMs=" + a.path("maxMs").asInt());
            }
            for (JsonNode n : a.path("replyContains")) {
                if (!text.contains(n.asText())) run.failures.add("回复缺少关键字: " + n.asText());
            }
            for (JsonNode n : a.path("replyNotContains")) {
                if (text.contains(n.asText())) run.failures.add("回复出现禁止内容: " + n.asText());
            }
            for (String sig : PROCESS_SIGNALS) {
                if (text.contains(sig)) run.signals.add(sig);
            }
            assertTrace(run, a);
        } catch (Exception e) {
            run.failures.add("请求异常: " + e.getClass().getSimpleName() + " " + e.getMessage());
        }
        lastRun = run;
        return run;
    }

    // ==================== 工具方法 ====================

    private HttpResponse<String> post(String path, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMinutes(6))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        if (!API_KEY.isBlank()) b.header("X-LLM-Api-Key", API_KEY);
        return http.send(b.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** 从 &lt;mods&gt;a,b,c&lt;/mods&gt; 取最终模组集合 */
    private Set<String> collectMods(String text) {
        Set<String> set = new LinkedHashSet<>();
        Matcher m = Pattern.compile("<mods>(.*?)</mods>", Pattern.DOTALL).matcher(text);
        if (m.find()) {
            for (String s : m.group(1).split(",")) {
                String t = s.trim();
                if (!t.isEmpty()) set.add(t);
            }
        }
        return set;
    }

    private String tag(String text, String name) {
        Matcher m = Pattern.compile("<" + name + ">(.*?)</" + name + ">", Pattern.DOTALL).matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * 解析 {@code <trace>} 过程指标：token / 模型调用次数 / 依赖耗时 / 数量口径 / 诊断计数。
     *
     * <p>这是"过程层与成本层可自动断言"的关键——没有它，评测只能看到最终模组清单。
     */
    private void readTrace(CaseRun run, String text) {
        Matcher m = Pattern.compile("<trace>(.*?)</trace>", Pattern.DOTALL).matcher(text);
        if (!m.find()) return;
        run.rawTrace = m.group(1);
        try {
            JsonNode t = mapper.readTree(m.group(1));
            run.traceFound = true;
            run.inTokens = t.path("architect").path("in").asInt() + t.path("critic").path("in").asInt();
            run.outTokens = t.path("architect").path("out").asInt() + t.path("critic").path("out").asInt();
            run.modelCalls = t.path("architect").path("calls").asInt() + t.path("critic").path("calls").asInt();
            run.dependencyMs = t.path("dependencyMs").asLong();
            run.toolCalls = t.path("toolCalls").asInt();
            run.countRatio = t.path("count").path("ratio").asDouble();
            run.countInRange = t.path("count").path("inRange").asBoolean();
            run.trimSuggested = t.path("diagnostics").path("trimSuggestions").asInt() > 0;
            run.modOpsCount = t.path("modOps").path("replaced").size()
                    + t.path("modOps").path("dropped").size()
                    + t.path("modOps").path("deduped").size();
            run.criticDegraded = t.path("criticDegraded").asBoolean(false);
        } catch (Exception e) {
            run.failures.add("<trace> 解析失败: " + e.getMessage());
        }
    }

    /** trace 相关断言（题集里的 assert.trace 字段） */
    private void assertTrace(CaseRun run, JsonNode a) {
        JsonNode ta = a.path("trace");
        if (ta.isMissingNode()) return;
        if (ta.path("exists").asBoolean(false) && !run.traceFound) {
            run.failures.add("缺少 <trace> 过程指标");
            return;
        }
        if (ta.has("countInRange") && ta.path("countInRange").asBoolean() != run.countInRange) {
            run.failures.add("count.inRange=" + run.countInRange + " 期望 " + ta.path("countInRange").asBoolean()
                    + "（ratio=" + run.countRatio + "）");
        }
        if (ta.has("trimSuggested") && ta.path("trimSuggested").asBoolean() != run.trimSuggested) {
            run.failures.add("trimSuggestions=" + run.trimSuggested + " 期望 " + ta.path("trimSuggested").asBoolean());
        }
        // 条件断言（诚实性）：只要本轮真的对点名模组做了平替/剔除/去重（trace.modOps 非空），
        // 回复里就必须出现对应说明——以前这些只写日志，用户看不到自己的包被改了什么。
        if (ta.path("modOpsReported").asBoolean(false)) {
            run.modOpsReported = run.modOpsCount == 0
                    || (run.rawText != null && (run.rawText.contains("平替")
                        || run.rawText.contains("剔除") || run.rawText.contains("去重")));
            if (!run.modOpsReported) {
                run.failures.add("trace.modOps 有 " + run.modOpsCount + " 项调整，但回复里没有告知用户");
            }
        }
        // 条件断言（诚实性，坏例 B16）：审核员输出退化（被 token 上限截断/标签缺失）时，
        // 必须由 Java 补位兜底并如实告知用户，否则就是"包莫名缩小且没人解释"。
        if (ta.path("criticDegradedReported").asBoolean(false)) {
            run.criticDegradedReported = !run.criticDegraded
                    || (run.rawText != null && (run.rawText.contains("审核员")
                        || run.rawText.contains("截断")));
            if (!run.criticDegradedReported) {
                run.failures.add("trace.criticDegraded=true（审核员输出退化），但回复里没有如实告知");
            }
        }
        // 条件断言：产品规则是"数量 > 1.5 倍目标时才给删除建议"，
        // 直接断言 trimSuggested=true 会因为依赖膨胀程度不同而抖动，所以按条件判。
        if (ta.path("trimWhenOverRange").asBoolean(false)) {
            boolean over = run.countRatio > 1.5;
            if (over && !run.trimSuggested) {
                run.failures.add("ratio=" + run.countRatio + " 已超 1.5 倍上限，但未给出删除建议");
            }
            if (!over && run.trimSuggested) {
                run.failures.add("ratio=" + run.countRatio + " 未超上限却给出了删除建议");
            }
        }
        if (ta.has("maxTotalTokens")) {
            int total = run.inTokens + run.outTokens;
            if (total > ta.path("maxTotalTokens").asInt()) {
                run.failures.add("tokens=" + total + " > maxTotalTokens=" + ta.path("maxTotalTokens").asInt());
            }
        }
    }

    private void expectContain(CaseRun run, String field, JsonNode a, Set<String> actual) {
        for (JsonNode n : a.path(field)) {
            if (!actual.contains(n.asText())) run.failures.add(field + " 缺少: " + n.asText());
        }
    }

    private void expectAbsent(CaseRun run, String field, JsonNode a, Set<String> actual) {
        for (JsonNode n : a.path(field)) {
            if (actual.contains(n.asText())) run.failures.add(field + " 不应出现: " + n.asText());
        }
    }

    // ==================== 报告 ====================

    private void report(List<CaseRun> runs) throws Exception {
        System.out.println("\n================ MAA 黄金测试集 ================");
        System.out.printf("%-5s %-8s %-12s %-11s %-8s %s%n", "ID", "层次", "难度", "结果", "耗时ms", "说明");
        int pass = 0, knownGap = 0, fail = 0, advTotal = 0, advPass = 0;
        long sumMs = 0;
        List<Long> allMs = new ArrayList<>();
        Map<String, Set<String>> signalsByCase = new LinkedHashMap<>();

        for (CaseRun r : runs) {
            boolean ok = r.failures.isEmpty();
            String verdict;
            if (ok) {
                pass++;
                verdict = r.knownGap.isEmpty() ? "PASS" : "FIXED?";
            } else if (!r.knownGap.isEmpty()) {
                knownGap++;
                verdict = "KNOWN-GAP";
            } else {
                fail++;
                verdict = "FAIL";
            }
            if ("adversarial".equals(r.difficulty)) {
                advTotal++;
                if (ok) advPass++;
            }
            sumMs += r.avgMs;
            allMs.add(r.avgMs);
            if (!r.signals.isEmpty()) signalsByCase.put(r.id, r.signals);
            String note = ok ? (r.knownGap.isEmpty() ? "" : "已知缺口已修复") : String.join("; ", r.failures);
            System.out.printf("%-5s %-8s %-12s %-11s %-8d %s%n", r.id, r.layer, r.difficulty, verdict, r.avgMs, note);
        }

        int total = runs.size();
        double rate = 100.0 * pass / total;
        java.util.Collections.sort(allMs);
        long p50 = allMs.get(allMs.size() / 2);
        long p95 = allMs.get(Math.min(allMs.size() - 1, (int) Math.ceil(allMs.size() * 0.95) - 1));
        double advRate = advTotal == 0 ? 0 : 100.0 * advPass / advTotal;

        System.out.println("-------------------------------------------------");
        System.out.printf("结果层  硬约束通过率: %.1f%% (%d/%d)  |  FAIL=%d  KNOWN-GAP=%d%n",
                rate, pass, total, fail, knownGap);
        System.out.printf("健壮性  刁难题通过率: %.1f%% (%d/%d)%n", advRate, advPass, advTotal);
        long sumIn = runs.stream().mapToLong(r -> r.inTokens).sum();
        long sumOut = runs.stream().mapToLong(r -> r.outTokens).sum();
        int sumCalls = runs.stream().mapToInt(r -> r.modelCalls).sum();
        int traceCount = (int) runs.stream().filter(r -> r.traceFound).count();
        System.out.printf("成本层  平均耗时: %dms  P50: %dms  P95: %dms%n", sumMs / total, p50, p95);
        System.out.printf("        输入 token: %d  输出 token: %d  模型调用: %d 次  (trace 覆盖 %d/%d 题)%n",
                sumIn, sumOut, sumCalls, traceCount, total);
        if (REPEAT > 1) {
            int stable = 0;
            for (CaseRun r : runs) if (r.passedRuns == REPEAT) stable++;
            System.out.printf("稳定性  同题 %d 次全过: %d/%d (%.1f%%)%n", REPEAT, stable, total, 100.0 * stable / total);
        }
        System.out.println("过程层  触发的诊断分支: " + (signalsByCase.isEmpty() ? "无" : signalsByCase.toString()));
        long sumTool = runs.stream().mapToLong(r -> r.toolCalls).sum();
        long sumDep = runs.stream().mapToLong(r -> r.dependencyMs).sum();
        System.out.printf("        工具调用合计: %d 次  依赖穿透耗时合计: %dms%n", sumTool, sumDep);
        System.out.println("口径提醒同一应用进程内二次运行会命中依赖解析缓存(10 分钟 TTL)，耗时明显偏低；");
        System.out.println("        要测冷启动成本请重启应用后再跑，并用 -Deval.label 区分（如 baseline-cold / baseline-warm）。");
        System.out.println("=================================================");

        writeArtifacts(runs, rate, advRate, sumMs / total, p50, p95);
        org.junit.jupiter.api.Assertions.assertTrue(fail == 0,
                "有 " + fail + " 条非已知缺口的用例未通过（详见上方表格与 target/eval/）");
    }

    private void writeArtifacts(List<CaseRun> runs, double rate, double advRate,
                                long avgMs, long p50, long p95) throws Exception {
        Path dir = Paths.get("target/eval");
        Files.createDirectories(dir);
        String ts = Instant.now().toString().replace(':', '-');

        ObjectNode out = mapper.createObjectNode();
        out.put("label", LABEL);
        out.put("layer", LAYER);
        out.put("repeat", REPEAT);
        out.put("timestamp", ts);
        out.put("passRate", rate);
        out.put("adversarialRate", advRate);
        out.put("avgMs", avgMs);
        out.put("p50Ms", p50);
        out.put("p95Ms", p95);
        ArrayNode cases = out.putArray("cases");
        for (CaseRun r : runs) {
            ObjectNode o = cases.addObject();
            o.put("id", r.id);
            o.put("layer", r.layer);
            o.put("difficulty", r.difficulty);
            o.put("title", r.title);
            o.put("passed", r.failures.isEmpty());
            o.put("passedRuns", r.passedRuns);
            o.put("repeat", r.repeat);
            o.put("avgMs", r.avgMs);
            o.put("modsOrNodes", r.nodeCount);
            o.put("envMc", r.envMc);
            o.put("envLoader", r.envLoader);
            o.put("inTokens", r.inTokens);
            o.put("outTokens", r.outTokens);
            o.put("modelCalls", r.modelCalls);
            o.put("toolCalls", r.toolCalls);
            o.put("dependencyMs", r.dependencyMs);
            o.put("trace", r.rawTrace);
            o.put("countRatio", r.countRatio);
            o.put("countInRange", r.countInRange);
            o.put("trimSuggested", r.trimSuggested);
            o.put("modOpsCount", r.modOpsCount);
            o.put("modOpsReported", r.modOpsReported);
            o.put("criticDegraded", r.criticDegraded);
            o.put("criticDegradedReported", r.criticDegradedReported);
            ArrayNode f = o.putArray("failures");
            r.failures.forEach(f::add);
            ArrayNode s = o.putArray("signals");
            r.signals.forEach(s::add);
        }
        Path file = dir.resolve(LABEL + "-" + ts + ".json");
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(out));

        Path csv = dir.resolve("summary.csv");
        long sumIn = runs.stream().mapToLong(r -> r.inTokens).sum();
        long sumOut = runs.stream().mapToLong(r -> r.outTokens).sum();
        int sumCalls = runs.stream().mapToInt(r -> r.modelCalls).sum();
        long sumTool = runs.stream().mapToLong(r -> r.toolCalls).sum();
        String row = String.format("%s,%s,%s,%d,%d,%.1f,%.1f,%d,%d,%d,%d,%d,%d,%d%n",
                ts, LABEL, LAYER, REPEAT, runs.size(), rate, advRate, avgMs, p50, p95,
                sumIn, sumOut, sumCalls, sumTool);
        appendRow(csv, row);
        // 长周期对比表必须落在版本控制内的目录：target/ 被 gitignore，历史对比只留在那里等于没留
        Path longTerm = Paths.get("eval/results/ablation.csv");
        Files.createDirectories(longTerm.getParent());
        appendRow(longTerm, row);
        System.out.println("明细: " + file.toAbsolutePath());
        System.out.println("本次对比表: " + csv.toAbsolutePath());
        System.out.println("长周期对比表(入库): " + longTerm.toAbsolutePath());
    }

    /** 两份汇总表共用同一列定义（列名相同，读者按列名取值；早期缺列的行留空） */
    private static final String CSV_HEADER = "timestamp,label,layer,repeat,cases,passRate,adversarialRate,"
            + "avgMs,p50Ms,p95Ms,inTokens,outTokens,modelCalls,toolCalls\n";

    private static void appendRow(Path file, String row) throws java.io.IOException {
        if (!Files.exists(file)) Files.writeString(file, CSV_HEADER);
        Files.writeString(file, row, java.nio.file.StandardOpenOption.APPEND);
    }

    /** 单题结果 */
    private static class CaseRun {
        String id, layer, difficulty, title, knownGap, envMc, envLoader;
        /** 原始 <trace> JSON 文本，原样带进产物，方便事后分析任意新增字段 */
        String rawTrace;
        /** 原始回复文本（含 XML 标签），用于"回复里有没有如实告知"这类断言 */
        String rawText;
        /** trace.modOps 里的调整项数（平替 + 剔除 + 去重） */
        int modOpsCount;
        /** 有调整时，回复里是否确实告知了用户 */
        boolean modOpsReported = true;
        int nodeCount, inTokens, outTokens, modelCalls, toolCalls;
        boolean traceFound, countInRange, trimSuggested;
        /** trace.criticDegraded：审核员输出是否退化（标签缺失/未闭合） */
        boolean criticDegraded;
        /** 退化时回复里是否确实告知了用户 */
        boolean criticDegradedReported = true;
        double countRatio;
        long elapsedMs, avgMs, dependencyMs;
        int passedRuns = 1, repeat = 1;
        List<String> failures = new ArrayList<>();
        Set<String> signals = new LinkedHashSet<>();
    }
}
