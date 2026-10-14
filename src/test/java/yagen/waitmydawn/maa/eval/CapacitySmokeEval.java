package yagen.waitmydawn.maa.eval;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.service.*;
import yagen.waitmydawn.maa.model.agent.*;
import java.nio.file.*;
import java.lang.management.ManagementFactory;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 合成排队冒烟；不调用模型或远端 API，不作为百人真实构建吞吐量证明。 */
@EnabledIfSystemProperty(named = "eval.capacity", matches = "true")
class CapacitySmokeEval {
    @Test void hundredOwnersQueueAndWaitingSelectionsReleaseWorkers() throws Exception {
        var registry = new DelegationTasks(true, 120_000, 8, 128);
        var selections = new ReplacementSelections();
        var intent = new BuildIntent(1, BuildIntent.Mode.NEW, "", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of("jade"), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2100000000L);
        var gate = new CountDownLatch(1); var started = new CountDownLatch(8);
        var active = new AtomicInteger(); var peakActive = new AtomicInteger();
        var pending = new AtomicInteger(); var completed = new AtomicInteger();
        var tasks = new ArrayList<DelegationTasks.Task>(); var elapsed = new ConcurrentLinkedQueue<Long>();
        long start = System.nanoTime();
        try {
            for (int i = 0; i < 100; i++) {
                int index = i; long submitted = System.nanoTime();
                tasks.add(registry.submit("capacity-" + i, (long) i + 1, "synthetic-" + i, null, () -> {
                    int now = active.incrementAndGet(); peakActive.accumulateAndGet(now, Math::max); started.countDown();
                    try {
                        assertTrue(gate.await(5, TimeUnit.SECONDS));
                        Thread.sleep(30 + index % 7 * 5L);
                        if (index % 4 == 0) {
                            selections.put(new ReplacementSelections.Pending((long) index + 1, "fixture", intent,
                                    BuildConstraints.empty(), null, List.of(), List.of()));
                            pending.incrementAndGet(); return "PENDING_SELECTION";
                        }
                        completed.incrementAndGet(); return "COMPLETE_SYNTHETIC_WORK";
                    } finally {
                        active.decrementAndGet(); elapsed.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submitted));
                    }
                }));
            }
            assertTrue(started.await(5, TimeUnit.SECONDS));
            int queued = (int) tasks.stream().filter(t -> !t.hasStarted()).count();
            assertEquals(92, queued); gate.countDown();
            CompletableFuture.allOf(tasks.stream().map(DelegationTasks.Task::result).toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
            assertEquals(25, pending.get()); assertEquals(75, completed.get()); assertEquals(8, peakActive.get());
            var next = registry.submit("after-pending", 1L, "after-pending", null, () -> "READY");
            assertEquals("READY", next.result().get(5, TimeUnit.SECONDS));

            var cancelGate = new CountDownLatch(1); var cancelStarted = new CountDownLatch(8);
            var blocking = new ArrayList<DelegationTasks.Task>();
            for (int i = 0; i < 8; i++) blocking.add(registry.submit("block-" + i, 1000L + i, "block-" + i, null, () -> {
                cancelStarted.countDown(); cancelGate.await(); return "READY";
            }));
            assertTrue(cancelStarted.await(5, TimeUnit.SECONDS));
            var canceled = new ArrayList<DelegationTasks.Task>();
            for (int i = 0; i < 20; i++) canceled.add(registry.submit("cancel-" + i, 2000L + i, "cancel-" + i, null, () -> "SHOULD_NOT_RUN"));
            canceled.forEach(registry::cancel);
            assertTrue(canceled.stream().allMatch(t -> t.result().isCancelled() && !t.hasStarted()));
            cancelGate.countDown();
            CompletableFuture.allOf(blocking.stream().map(DelegationTasks.Task::result).toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            var afterCancel = registry.submit("after-cancel", 2000L, "after-cancel", null, () -> "READY");
            assertEquals("READY", afterCancel.result().get(5, TimeUnit.SECONDS));

            var times = elapsed.stream().sorted().toList();
            var report = new LinkedHashMap<String, Object>();
            report.put("scope", "synthetic queue and cancellation; no HTTP/LLM/Modrinth; not production capacity acceptance");
            report.put("availableProcessors", Runtime.getRuntime().availableProcessors());
            report.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
            report.put("heapUsedBytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
            report.put("owners", 100); report.put("initialQueued", queued); report.put("peakRunning", peakActive.get());
            report.put("rejections", 0); report.put("waitingSelectionTickets", pending.get()); report.put("canceledQueued", 20);
            report.put("p50Ms", times.get(49)); report.put("p95Ms", times.get(94));
            report.put("elapsedMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            Files.createDirectories(Path.of("target/eval"));
            Files.writeString(Path.of("target/eval/capacity-smoke.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report));
        } finally { gate.countDown(); registry.close(); }
    }
}
