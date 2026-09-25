package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.runtime.DelegationContext.Kind;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回执结构校验的离线测试。
 *
 * <p>这里的检查<b>不是验真</b>——下载地址、依赖列表客户端都能编。它只挡住明显不对的东西，
 * 免得脏数据把流程带偏。之所以这样就够了：回执只在本次任务里用，既不进共享缓存、也不会传播给别人，
 * 最坏结果是发起者自己拿到一个错包（自伤）。
 *
 * <p>但有一条必须挡住：{@code ENV_VERSION} 声称"这个版本支持目标 mc + loader"时，
 * 返回的版本对象里必须真的声明了这两个。否则"这个模组能装"就是空口无凭，
 * 而它直接决定模组进不进入最终包。
 */
class DelegatedFactsGuardTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    @DisplayName("kind 解析：大小写不敏感，未知返回 null 而不是抛异常")
    void parseKindIsForgiving() {
        assertSame(Kind.PROJECT, ChatController.parseKind("project"));
        assertSame(Kind.ENV_VERSION, ChatController.parseKind("ENV_VERSION"));
        assertSame(Kind.ENV_VERSION, ChatController.parseKind("env_version"));
        assertNull(ChatController.parseKind("drop-tables"));
        assertNull(ChatController.parseKind(""));
    }

    @Test
    @DisplayName("项目事实：返回的 id 或 slug 必须和请求的键对得上")
    void projectKeyMustMatch() throws Exception {
        JsonNode project = json("{\"id\":\"P123\",\"slug\":\"create\"}");
        assertTrue(ChatController.selfConsistent(Kind.PROJECT, "create", project));
        assertTrue(ChatController.selfConsistent(Kind.PROJECT, "P123", project));
        assertFalse(ChatController.selfConsistent(Kind.PROJECT, "jei", project),
                "拿别的项目来顶替必须被挡住");
    }

    @Test
    @DisplayName("精确版本：返回对象自己的 id 必须等于请求的 version_id")
    void versionIdMustMatch() throws Exception {
        JsonNode version = json("{\"id\":\"v-abc\",\"game_versions\":[\"1.20.1\"]}");
        assertTrue(ChatController.selfConsistent(Kind.VERSION, "v-abc", version));
        assertFalse(ChatController.selfConsistent(Kind.VERSION, "v-other", version));
    }

    @Test
    @DisplayName("环境版本：mc 或 loader 对不上就直接拒收——这条直接决定模组进不进最终包")
    void envVersionMustDeclareTheRequestedEnvironment() throws Exception {
        String key = "create|1.20.1|[\"forge\"]";
        JsonNode forge120 = json("""
                {"id":"v1","game_versions":["1.20.1"],"loaders":["forge","neoforge"]}""");
        assertTrue(ChatController.selfConsistent(Kind.ENV_VERSION, key, forge120));

        JsonNode wrongMc = json("""
                {"id":"v2","game_versions":["1.21.1"],"loaders":["forge"]}""");
        assertFalse(ChatController.selfConsistent(Kind.ENV_VERSION, key, wrongMc));

        JsonNode wrongLoader = json("""
                {"id":"v3","game_versions":["1.20.1"],"loaders":["fabric"]}""");
        assertFalse(ChatController.selfConsistent(Kind.ENV_VERSION, key, wrongLoader));

        JsonNode noId = json("""
                {"game_versions":["1.20.1"],"loaders":["forge"]}""");
        assertFalse(ChatController.selfConsistent(Kind.ENV_VERSION, key, noId));

        assertFalse(ChatController.selfConsistent(Kind.ENV_VERSION, "格式不对的键", forge120));
    }

    @Test
    @DisplayName("搜索：必须带回 hits 数组")
    void searchNeedsHits() throws Exception {
        assertTrue(ChatController.selfConsistent(Kind.SEARCH, "w1", json("{\"hits\":[]}")));
        assertFalse(ChatController.selfConsistent(Kind.SEARCH, "w1", json("{\"total_hits\":0}")));
    }
}
