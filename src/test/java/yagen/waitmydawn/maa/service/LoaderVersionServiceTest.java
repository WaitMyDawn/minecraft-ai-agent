package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.lang.reflect.Field;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.containsString;

/**
 * 加载器版本服务的离线测试（不联网，喂固定的上游片段）。
 *
 * <p>这个类存在的意义：这张表决定"打包时 mrpack 里写的 loader 版本"，而它原来是**只更新不发现**，
 * 导致新 MC 版本（Forge 26.3）永远进不了表；而且表里的值是人工挑的子集。重做之后，风险点集中在
 * 三处纯逻辑上，所以这里逐个盯死：
 * ① NeoForge 版本号 → MC 版本（含 26.x 的日期式版本号、旧 artifact 的 {@code 1.20.1-47.1.106}）；
 * ② 挑选策略（正式版 &gt; 最新 beta &gt; 最新 alpha，必须有可用版本）；
 * ③ 合并语义（只增不删 / 已有键更新 / manual 键不覆盖 / 预发布标记每次重建）。
 */
class LoaderVersionServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Set<String> setOf(String... items) {
        return new LinkedHashSet<>(java.util.List.of(items));
    }

    @Test
    @DisplayName("NeoForge 版本号 → MC 版本：族与 MC 1:1；26.x 第三段为 0 时省略")
    void neoforgeVersionToMcVersion() {
        // 1.20.4 ← 20.4.251 是取 jar 的 MANIFEST（Specification-Version: 1.20.4）验证过的
        assertEquals("1.20.2", LoaderVersionService.mcVersionOfNeoForge("20.2.93"));
        assertEquals("1.20.4", LoaderVersionService.mcVersionOfNeoForge("20.4.251"));
        assertEquals("1.20.6", LoaderVersionService.mcVersionOfNeoForge("20.6.141"));
        assertEquals("1.21", LoaderVersionService.mcVersionOfNeoForge("21.0.167"));
        assertEquals("1.21.1", LoaderVersionService.mcVersionOfNeoForge("21.1.251"));
        assertEquals("1.21.11", LoaderVersionService.mcVersionOfNeoForge("21.11.45"));
        // 26.x 起是日期式版本号，且可能带 alpha/beta 与构建元数据
        assertEquals("26.1", LoaderVersionService.mcVersionOfNeoForge("26.1.0.0-alpha.15+pre-3"));
        assertEquals("26.1.1", LoaderVersionService.mcVersionOfNeoForge("26.1.1.15-beta"));
        assertEquals("26.1.2", LoaderVersionService.mcVersionOfNeoForge("26.1.2.109"));
        assertEquals("26.2", LoaderVersionService.mcVersionOfNeoForge("26.2.0.88"));
        assertEquals("26.3", LoaderVersionService.mcVersionOfNeoForge("26.3.0.22-beta"));
        // 旧 artifact 的版本把 MC 写在前面，不能按新规则解析（由 legacy 分支处理）
        assertNull(LoaderVersionService.mcVersionOfNeoForge("1.20.1-47.1.106"));
    }

    @Test
    @DisplayName("挑选策略：正式版 > 最新 beta > 最新 alpha（必须有可用版本）")
    void pickPreference() {
        LoaderVersionService.Discovery d = LoaderVersionService.discoverNeoForge(
                setOf("26.3.0.0-beta", "26.3.0.3-alpha", "26.3.0.22-beta"), Set.of());
        assertEquals("26.3.0.22-beta", d.best.get("26.3"), "同通道取最新");

        d = LoaderVersionService.discoverNeoForge(setOf("26.1.2.71-beta", "26.1.2.109"), Set.of());
        assertEquals("26.1.2.109", d.best.get("26.1.2"), "有正式版就不该选 beta");

        d = LoaderVersionService.discoverNeoForge(setOf("26.1.0.0-alpha.15+pre-3"), Set.of());
        assertEquals("26.1.0.0-alpha.15+pre-3", d.best.get("26.1"), "只有 alpha 时也要给一个可用版本");
    }

    @Test
    @DisplayName("MC 1.20.1 的 NeoForge 在旧 artifact（net.neoforged:forge）下，也要被发现")
    void legacyArtifactIsDiscovered() {
        LoaderVersionService.Discovery d = LoaderVersionService.discoverNeoForge(Set.of(),
                setOf("1.20.1-47.1.105", "1.20.1-47.1.106", "1.20.1-47.1.107-beta"));
        assertEquals("47.1.106", d.best.get("1.20.1"), "取最新正式版，且写进 mrpack 的是去掉 MC 前缀的版本号");
    }

    @Test
    @DisplayName("Forge promotions：latest 压过 recommended，历史版本一并发现")
    void forgeDiscovery() {
        ObjectNode holder = MAPPER.createObjectNode();
        ObjectNode promos = holder.putObject("promos");
        promos.put("1.20.1-latest", "47.4.23");
        promos.put("1.20.1-recommended", "47.3.0");
        promos.put("1.7.10-recommended", "10.13.4.1614");
        promos.put("26.3-latest", "66.0.1");

        LoaderVersionService.Discovery d = LoaderVersionService.discoverForge(promos);
        assertEquals("47.4.23", d.best.get("1.20.1"));
        assertEquals("10.13.4.1614", d.best.get("1.7.10"), "老版本也要进来（用户要求全收）");
        assertEquals("66.0.1", d.best.get("26.3"), "新版本要能被发现 —— 旧实现就是漏在这里");
        assertEquals(3, d.best.size());
    }

    @Test
    @DisplayName("合并语义：只增不删 / 已有键更新 / manual 不覆盖 / 预发布表每次重建")
    void mergeSemantics() {
        ObjectNode gameVersions = MAPPER.createObjectNode();
        gameVersions.put("1.20.1", "47.4.0");     // 本次会被更新
        gameVersions.put("1.16.5", "36.2.34");    // 上游本次没发现 → 必须保留
        gameVersions.put("26.3", "手工钉死");      // manual → 不许被覆盖
        ObjectNode prerelease = MAPPER.createObjectNode();
        ArrayNode manual = MAPPER.createObjectNode().putArray("manual");
        manual.add("26.3");

        LoaderVersionService.Discovery d = new LoaderVersionService.Discovery();
        d.offer("1.20.1", "47.4.23", 3);
        d.offer("1.7.10", "10.13.4.1614", 3);
        d.offer("26.3", "26.3.0.22-beta", 2);

        LoaderVersionService.MergeResult r = LoaderVersionService.mergeInto(gameVersions, prerelease, d, manual);

        assertEquals("47.4.23", gameVersions.path("1.20.1").asText(), "已有键应被更新");
        assertEquals("36.2.34", gameVersions.path("1.16.5").asText(), "上游没发现的旧键不能被删");
        assertEquals("手工钉死", gameVersions.path("26.3").asText(), "manual 键不被覆盖");
        assertEquals("10.13.4.1614", gameVersions.path("1.7.10").asText(), "发现到的新键要加进来");
        assertEquals(1, r.added());
        assertEquals(1, r.updated());
        assertTrue(prerelease.path("26.3").isMissingNode(), "manual 项不参与预发布标记（我们没写它的值）");

        // 第二次：26.3 上游出了正式版，且人工取消钉死 → 标记应被清掉
        gameVersions.remove("26.3");
        LoaderVersionService.Discovery d2 = new LoaderVersionService.Discovery();
        d2.offer("26.3", "26.3.0.30", 3);
        LoaderVersionService.mergeInto(gameVersions, prerelease,
                d2, MAPPER.createObjectNode().putArray("manual"));
        assertTrue(prerelease.path("26.3").isMissingNode(), "升到正式版后必须退出预发布表（每次重建）");
        assertEquals("26.3.0.30", gameVersions.path("26.3").asText());
    }

    @Test
    @DisplayName("maven 元数据解析：只取 <version> 标签")
    void parseMavenVersions() {
        String xml = "<metadata><versioning><versions>"
                + "<version>20.4.251</version><version>26.3.0.22-beta</version>"
                + "</versions><lastUpdated>20260926000000</lastUpdated></versioning></metadata>";
        assertEquals(setOf("20.4.251", "26.3.0.22-beta"), LoaderVersionService.parseMavenVersions(xml));
    }

    @Test
    @DisplayName("重试策略：只对'可能自己好起来'的失败重试（超时/连接失败/5xx），4xx 立刻放弃")
    void retryPolicy() {
        assertTrue(LoaderVersionService.retryable(
                new ResourceAccessException("Read timed out", new SocketTimeoutException())),
                "读超时要重试 —— 这就是线上偶发的那条");
        assertTrue(LoaderVersionService.retryable(
                new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR)), "5xx 要重试");
        assertFalse(LoaderVersionService.retryable(
                new HttpClientErrorException(HttpStatus.NOT_FOUND)), "404 重试也不会变好");
    }

    @Test
    @DisplayName("一个上游源失败，不影响其它源的合并与落盘（旧实现是全有或全无）")
    void oneSourceFailureDoesNotBlockOthers() throws Exception {
        Path dir = Files.createTempDirectory("maa-loader-test");
        Path file = dir.resolve("loader-versions.json");
        Files.writeString(file, "{\"updatedAt\":\"2026-01-01T00:00:00Z\",\"loaders\":{"
                + "\"neoforge\":{\"gameVersions\":{}},"
                + "\"forge\":{\"gameVersions\":{}},"
                + "\"fabric-loader\":{\"gameVersions\":{\"*\":\"0.0.0\"}}}}", StandardCharsets.UTF_8);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LoaderVersionService svc = new LoaderVersionService(builder.build(), MAPPER);
        setField(svc, "versionsPath", file.toString());
        setField(svc, "retryBackoffBaseMs", 0L);                    // 测试里不等退避
        setField(svc, "retriesLeft", new AtomicInteger(0));         // 也不安排 60 秒后的延迟重试
        svc.init();

        server.expect(requestTo(containsString("net/neoforged/neoforge/maven-metadata.xml")))
                .andRespond(withSuccess("<version>21.1.251</version>", MediaType.APPLICATION_XML));
        // neoforge 的旧 artifact（MC 1.20.1 那批）持续 500 → 会重试 3 次后判失败
        server.expect(ExpectedCount.times(3), requestTo(containsString("net/neoforged/forge/maven-metadata.xml")))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
        server.expect(requestTo(containsString("promotions_slim.json")))
                .andRespond(withSuccess("{\"promos\":{\"1.20.1-latest\":\"47.4.23\"}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(containsString("meta.fabricmc.net")))
                .andRespond(withSuccess("[{\"version\":\"0.19.5\",\"stable\":true}]", MediaType.APPLICATION_JSON));

        svc.refreshAll();
        server.verify();

        ObjectNode after = (ObjectNode) MAPPER.readTree(Files.readString(file));
        ObjectNode loaders = (ObjectNode) after.path("loaders");
        assertEquals("21.1.251",
                loaders.path("neoforge").path("gameVersions").path("1.21.1").asText(),
                "主源成功就要合并（旧源失败不该把主源的成果一起丢掉）");
        assertEquals("47.4.23",
                loaders.path("forge").path("gameVersions").path("1.20.1").asText(),
                "forge 排在 neoforge 之后，但它照样要刷新 —— 旧实现在这里会整个跳过");
        assertEquals("0.19.5",
                loaders.path("fabric-loader").path("gameVersions").path("*").asText(), "fabric 同理");
        assertTrue(loaders.path("neoforge").path("prerelease").path("1.21.1").isMissingNode(),
                "正式版不该进 prerelease 表");

        Files.deleteIfExists(file);
        Files.deleteIfExists(dir);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
