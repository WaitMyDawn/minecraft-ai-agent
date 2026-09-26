package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回合变更摘要（{@code <ops_summary>}）的离线测试。
 *
 * <p>这张摘要会直接渲染成用户能看到的"本轮变更"卡片，所以它必须满足两件事：
 * ① 无变更时不出现（前端就不会画空卡片）；② 有变更时是严格合法的 JSON
 * （前端 JSON.parse 失败就什么都不显示，等于用户又看不见了）。
 */
class PackSessionStateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("无变更：hasChanges=false，JSON 里也没有任何条目")
    void emptyStateHasNoChanges() throws Exception {
        PackSessionState state = new PackSessionState();

        assertFalse(state.hasChanges(), "没有任何 Tool 变更时不应产出 ops_summary");
        JsonNode json = mapper.readTree(state.toOpsJson());
        assertEquals(0, json.path("removed").size());
        assertEquals(0, json.path("categoryTargets").size());
        assertTrue(json.path("targetCount").isMissingNode(), "没设过数量就不该有这个字段");
        assertTrue(json.path("maxDownloads").isMissingNode());
        assertTrue(json.path("name").isMissingNode());
    }

    @Test
    @DisplayName("五类变更齐全：字段名与取值能被前端直接消费")
    void serializesAllChangeKinds() throws Exception {
        PackSessionState state = new PackSessionState();
        state.removeSlug("create");
        state.removeSlug("jei");
        state.putCategoryTarget("magic", 10);
        state.putCategoryTarget("technology", -5);
        state.setTargetCount(20);
        state.setMaxDownloads(500000L);
        state.setPackName("低配魔法冒险包");

        assertTrue(state.hasChanges());
        JsonNode json = mapper.readTree(state.toOpsJson());

        assertEquals(2, json.path("removed").size());
        assertEquals("create", json.path("removed").get(0).asText());
        assertEquals("jei", json.path("removed").get(1).asText());
        assertEquals(10, json.path("categoryTargets").path("magic").asInt());
        assertEquals(-5, json.path("categoryTargets").path("technology").asInt(), "负增量必须原样保留，前端要显示 -5");
        assertEquals(20, json.path("targetCount").asInt());
        assertEquals(500000L, json.path("maxDownloads").asLong());
        assertEquals("低配魔法冒险包", json.path("name").asText());
    }

    @Test
    @DisplayName("包名带引号/反斜杠/换行：JSON 仍然合法且内容原样还原")
    void escapesTrickyPackName() throws Exception {
        PackSessionState state = new PackSessionState();
        state.setPackName("A \"quoted\" \\ back\nnewline");

        JsonNode json = mapper.readTree(state.toOpsJson());
        assertEquals("A \"quoted\" \\ back\nnewline", json.path("name").asText());
    }

    @Test
    @DisplayName("同一类别多次调整：增量累加为一条，避免卡片上出现重复行")
    void mergesRepeatedCategoryAdjustments() throws Exception {
        PackSessionState state = new PackSessionState();
        state.putCategoryTarget("storage", 5);
        state.putCategoryTarget("storage", 7);

        JsonNode json = mapper.readTree(state.toOpsJson());
        assertEquals(1, json.path("categoryTargets").size());
        assertEquals(12, json.path("categoryTargets").path("storage").asInt());
    }

    @Test
    @DisplayName("环境切换也要进\"本轮变更\"卡片：只切环境时同样有变更、JSON 里带 env")
    void envChangeShowsUpInOps() throws Exception {
        PackSessionState state = new PackSessionState();
        assertFalse(state.hasChanges(), "什么都没发生 → 不该出现空卡片");

        state.setEnvChange("1.21.1 + neoforge → 26.2 + neoforge");
        assertTrue(state.hasChanges(), "只有环境变化也必须算变更（否则用户看不到切换）");

        JsonNode json = mapper.readTree(state.toOpsJson());
        assertEquals("1.21.1 + neoforge → 26.2 + neoforge", json.path("env").asText(),
                "卡片要显示\"从哪个环境切到哪个\"，不能只有一个目标值");
    }
}
