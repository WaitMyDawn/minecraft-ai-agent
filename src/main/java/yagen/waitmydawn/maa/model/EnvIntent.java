package yagen.waitmydawn.maa.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户在自然语言里**点名**的整合包环境（MC 版本 + 可选加载器）。
 *
 * <p>为什么需要它：环境原本由模型输出的 {@code <mc>/<loader>} 决定，而提示词里只给了"当前环境"，
 * 没给"支持哪些环境"。于是用户说"Neoforge26.2"时，模型凭记忆判断"26.2 对应 MC 1.21.9+"并拒绝切换，
 * 整轮都按旧环境 1.21.1 跑（真实事故，见 logs/2026-09-26/user-1.log:2269-2294）。
 * 现在：**用户明确点名 > 模型回填 > 前端当前环境**，用户说了就切，本轮生效。
 *
 * <p>版本号形态（用户 2026-09 确认的官方命名规则）：
 * <ul>
 *   <li>老式：{@code 1.20.1} / {@code 1.21}（{@code 1.x[.y]}）</li>
 *   <li>新式：{@code xx.y.z}，xx = 年份后缀（2026 → 26）、y = 季度、z = 补丁；例如
 *       {@code 26.2} = 2026 年第 2 季度，{@code 26.1.2} = 该季度第 2 个补丁</li>
 * </ul>
 *
 * <p>这里只做**形态识别**；"这个环境我们是否维护"由调用方拿 {@code LoaderVersionService} 校验
 * （见 ChatController），避免识别出一个其实不存在的版本就乱切。
 */
public record EnvIntent(String mcVersion, String loader) {

    /** 1.x[.y] 或 2x.y[.z]；结尾用 (?!\d) 挡住 "1.211" 这种被截断的匹配。 */
    private static final Pattern MC_VERSION = Pattern.compile(
            "((?:1\\.\\d{1,2}(?:\\.\\d{1,2})?)|(?:2\\d\\.\\d{1,2}(?:\\.\\d{1,2})?))(?!\\d)");

    /**
     * 从用户话里解析环境意图。
     *
     * @return 解析到 MC 版本时返回（加载器可能为 null = 用户没点名加载器）；否则 null
     */
    public static EnvIntent parse(String prompt) {
        if (prompt == null || prompt.isBlank()) return null;
        Matcher m = MC_VERSION.matcher(prompt);
        if (!m.find()) return null;
        return new EnvIntent(m.group(1), loaderOf(prompt));
    }

    /** 加载器别名 → 规范名；"neoforge" 必须先于 "forge" 判断，否则会被吞掉。 */
    private static String loaderOf(String prompt) {
        String p = prompt.toLowerCase();
        if (p.contains("neoforge") || p.contains("neo-forge") || p.contains("neo forge")) return "neoforge";
        if (p.contains("fabric")) return "fabric";
        if (p.contains("forge")) return "forge";
        return null;
    }
}
