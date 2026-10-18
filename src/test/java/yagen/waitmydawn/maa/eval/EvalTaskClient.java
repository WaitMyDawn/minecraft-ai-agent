package yagen.waitmydawn.maa.eval;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/** 黑盒评测客户端：推进已有任务，客户端取数与服务器回退保持独立。 */
final class EvalTaskClient {
    record Reply(int statusCode, String body) {}
    interface FactsProvider { ObjectNode fulfil(JsonNode wanted) throws Exception; }
    private final URI base;
    private final String token;
    private final String apiKey;
    private final Duration budget;
    private final long pollMs;
    private final FactsProvider facts;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    EvalTaskClient(String base, String token, String apiKey) {
        this(base, token, apiKey, Duration.ofMinutes(20), 1000, new ModrinthFacts());
    }

    EvalTaskClient(String base, String token, String apiKey, Duration budget, long pollMs, FactsProvider facts) {
        this.base = URI.create(base);
        this.token = token;
        this.apiKey = apiKey;
        this.budget = budget;
        this.pollMs = pollMs;
        this.facts = facts;
    }

    Reply checkReady() throws Exception {
        requireToken();
        return send("GET", "/api/meta/categories", null, System.nanoTime() + budget.toNanos(), false);
    }

    Reply post(String path, String json) throws Exception {
        requireToken();
        long deadline = System.nanoTime() + budget.toNanos();
        String taskId = null;
        boolean operation = false;
        try {
            Reply reply = send("POST", path, json, deadline, path.equals("/api/chat"));
            for (int rounds = 0; ; rounds++) {
                if (reply.statusCode() < 200 || reply.statusCode() >= 300) return reply;
                JsonNode envelope;
                try { envelope = mapper.readTree(reply.body()); }
                catch (RuntimeException e) { return reply; }
                if (envelope == null) return reply;
                if (envelope.hasNonNull("error")) throw new IllegalStateException("评测接口返回业务错误");
                String stage = envelope.path("stage").asText();
                if (!Set.of("queued", "running", "need-data").contains(stage)) {
                    if (envelope.has("stage")) throw new IllegalStateException("未知评测任务阶段");
                    return reply;
                }
                String nextId = envelope.path("taskId").asText();
                if (!nextId.matches("[A-Za-z0-9-]{1,128}") || (taskId != null && !taskId.equals(nextId))) {
                    throw new IllegalStateException("无效或变化的任务标识");
                }
                boolean nextOperation = envelope.path("operation").asBoolean(false);
                if (taskId != null && operation != nextOperation) throw new IllegalStateException("任务类型发生变化");
                taskId = nextId;
                operation = nextOperation;
                if (rounds >= 2000 || System.nanoTime() >= deadline) throw new IllegalStateException("评测任务超时或超过轮次限额");
                String taskPath = operation ? "/api/operations/" + taskId : "/api/chat/task/" + taskId;
                if (stage.equals("need-data")) {
                    ObjectNode body = facts.fulfil(envelope.path("wanted"));
                    reply = send("POST", taskPath + "/facts", body.toString(), deadline, false);
                } else {
                    Thread.sleep(pollMs);
                    reply = send(operation ? "GET" : "POST", operation ? taskPath : taskPath + "/facts",
                            operation ? null : "{}", deadline, false);
                }
            }
        } catch (Exception e) {
            if (taskId != null) {
                try {
                    send("POST", operation ? "/api/operations/" + taskId + "/cancel" : "/api/chat/abort",
                            operation ? "{}" : "{\"uuid\":\"default-user\"}",
                            System.nanoTime() + Duration.ofSeconds(5).toNanos(), false);
                } catch (Exception ignored) { /* 原始失败优先；取消仅为释放任务资源。 */ }
            }
            throw e;
        }
    }

    private void requireToken() {
        if (token == null || token.isBlank()) throw new IllegalStateException("评测需要已登录测试账号的 MAA_EVAL_AUTH_TOKEN");
    }

