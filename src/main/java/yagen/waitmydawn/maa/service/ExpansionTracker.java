package yagen.waitmydawn.maa.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 依赖膨胀系数（EF = Expansion Factor）跟踪器。
 *
 * <p>要解决的问题：用户说的 target_count 是**最终总数**，但发给 Critic 的"还需要补多少"
 * 是**根模组数**。两者之间隔着依赖穿透——实测每组根模组平均还会带出 0.3~0.9 个必需前置，
 * 于是"要 100 个"最后变成 177 个（A01 实测）。
 *
 * <p>做法：按环境（loader|mc）记录最近观测到的 {@code 最终数 / 根模组数}，用指数滑动平均（EMA）
 * 滚动修正，再反过来把"最终目标"折算成"根模组预算"：
 * {@code rootBudget = needed / EF}。
 *
 * <p>为什么是启发式而不是精确求解：模组数量本来就不是硬约束（多一点不算坏事，
 * 用户不满意可以按建议删），而精确做法要在每次选完后重跑一遍依赖解析，代价明显更高。
 * 这里的近似值会随实际运行自我修正，并写进 {@code <trace>} 便于观察与调参。
 *
 * <p>线程安全：虚拟线程并发访问，用 ConcurrentHashMap。
 */
@Service
public class ExpansionTracker {

    /** 初始经验值：池子里"库/前置"占比通常不低，1.3 表示平均每个根模组带 0.3 个前置 */
    private static final double DEFAULT_FACTOR = 1.3;
    /** EF 合理区间：防止个别极端样本把系数带飞（例如整包都是库） */
    private static final double MIN_FACTOR = 1.0;
    private static final double MAX_FACTOR = 2.5;
    /** EMA 权重：新样本占 1/4，兼顾收敛速度与稳定性 */
    private static final double ALPHA = 0.25;

    private final Map<String, Double> factors = new ConcurrentHashMap<>();

    /** 取当前环境的膨胀系数（无观测时返回默认值） */
    public double factor(String loader, String mcVersion) {
        Double v = factors.get(key(loader, mcVersion));
        return v == null ? DEFAULT_FACTOR : v;
    }

    /**
     * 记录一次真实观测并更新系数。
     *
     * @param roots      送入依赖引擎的根模组数
     * @param finalCount 依赖穿透后的最终模组数
     */
    public void observe(String loader, String mcVersion, int roots, int finalCount) {
        if (roots <= 0 || finalCount <= 0) return;
        double sample = (double) finalCount / roots;
        if (sample < MIN_FACTOR || sample > MAX_FACTOR) return;
        factors.compute(key(loader, mcVersion), (k, old) -> {
            double base = old == null ? DEFAULT_FACTOR : old;
            return base * (1 - ALPHA) + sample * ALPHA;
        });
    }

    private static String key(String loader, String mcVersion) {
        return (loader == null ? "neoforge" : loader.toLowerCase()) + "|" + (mcVersion == null ? "" : mcVersion);
    }
}
