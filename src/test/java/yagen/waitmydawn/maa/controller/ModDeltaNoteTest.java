package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.controller.ChatController.ModDelta;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "点名模组被平替/剔除/去重"的用户可见说明（诚实上报）。
 *
 * <p>这三件事都会改变用户的包，但以前只写日志——用户只看到"我要的东西没进来"。
 * 这里守两件事：① 有调整时必须说清楚（含来源标注）；② 不能把回复撑爆（截断 + 计数）。
 */
class ModDeltaNoteTest {

    private static ModDelta replaced(String from, String to, boolean fromPack) {
        return new ModDelta(from, "REPLACED", to, fromPack);
    }

    @Test
    @DisplayName("没有调整时不打扰用户")
    void emptyWhenNoDeltas() {
        assertEquals("", ChatController.buildModDeltaNote(null));
        assertEquals("", ChatController.buildModDeltaNote(List.of()));
        assertEquals("", ChatController.buildModDeltaNote(List.of(new ModDelta("x", "UNKNOWN", "d", false))));
    }

    @Test
    @DisplayName("平替要说清 from → to，并标注是用户包里的还是本轮新增的")
    void replacedIsExplainedWithOrigin() {
        String note = ChatController.buildModDeltaNote(List.of(
                replaced("thermal-expansion", "thermal-foundation", true),
                replaced("ftb-quests", "ftb-quests-optimizer", false)));

        assertTrue(note.contains("thermal-expansion → thermal-foundation"), note);
        assertTrue(note.contains("你包里原有的模组"), note);
        assertTrue(note.contains("本轮新增的模组"), note);
        assertTrue(note.contains("Modrinth"), "必须说明依据来自真实查询：" + note);
    }

    @Test
    @DisplayName("剔除与去重分别说明原因，并提示用户可以点名换替代")
    void droppedAndDedupedAreExplained() {
        String note = ChatController.buildModDeltaNote(List.of(
                new ModDelta("biomesoplenty", "DROPPED", "Modrinth 上没有这个模组，也没找到合理的平替", false),
                new ModDelta("iron-chests", "DUPLICATE", "与包内 iron-ender-chests 是同一个模组", true)));

        assertTrue(note.contains("· 剔除：biomesoplenty"), note);
        assertTrue(note.contains("· 去重：iron-chests"), note);
        assertTrue(note.contains("点名要我换一个可用的替代"), note);
    }

    @Test
    @DisplayName("调整很多时截断并列剩余条数，避免回复被撑爆")
    void longListIsTruncated() {
        List<ModDelta> many = new ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(replaced("mod-" + i, "mod-" + i + "-port", false));

        String note = ChatController.buildModDeltaNote(many);

        long bullets = note.lines().filter(l -> l.startsWith("· ")).count();
        assertEquals(9, bullets, "8 条明细 + 1 条“还有 N 项”");
        assertTrue(note.contains("还有 4 项"), note);
        assertFalse(note.contains("mod-9 →"), "第 9 条起应被截断：" + note);
    }
}
