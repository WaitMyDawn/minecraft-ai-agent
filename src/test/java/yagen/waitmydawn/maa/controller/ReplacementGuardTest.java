package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.controller.ChatController.ReplacementCandidate;
import yagen.waitmydawn.maa.controller.ChatController.RescueResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平替守卫的离线测试（坏例 B15）。
 *
 * <p>覆盖两个实测缺陷：① 排序把"词缀命中"放在"强词命中"之前，导致只命中通用词
 * {@code expansion} 的无关模组被选成平替；② "已在包内 → 判重复"排在质量闸门之前且在整桶上扫描，
 * 导致任意请求都可能被误判成"包内那个最热门的模组"的重复。
 */
class ReplacementGuardTest {

    private static ReplacementCandidate cand(String slug, long downloads, Set<String> hitTokens, boolean affixHit) {
        return new ReplacementCandidate(slug, slug, downloads, "", hitTokens, affixHit);
    }

    private static RescueResult pick(String original, List<ReplacementCandidate> candidates,
                                     Set<String> existingNorms, Map<String, Integer> debug) {
        return ChatController.pickReplacement(original, candidates, existingNorms, debug);
    }

    @Test
    @DisplayName("回归 B15①：只命中通用词 expansion 的无关模组不得当选（词缀不再压过强词）")
    void genericOnlyCandidateIsNotPicked() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                cand("doggy-talents-nexts-community-skin-expansion", 5_000_000L, Set.of("expansion"), true),
                cand("thermal-expansion-continuation", 20_000L, Set.of("thermal", "expansion"), true));

        RescueResult r = pick("thermal-expansion", candidates, Set.of(), debug);

        assertEquals("thermal-expansion-continuation", r.replacement,
                "强词命中多的候选必须排在前面，词缀只能做次级优先");
    }

    @Test
    @DisplayName("回归 B15①：池子里只有通用词命中的候选时，宁可放弃也不乱替")
    void noDistinctiveHitMeansGiveUpInsteadOfGuessing() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                cand("doggy-talents-nexts-community-skin-expansion", 5_000_000L, Set.of("expansion"), true));

        RescueResult r = pick("thermal-expansion", candidates, Set.of(), debug);

        assertNull(r.replacement, "thermal 没命中就不该拿别人顶替");
        assertFalse(r.duplicate);
        assertEquals(0, debug.get("强词可选项"), "预筛后强词可选项应为 0");
    }

    @Test
    @DisplayName("回归 B15③：只命中过度通用的品牌词（ender / travelers）不得当选")
    void overcommonBrandWordIsNotEnoughEvidence() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        assertEquals(null, pick("ender-io",
                List.of(cand("l_enders-cataclysm", 9_000_000L, Set.of("ender"), true)), Set.of(), debug).replacement,
                "ender 太通用，不能凭它把 Ender IO 换成灾变模组");
        assertNull(pick("travelers-backpack",
                List.of(cand("travelers-titles", 3_000_000L, Set.of("travelers"), true)), Set.of(), debug).replacement,
                "travelers 太通用，背包不能被换成称号模组");
    }

    @Test
    @DisplayName("回归 B15③ 不能误伤真平替：thermal-expansion → thermal-foundation 仍要成立")
    void correctReplacementSurvivesTheOvercommonRule() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        RescueResult r = pick("thermal-expansion",
                List.of(cand("thermal-foundation", 4_000_000L, Set.of("thermal"), false)), Set.of(), debug);

        assertEquals("thermal-foundation", r.replacement, "thermal 是有辨识度的品牌词，这条正解必须保留");
    }

    @Test
    @DisplayName("回归 B15②：无强词命中的候选不得触发\"已包内→重复\"误判")
    void weakCandidateCannotTriggerFalseDuplicate() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                cand("cloth-config", 50_000_000L, Set.of(), false),
                cand("doggy-talents-nexts-community-skin-expansion", 5_000_000L, Set.of("expansion"), true));
        Set<String> pack = Set.of("clothconfig", "doggytalentsnextscommunityskinexpansion");

        RescueResult r = pick("ferritecore", candidates, pack, debug);

        assertFalse(r.duplicate, "ferritecore 不该被当成 cloth-config 的重复");
        assertNull(r.replacement);
    }

    @Test
    @DisplayName("重复判定没有被修坏：真身的社区版已在包内时仍判重复（mobs → alexs-mobs-continued）")
    void realDuplicateIsStillDetected() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                cand("alexs-mobs-continued", 2_000_000L, Set.of("mobs"), true));

        RescueResult r = pick("mobs", candidates, Set.of("alexsmobscontinued"), debug);

        assertTrue(r.duplicate);
        assertEquals("alexs-mobs-continued", r.duplicateWith);
    }

    @Test
    @DisplayName("同强词命中时词缀优先，其次下载量")
    void affixBreaksTieAmongEquallyStrongCandidates() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                cand("big-port-mod", 900_000L, Set.of("thermal"), false),
                cand("tiny-community-port", 5_000L, Set.of("thermal"), true));

        assertEquals("tiny-community-port", pick("thermal-core", candidates, Set.of(), debug).replacement);
    }

    @Test
    @DisplayName("质量闸门仍在：低下载量跳过、非食物需求不选食物类候选")
    void qualityGatesStillApply() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> lowDownloads =
                List.of(cand("thermal-tiny", 500L, Set.of("thermal"), false));
        assertNull(pick("thermal-core", lowDownloads, Set.of(), debug).replacement);
        assertEquals(1, debug.get("低下载跳过"));

        Map<String, Integer> debug2 = new LinkedHashMap<>();
        List<ReplacementCandidate> foodOnly = List.of(
                new ReplacementCandidate("thermal-delight", "Thermal Delight", 900_000L, "food", Set.of("thermal"), false));
        assertNull(pick("thermal-core", foodOnly, Set.of(), debug2).replacement, "非食物需求不该串味到食物模组");
        assertEquals(1, debug2.get("食物类跳过"));
    }

    @Test
    @DisplayName("食物需求本身可以选食物候选（防串味不能误伤正主）")
    void foodishRequestMayPickFoodCandidate() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                new ReplacementCandidate("farmers-delight-port", "Farmer's Delight Port", 900_000L, "food",
                        Set.of("farmers", "delight"), false));

        assertEquals("farmers-delight-port", pick("farmers-delight", candidates, Set.of(), debug).replacement);
    }

    @Test
    @DisplayName("版本号粘连仍可命中（mobs1211 → alexs-mobs-continued），且去掉数字后仍有辨识度")
    void versionGluedNameStillMatches() {
        ReplacementCandidate c = ChatController.scoreCandidate("mobs1211", Set.of(), true,
                "alexs-mobs-continued", "Alex's Mobs Continued", 2_000_000L, "");

        assertEquals(1, c.strongHits(), "mobs1211 去尾数字后应命中 mobs");
        assertTrue(c.hitTokens().contains("mobs1211"));
        Map<String, Integer> debug = new LinkedHashMap<>();
        assertEquals("alexs-mobs-continued", pick("mobs1211", List.of(c), Set.of(), debug).replacement);
    }

    @Test
    @DisplayName("跳过自身：候选与原名规范化相同不作为平替")
    void skipsSelf() {
        Map<String, Integer> debug = new LinkedHashMap<>();
        List<ReplacementCandidate> candidates = List.of(
                cand("thermal-core", 9_000_000L, Set.of("thermal"), false));

        assertNull(pick("thermal-core", candidates, Set.of(), debug).replacement);
    }

    @Test
    @DisplayName("强词集合不含停用词与词缀：原名里的 api/lib/core 不参与匹配")
    void stopwordsAreNotStrongTokens() {
        // 原名 create-api-lib 里只有 create 会参与匹配 → 命中 create 的候选得 1 分
        assertEquals(Set.of("create"), ChatController.scoreCandidate("create-api-lib", Set.of(), false,
                "create-tools", "Create Tools", 999_999L, "").hitTokens());

        // 反过来：候选名里只有 api/lib、没有 create → 命中数 0（不会因为"api"相同就被当成同款模组）
        assertEquals(0, ChatController.scoreCandidate("create-api-lib", Set.of(), false,
                "api-lib-thing", "API Lib Thing", 999_999L, "").strongHits());
    }
}
