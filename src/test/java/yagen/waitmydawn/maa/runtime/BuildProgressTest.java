package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildProgressTest {
    @Test void pollingCannotCreateDuplicatesOrReopenFinishedStages() {
        var progress = new BuildProgress();
        progress.enter("规划"); progress.enter("规划");
        var earlier = progress.snapshot();
        progress.enter("取数"); progress.enter("审核");
        assertEquals(1, earlier.size());
        assertNull(earlier.getFirst().endedAt());
        assertEquals(3, progress.snapshot().size());
        assertNotNull(progress.snapshot().getFirst().endedAt());
        progress.finish(); progress.enter("迟到阶段");
        assertEquals(3, progress.snapshot().size());
        assertNotNull(progress.snapshot().getLast().endedAt());
    }

    @Test void businessMarkersAreIsolatedBetweenConcurrentTaskContexts() {
        var first = new DelegationContext(1000); var second = new DelegationContext(1000);
        DelegationContext.runWith(first, () -> BuildProgress.stage("规划"));
        DelegationContext.runWith(second, () -> BuildProgress.stage("依赖"));
        assertEquals("规划", first.progress().snapshot().getFirst().title());
        assertEquals("依赖", second.progress().snapshot().getFirst().title());
        BuildProgress.stage("没有上下文");
        assertEquals(1, first.progress().snapshot().size());
    }
}
