package yagen.waitmydawn.maa.check;

/**
 * 自愈引擎进度回调接口
 * 用于 SelfHealingEngine 向外部（如 SSE emitter）报告实时进度
 */
@FunctionalInterface
public interface HealingProgressCallback {

    /**
     * @param phase 阶段名: "resolving", "resolved", "downloading", "testing", "crash", "healing", "complete", "failed"
     * @param data  阶段附带数据, 各阶段含义不同:
     *   resolving: [coreCount]
     *   resolved:  [totalCount]
     *   downloading: [current, total]
     *   testing:   []
     *   crash:     [logSnippet]
     *   healing:   [cycle, action, target, reason]
     *   complete:  [finalModsList, skippedClientList, cycles]
     *   failed:    [finalModsList, cycles, message]
     */
    void onPhase(String phase, Object... data);
}
