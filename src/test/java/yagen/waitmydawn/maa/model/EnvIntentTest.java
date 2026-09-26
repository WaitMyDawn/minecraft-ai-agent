package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 用户"点名环境"的解析测试。
 *
 * <p>第一条用例就是真实事故的原话：用户说"Neoforge26.2"，模型却凭记忆判断 26.2 不存在并拒绝切换，
 * 整轮按 1.21.1 跑。修复后由 Java 把这句解析成 (26.2, neoforge)，并压过模型的回填。
 */
class EnvIntentTest {

    @Test
    @DisplayName("真实事故原话：\"60个模组的Neoforge26.2\" → (26.2, neoforge)")
    void parsesTheRealIncident() {
        EnvIntent intent = EnvIntent.parse("我想玩一个60个模组的Neoforge26.2的冒险整合包");
        assertEquals("26.2", intent.mcVersion());
        assertEquals("neoforge", intent.loader());
    }

    @Test
    @DisplayName("新式命名 xx.y.z：26.1.2 不能被截成 26.1")
    void doesNotTruncatePatchVersion() {
        assertEquals("26.1.2", EnvIntent.parse("NeoForge 26.1.2 的包").mcVersion());
        assertEquals("26.3", EnvIntent.parse("我要 26.3").mcVersion());
    }

    @Test
    @DisplayName("加载器别名：fabric / forge / neoforge（含无空格写法）")
    void recognizesLoaders() {
        assertEquals("fabric", EnvIntent.parse("换成 fabric 1.20.1 的包").loader());
        assertEquals("forge", EnvIntent.parse("forge 1.7.10 老包").loader());
        assertEquals("neoforge", EnvIntent.parse("NeoForge26.3").loader());
        assertNull(EnvIntent.parse("我要一个 1.21.1 的包").loader(), "没点名加载器时留 null（沿用当前）");
    }

    @Test
    @DisplayName("没点名环境就不要乱切")
    void returnsNullWhenNoEnvironment() {
        assertNull(EnvIntent.parse("我想要 60 个模组"));
        assertNull(EnvIntent.parse("neoforge 的包"), "只说加载器没给版本 → 不算切换");
    }

    @Test
    @DisplayName("已知误报：句子里的版本号会被识别成环境（靠调用方\"与当前环境相同则不切\"兜住）")
    void knownFalsePositive() {
        // 这是有意的取舍：识别是"形态匹配"，不判断语义。缓解手段在 ChatController：
        // ① 版本必须在已维护清单里；② 与当前环境相同就不算切换（所以"1.20.1 里那个模组坏了"在 1.20.1 包里无害）。
        EnvIntent intent = EnvIntent.parse("1.20.1 里有 3 个模组坏了");
        assertEquals("1.20.1", intent.mcVersion());
    }

    @Test
    @DisplayName("只给版本：加载器留 null")
    void versionOnly() {
        EnvIntent intent = EnvIntent.parse("来一套 1.21.1");
        assertEquals("1.21.1", intent.mcVersion());
        assertNull(intent.loader());
    }
}
