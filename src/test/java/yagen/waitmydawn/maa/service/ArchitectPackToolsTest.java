package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 包名净化的离线测试。
 *
 * <p>背景：包名会被回填进 {@code <name>}（走 {@code Matcher.replaceAll}）并显示给用户，
 * {@code $} / {@code \} 在替换串里有特殊含义，含它们的包名会让整轮回复抛异常退化。
 * 所以净化放在 Tool 入口，而不是等到替换时补救。
 */
class ArchitectPackToolsTest {

    private static ArchitectPackTools tools() {
        // setPackName 不触碰缓存/API/词典，用空依赖即可离线断言 Tool 契约
        return new ArchitectPackTools(new PackSessionState(), null, null, null);
    }

    @Test
    @DisplayName("正则替换的危险字符（$ 与 \\）被清理")
    void stripsRegexHostileChars() {
        assertEquals("abc", ArchitectPackTools.sanitizePackName("a$b\\c"));
        assertEquals("已清理", ArchitectPackTools.sanitizePackName("已$清\\理"));
    }

    @Test
    @DisplayName("XML/引号/围栏字符被清理，中英文与常用分隔符保留")
    void stripsXmlAndQuoteChars() {
        String cleaned = ArchitectPackTools.sanitizePackName("<name>x</name> & \"q\" 'a' `t`");
        assertFalse(cleaned.matches(".*[\\\\$<>\"'`&].*"), "不该残留危险字符，实际=" + cleaned);
        assertEquals("Craft Build", ArchitectPackTools.sanitizePackName("  Craft   Build  "));
        assertEquals("科技/魔法-包 2.0", ArchitectPackTools.sanitizePackName("科技/魔法-包 2.0"));
    }

    @Test
    @DisplayName("控制字符与行分隔符按空白折叠，包名保持单行")
    void collapsesWhitespaceAndControlChars() {
        assertEquals("魔法 冒险", ArchitectPackTools.sanitizePackName("魔法\n\t冒险"));
        assertEquals("A B", ArchitectPackTools.sanitizePackName("A\u2028B"));
    }

    @Test
    @DisplayName("只剩特殊字符时返回空串，由 Tool 按名称非法拒绝（不静默存空包名）")
    void emptyResultMeansReject() {
        assertEquals("", ArchitectPackTools.sanitizePackName("$\\<>"));
        PackSessionState state = new PackSessionState();
        String reply = new ArchitectPackTools(state, null, null, null).setPackName("$\\");
        assertTrue(reply.contains("1~60"), "应返回名称非法的提示，实际=" + reply);
        assertTrue(state.getPackName() == null, "非法名称不得写入状态，否则 <name> 会被清空");
    }

    @Test
    @DisplayName("Tool 说真话：名字被清理过就明确告知，且状态里存的是清理后的值")
    void toolReportsSanitizedValue() {
        PackSessionState state = new PackSessionState();
        ArchitectPackTools tools = new ArchitectPackTools(state, null, null, null);

        String ok = tools.setPackName("我的$包");
        assertTrue(ok.contains("我的包"), "应回显清理后的名字，实际=" + ok);
        assertTrue(ok.contains("已清理"), "清理过必须告知模型，实际=" + ok);
        assertEquals("我的包", state.getPackName());

        String untouched = tools.setPackName("机械动力整合包");
        assertEquals("已将包名设为 机械动力整合包", untouched, "没被清理时不应多嘴");
    }

    @Test
    @DisplayName("净化后的包名可直接进 60 字上限判断：清理会缩短长度")
    void sanitizeHappensBeforeLengthCheck() {
        PackSessionState state = new PackSessionState();
        ArchitectPackTools tools = new ArchitectPackTools(state, null, null, null);
        String name = "$" + "包".repeat(59) + "\\";   // 61 字符，清理后 59 字符 → 合法

        String reply = tools.setPackName(name);
        assertEquals("包".repeat(59), state.getPackName(), "长度判断应基于清理后的名字");
        assertTrue(reply.contains("已清理"));
    }
}