    private Reply send(String method, String path, String body, long deadline, boolean overrideKey) throws Exception {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("评测任务等待超时");
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path))
                .timeout(Duration.ofNanos(Math.min(remaining, Duration.ofMinutes(6).toNanos())))
                .header("X-Auth-Token", token);
        if (overrideKey && apiKey != null && !apiKey.isBlank()) builder.header("X-LLM-Api-Key", apiKey);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        HttpResponse<String> reply = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Reply(reply.statusCode(), reply.body());
    }

    /** JVM 取数用于协议评测，不代表浏览器 CORS、网络或下载行为已通过验收。 */
    static final class ModrinthFacts implements FactsProvider {
        private final ObjectMapper mapper = new ObjectMapper();
        private final HttpClient http;
        static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
        ModrinthFacts() { this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()); }
        ModrinthFacts(HttpClient http) { this.http = http; }
        private long nextRequest;
        private int requests;
        private int rateLimited;
        private long batchDeadline;
        private String lastFailure = "UNSPECIFIED";

        @Override public ObjectNode fulfil(JsonNode wanted) throws Exception {
            ObjectNode result = mapper.createObjectNode();
            var answered = result.putArray("answered");
            var missing = result.putArray("missing");
            var unavailable = result.putArray("unavailable");
            int before = requests, before429 = rateLimited;
            batchDeadline = System.nanoTime() + Duration.ofSeconds(110).toNanos();
            for (String kind : new String[]{"project", "version"}) {
                var wants = new java.util.ArrayList<JsonNode>();
                wanted.forEach(w -> { if (kind.equals(w.path("kind").asText())) wants.add(w); });
                for (int offset = 0; offset < wants.size(); offset += 100) {
                    var chunk = wants.subList(offset, Math.min(offset + 100, wants.size()));
                    var ids = mapper.createArrayNode();
                    chunk.forEach(w -> ids.add(w.path("key").asText()));
                    JsonNode data = fetch("https://api.modrinth.com/v2/" + (kind.equals("project") ? "projects" : "versions")
                            + "?ids=" + encode(ids.toString()));
                    boolean valid = data != null && validArray(data, kind.equals("project"));
                    for (JsonNode w : chunk) {
                        JsonNode hit = null;
                        if (valid) for (JsonNode item : data) {
                            if (w.path("key").asText().equals(item.path("id").asText())
                                    || w.path("key").asText().equals(item.path("slug").asText())) { hit = item; break; }
                        }
                        ObjectNode receipt = receipt(w);
                        if (hit != null) { receipt.set("data", hit); answered.add(receipt); }
                        else if (valid) missing.add(receipt);
                        else unavailable.add(receipt.put("reason", data == null ? lastFailure : "INVALID_RESPONSE"));
                    }
                }
            }
            var envLoads = new java.util.HashMap<String, JsonNode>();
            var envFailures = new java.util.HashMap<String, String>();
            int coalesced = 0;
            for (JsonNode w : wanted) {
                String kind = w.path("kind").asText();
                if (kind.equals("project") || kind.equals("version")) continue;
                ObjectNode receipt = receipt(w);
                String url;
                if (kind.equals("search")) url = w.path("key").asText();
                else if (kind.equals("env_version") || kind.equals("env_versions")) {
                    url = "https://api.modrinth.com/v2/project/" + encode(w.path("project").asText())
                            + "/version?game_versions=" + encode(mapper.createArrayNode().add(w.path("mc").asText()).toString())
                            + "&loaders=" + encode(mapper.createArrayNode().add(w.path("loader").asText()).toString());
                } else throw new IllegalStateException("不支持的委派事实类型: " + kind);
                boolean environment = kind.equals("env_version") || kind.equals("env_versions");
                JsonNode data;
                if (environment && envLoads.containsKey(url)) { data = envLoads.get(url); lastFailure = envFailures.get(url); coalesced++; }
                else {
                    data = fetch(url);
                    if (environment) { envLoads.put(url, data); envFailures.put(url, lastFailure); }
                }
                if (data == null || (kind.equals("search") && !data.path("hits").isArray())
                        || (environment && !validArray(data, false))) unavailable.add(receipt.put("reason", data == null ? lastFailure : "INVALID_RESPONSE"));
                else if ((kind.equals("env_version") || kind.equals("env_versions")) && data.isEmpty()) missing.add(receipt);
                else {
                    if (kind.equals("env_versions")) {
                        var limited = mapper.createArrayNode();
                        for (int i = 0; i < Math.min(4, data.size()); i++) limited.add(data.get(i));
                        receipt.set("data", limited);
                    } else receipt.set("data", kind.equals("env_version") ? data.get(0) : data);
                    answered.add(receipt);
                }
            }
            var client = result.putObject("client");
            client.put("requests", requests - before);
            client.put("rateLimited", rateLimited - before429);
            client.put("via", "eval-jvm");
            client.put("coalesced", coalesced);
            return result;
        }

        private ObjectNode receipt(JsonNode want) {
            ObjectNode receipt = mapper.createObjectNode();
            receipt.put("kind", want.path("kind").asText());
            receipt.put("key", want.path("key").asText());
            return receipt;
        }

        private static boolean validArray(JsonNode data, boolean projects) {
            if (!data.isArray()) return false;
            for (var item : data) if (!item.isObject() || !item.path("id").isTextual() || item.path("id").asText().isBlank()
                    || projects && !item.path("slug").isTextual()) return false;
            return true;
        }

        private JsonNode fetch(String url) throws Exception {
            lastFailure = "UNSPECIFIED";
            URI uri = URI.create(url);
            if (!"https".equals(uri.getScheme()) || !"api.modrinth.com".equals(uri.getHost())
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                    || !uri.getPath().startsWith("/v2/")) throw new IllegalArgumentException("非法 Modrinth 取数地址");
            for (int attempt = 0; attempt < 3; attempt++) {
                if (System.nanoTime() >= batchDeadline) { lastFailure = "BUDGET_EXHAUSTED"; return null; }
                // 跨事实批次保持 240 次/分钟上限，不将应用凭据发送给上游。
                long wait = nextRequest - System.nanoTime();
                if (wait > 0) java.util.concurrent.TimeUnit.NANOSECONDS.sleep(wait);
                long remaining = batchDeadline - System.nanoTime();
                if (remaining <= 0) { lastFailure = "BUDGET_EXHAUSTED"; return null; }
                nextRequest = System.nanoTime() + Duration.ofMillis(250).toNanos();
                requests++;
                HttpResponse<String> response;
                var pending = http.sendAsync(HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.min(remaining, REQUEST_TIMEOUT.toNanos())))
                        .header("User-Agent", "MAA-Eval/1.0").GET().build(), HttpResponse.BodyHandlers.ofString());
                try {
                    response = pending.get(Math.min(remaining, REQUEST_TIMEOUT.toNanos()), java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    Throwable cause = e.getCause() == null ? e : e.getCause();
                    lastFailure = cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.util.concurrent.TimeoutException
                            ? "REQUEST_TIMEOUT" : "NETWORK_ERROR";
                    return null;
                }
                finally { if (!pending.isDone()) pending.cancel(true); }
                if (response.statusCode() == 429) {
                    rateLimited++;
                    if (attempt == 2) { lastFailure = "RATE_LIMITED"; return null; }
                    long delay = response.headers().firstValue("x-ratelimit-reset").map(value -> {
                        try { return Math.min(60, Math.max(1, Long.parseLong(value))) * 1000; }
                        catch (NumberFormatException e) { return 1000L; }
                    }).orElse(1000L);
                    Thread.sleep(Math.min(delay, Math.max(0, Duration.ofNanos(batchDeadline - System.nanoTime()).toMillis())));
                    continue;
                }
                // 环境版本列表的明确 404 与浏览器回执一致，其他端点错误仍视为取数失败。
                if (response.statusCode() == 404 && uri.getPath().endsWith("/version")) return mapper.createArrayNode();
                if (response.statusCode() < 200 || response.statusCode() >= 300) { lastFailure = "UPSTREAM_HTTP"; return null; }
                try { return mapper.readTree(response.body()); }
                catch (RuntimeException e) { lastFailure = "INVALID_RESPONSE"; return null; }
            }
            return null;
        }

        private static String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }
}
