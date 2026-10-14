package yagen.waitmydawn.maa.runtime;

import java.util.ArrayList;
import java.util.List;

/** 业务阶段日志独立于取数轮次；快照只能追加，轮询不能把阶段回退。 */
public final class BuildProgress {
    public record Entry(int sequence, String title, long startedAt, Long endedAt) {}
    private final List<Entry> entries = new ArrayList<>();
    private boolean finished;

    public synchronized void enter(String title) {
        if (finished || entries.size() >= 64 || title == null || title.isBlank()) return;
        if (!entries.isEmpty() && entries.getLast().title().equals(title)) return;
        long now = System.currentTimeMillis();
        closeLast(now);
        entries.add(new Entry(entries.size() + 1, title, now, null));
    }

    private void closeLast(long now) {
        if (entries.isEmpty()) return;
        var last = entries.getLast();
        if (last.endedAt() == null) entries.set(entries.size() - 1,
                new Entry(last.sequence(), last.title(), last.startedAt(), Math.max(last.startedAt(), now)));
    }

    public synchronized List<Entry> snapshot() { return List.copyOf(entries); }
    public synchronized void finish() { closeLast(System.currentTimeMillis()); finished = true; }

    public static void stage(String title) {
        var context = DelegationContext.current();
        if (context != null) context.progress().enter(title);
    }
}
