package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 版本号（打包输入状态的内容哈希）的稳定性契约。
 *
 * <p>它决定界面上的"这是哪一版"，所以三条必须成立：同一版永远同一个号；
 * 清单顺序不影响版本号（前端拼接顺序不稳定）；任何一个输入变了就是新版本。
 * 另外它是用户可见的短标识，格式固定为 10 位十六进制。
 */
class PackSnapshotHashTest {

    @Test
    @DisplayName("同一个状态 → 同一个版本号，且格式是 10 位十六进制")
    void sameStateGivesSameId() {
        String a = PackSnapshotHash.id("neoforge", "1.21.1", "sodium,iris", "xyz");
        String b = PackSnapshotHash.id("neoforge", "1.21.1", "sodium,iris", "xyz");

        assertEquals(a, b);
        assertEquals(10, a.length());
        assertTrue(a.matches("[0-9a-f]{10}"), "版本号必须是十六进制小写：" + a);
    }

    @Test
    @DisplayName("清单顺序 / 重复项 / 空格不影响版本号（前端拼接顺序不稳定）")
    void listOrderDoesNotMatter() {
        String a = PackSnapshotHash.id("neoforge", "1.21.1", "sodium,iris,sodium", " b , a ");
        String b = PackSnapshotHash.id("neoforge", "1.21.1", " iris , sodium ", "b,a");

        assertEquals(a, b);
    }

    @Test
    @DisplayName("加载器 / 版本 / 清单 / 排除项 任一变化都要换版本号")
    void everyFieldAffectsTheId() {
        String base = PackSnapshotHash.id("neoforge", "1.21.1", "sodium", "");

        assertNotEquals(base, PackSnapshotHash.id("forge", "1.21.1", "sodium", ""));
        assertNotEquals(base, PackSnapshotHash.id("neoforge", "1.20.1", "sodium", ""));
        assertNotEquals(base, PackSnapshotHash.id("neoforge", "1.21.1", "sodium,iris", ""));
        assertNotEquals(base, PackSnapshotHash.id("neoforge", "1.21.1", "sodium", "iris"));
    }

    @Test
    @DisplayName("空状态也要有稳定的版本号（空图谱就是这一版）")
    void emptyStateIsStable() {
        String empty = PackSnapshotHash.id("neoforge", "1.21.1", "", "");
        assertEquals(empty, PackSnapshotHash.id("neoforge", "1.21.1", null, null));
        assertNotEquals(empty, PackSnapshotHash.id("fabric", "1.21.1", "", ""));
    }

    @Test
    @DisplayName("字段之间不能靠分隔符粘在一起（'a|b' 与 'a','b' 不能撞号）")
    void fieldsAreProperlyDelimited() {
        assertNotEquals(
                PackSnapshotHash.id("neoforge", "1.21.1", "a", "b"),
                PackSnapshotHash.id("neoforge", "1.21.1", "a", "")); // 排除项不同 → 不同版本
        assertEquals("neoforge|1.21.1|a|b", PackSnapshotHash.canonical("neoforge", "1.21.1", "a", "b"));
    }
}
