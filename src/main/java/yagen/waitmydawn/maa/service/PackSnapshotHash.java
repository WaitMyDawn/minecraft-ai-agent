package yagen.waitmydawn.maa.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * 打包输入状态的版本号。
 *
 * <p>一个整合包的"这一版"由四个输入决定：加载器、游戏版本、模组清单、用户显式删除的清单。
 * 把它们规范化后取 SHA-256 前 10 位十六进制，就得到 {@code snapshotId}：
 *
 * <ul>
 *   <li><b>同样的一版永远是同一个 id</b>：同一轮里消息可能被保存多次（重试、补写），
 *       内容哈希让"重复保存"不会伪装成"新版本"；</li>
 *   <li><b>与清单顺序无关</b>：前端拼接软顺序不该改变版本号，所以先排序去重再哈希；</li>
 *   <li><b>不掺时间戳</b>：它是内容标识，不是创建流水号 —— 用户界面靠它区分"哪一版"，
 *       也靠它判断一次改动是否真的改变了内容。</li>
 * </ul>
 *
 * <p>只做展示与比较，不承担安全职责；10 位十六进制（40 bit）在单会话的版本量级下
 * 碰撞概率可以忽略。真正用于导出的文件清单是另一套东西（{@code PackManifestService}
 * 的 manifestId，短生命周期、不持久化），两者不要混用。
 */
public final class PackSnapshotHash {

    /** 取 SHA-256 的前 5 字节 → 10 位十六进制 */
    private static final int HEX_CHARS = 10;

    private PackSnapshotHash() {
    }

    /**
     * @param loader        加载器（neoforge / forge / fabric），null 视为空
     * @param mcVersion     游戏版本，null 视为空
     * @param modSlugsCsv   模组清单（逗号分隔），顺序无关
     * @param excludedSlugsCsv 用户显式删除的模组（逗号分隔），顺序无关
     * @return 10 位十六进制的版本号
     */
    public static String id(String loader, String mcVersion, String modSlugsCsv, String excludedSlugsCsv) {
        return sha256Hex(canonical(loader, mcVersion, modSlugsCsv, excludedSlugsCsv));
    }

    /** 规范化后的待哈希串（包可见：测试直接断言"顺序不同、结果相同"） */
    static String canonical(String loader, String mcVersion, String modSlugsCsv, String excludedSlugsCsv) {
        return blankIfNull(loader) + "|" + blankIfNull(mcVersion) + "|"
                + sortedUnique(modSlugsCsv) + "|" + sortedUnique(excludedSlugsCsv);
    }

    private static String blankIfNull(String s) {
        return s == null ? "" : s.trim();
    }

    /** 逗号分隔清单 → 去空、去重、字典序，保证"同一批模组"只有一种写法 */
    private static String sortedUnique(String csv) {
        List<String> out = new ArrayList<>();
        if (csv != null) {
            for (String part : csv.split(",")) {
                String slug = part.trim();
                if (!slug.isEmpty() && !out.contains(slug)) out.add(slug);
            }
        }
        out.sort(String::compareTo);
        return String.join(",", out);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(HEX_CHARS);
            for (int i = 0; i < HEX_CHARS / 2; i++) {
                sb.append(Character.forDigit((digest[i] >> 4) & 0xF, 16));
                sb.append(Character.forDigit(digest[i] & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境已经被破坏
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
