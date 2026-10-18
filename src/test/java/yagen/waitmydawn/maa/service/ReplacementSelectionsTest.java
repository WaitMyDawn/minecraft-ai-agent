package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.model.RetrievalBudget;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReplacementSelectionsTest {
    @Test @org.junit.jupiter.api.Timeout(5)
    void pendingGenerationTicketsDoNotHoldTheOnlyBuildWorker() throws Exception {
        var tasks = new DelegationTasks(true, 5000, 1, 8);
        try {
            for (long owner = 1; owner <= 12; owner++) {
                long account = owner;
                var template = generationPending(new RetrievalBudget());
                var task = tasks.submit(null, account, "fixture", null, () -> selections.put(new ReplacementSelections.Pending(account,
                        template.snapshot(), template.intent(), template.constraints(), null, List.of(), template.groups(), template.generation())));
                assertNotNull(task.result().get(1, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertEquals("other account finished", tasks.submit(null, 99L, "other", null, () -> "other account finished")
                    .result().get(1, java.util.concurrent.TimeUnit.SECONDS));
        } finally { tasks.close(); }
    }
    @Test void expiredGenerationTicketCannotApplyTheDefaultSelection() {
        var elapsed = new java.util.concurrent.atomic.AtomicLong();
        var pending = new ReplacementSelections(elapsed::get);
        String ticket = pending.put(generationPending(new RetrievalBudget()));
        elapsed.set(java.util.concurrent.TimeUnit.MINUTES.toNanos(16));
        assertThrows(IllegalArgumentException.class, () -> pending.resume(ticket, 1L, generationPayload("nether-a")));
    }
    private ReplacementSelections.Pending generationPending(RetrievalBudget budget) {
        var cards = List.of(new yagen.waitmydawn.maa.model.ContentOverlap.Candidate("nether-a", "A", "", "", 100, "核心生成", "Nether custom terrain", "下界", "", false, true),
                new yagen.waitmydawn.maa.model.ContentOverlap.Candidate("nether-b", "B", "", "", 50, "核心生成", "Nether terrain overhaul", "下界", "", false, false));
        var rich = new yagen.waitmydawn.maa.model.ContentOverlap.Group("generation:nether", "下界核心世界生成", "组合风险", cards, true);
        var basic = new RequiredModAlternatives.Group(rich.original(), cards.stream().map(c -> new RequiredModAlternatives.Candidate(c.slug(), c.title(), c.description(), "", c.downloads(), 100, c.relation())).toList(), true);
        return new ReplacementSelections.Pending(1L, ReplacementSelections.snapshot(base), intent, BuildConstraints.empty().apply(intent, Set.of()).constraints(),
                null, List.of(), List.of(basic), new ReplacementSelections.GenerationContext(List.of(rich), budget, Map.of("old", List.of("port")), Map.of()));
    }
    private Map<String, String> generationPayload(String slug) {
        var result = new HashMap<>(base);
        result.put("replacementChoices", "{\"generation:nether\":{\"slugs\":[\"" + slug + "\"],\"drop\":false,\"manualSlug\":\"\"}}");
        return result;
    }
    @Test void generationConfirmationPreservesPlanBudgetAndPriorPortChoices() {
        var budget = new yagen.waitmydawn.maa.model.RetrievalBudget(); budget.search("already-used");
        String ticket = selections.put(generationPending(budget));
        var resumed = selections.resume(ticket, 1L, generationPayload("nether-b"));
        assertTrue(resumed.intent().mustHave().containsAll(List.of("alexs-mobs", "nether-b")));
        assertTrue(resumed.intent().exclude().contains("nether-a"));
        assertEquals(intent.requirements(), resumed.intent().requirements());
        assertEquals(Map.of("old", List.of("port")), resumed.replacements());
        assertSame(budget, resumed.pending().generation().budget());
        assertEquals(1, budget.snapshot().get("searchQueries"));
        assertThrows(IllegalArgumentException.class, () -> selections.resume(ticket, 1L, generationPayload("nether-b")));
    }
    @Test void rejectedAdmissionRestoresOriginalGenerationOptionsSoTheChoiceCanChange() {
        String ticket = selections.put(generationPending(new yagen.waitmydawn.maa.model.RetrievalBudget()));
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> selections.admit(ticket, 1L, generationPayload("nether-a"), ignored -> { throw new java.util.concurrent.RejectedExecutionException(); }));
        assertTrue(selections.resume(ticket, 1L, generationPayload("nether-b")).intent().mustHave().contains("nether-b"));
        String staleTicket = selections.put(generationPending(new yagen.waitmydawn.maa.model.RetrievalBudget()));
        var changed = new HashMap<>(generationPayload("nether-a")); changed.put("assembly", "changed-snapshot");
        assertThrows(IllegalArgumentException.class, () -> selections.resume(staleTicket, 1L, changed));
    }
    private final ReplacementSelections selections = new ReplacementSelections();
    private final Map<String, String> base = Map.of("prompt", "fixture", "currentMods", "", "loader", "neoforge", "mcVersion", "1.21.1");
    private final BuildIntent intent = new BuildIntent(1, BuildIntent.Mode.NEW, "", "",
            new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
            new BuildIntent.CountTarget(60, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
            List.of("alexs-mobs"), List.of("alexs-mobs"), List.of(), List.of("blocked"), List.of("alexs-mobs"),
            BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2_100_000_000L, ConstraintChanges.KEEP,
            List.of(new BuildRequirement("r-0000000000000001", "包含Alex生物", BuildRequirement.Kind.PROJECTS,
                            List.of("alexs-mobs"), List.of()),
                    new BuildRequirement("r-0000000000000002", "扩展Alex生物附属", BuildRequirement.Kind.ADDONS,
                            List.of("alexs-mobs"), List.of())));
    private String ticket() {
        return selections.put(pendingFor(1L));
    }
    private ReplacementSelections.Pending pendingFor(long owner) {
        var candidates = List.of(new RequiredModAlternatives.Candidate("alexs-mobs-1.21.1", "Port", "", "", 10, 100, "移植版"),
                new RequiredModAlternatives.Candidate("alexs-mobs-continued", "Continued", "", "", 5, 100, "延续版"));
        var constraints = BuildConstraints.empty().apply(intent, Set.of()).constraints();
        return new ReplacementSelections.Pending(owner, ReplacementSelections.snapshot(base), intent, constraints,
                null, List.of(), List.of(new RequiredModAlternatives.Group("alexs-mobs", candidates, true)));
    }
    private Map<String, String> payload(String choice) {
        var payload = new HashMap<>(base);
        payload.put("replacementChoices", "{\"alexs-mobs\":" + choice + "}");
        return payload;
    }
    @Test void multiSelectionUpdatesAllProjectReferencesAndKeepsOtherConstraints() {
        String ticket = ticket();
        var payload = payload("{\"slugs\":[\"alexs-mobs-1.21.1\",\"alexs-mobs-continued\"],\"drop\":false,\"manualSlug\":\"\"}");
        var result = selections.resume(ticket, 1L, payload);
        assertEquals(List.of("alexs-mobs-1.21.1", "alexs-mobs-continued"), result.intent().mustHave());
        assertEquals(result.intent().mustHave(), result.intent().add());
        assertEquals(result.intent().mustHave(), result.intent().expandAddons());
        assertEquals(intent.count(), result.intent().count());
        assertEquals(intent.exclude(), result.constraints().exclude());
        assertEquals(result.intent().mustHave(), result.intent().requirements().getFirst().projects());
        assertEquals(result.intent().mustHave(), result.intent().requirements().get(1).addonCores());
        assertEquals(intent.requirements().getFirst().source(), result.constraints().requirements().getFirst().source());
        assertThrows(IllegalArgumentException.class, () -> selections.resume(ticket, 1L, payload));
    }
    @Test void anotherAccountOrChangedWorkspaceCannotConsumeTicket() {
        String ticket = ticket();
        var payload = payload("{\"slugs\":[\"alexs-mobs-continued\"],\"drop\":false,\"manualSlug\":\"\"}");
        assertThrows(IllegalArgumentException.class, () -> selections.resume(ticket, 2L, payload));
        var changed = new HashMap<>(payload); changed.put("currentMods", "jei");
        assertThrows(IllegalArgumentException.class, () -> selections.resume(ticket, 1L, changed));
        assertDoesNotThrow(() -> selections.resume(ticket, 1L, payload));
    }
    @Test void unknownCandidateEmptySelectionAndConflictingActionsAreRejected() {
        String ticket = ticket();
        for (String choice : List.of("{\"slugs\":[\"alexs-mobs-extra-music\"],\"drop\":false,\"manualSlug\":\"\"}",
                "{\"slugs\":[],\"drop\":false,\"manualSlug\":\"\"}",
                "{\"slugs\":[\"alexs-mobs-continued\"],\"drop\":true,\"manualSlug\":\"\"}",
                "{\"slugs\":[],\"drop\":false,\"manualSlug\":\"blocked\"}"))
            assertThrows(IllegalArgumentException.class, () -> selections.resume(ticket, 1L, payload(choice)));
    }
    @Test void explicitDropRemovesRequirementAndExplicitSlugIsPreservedForPreflight() {
        var dropped = selections.resume(ticket(), 1L, payload("{\"slugs\":[],\"drop\":true,\"manualSlug\":\"\"}"));
        assertTrue(dropped.intent().mustHave().isEmpty()); assertTrue(dropped.intent().add().isEmpty());
        assertTrue(dropped.intent().requirements().isEmpty());
        assertTrue(dropped.constraints().requirements().isEmpty());
        var manual = selections.resume(ticket(), 1L, payload("{\"slugs\":[],\"drop\":false,\"manualSlug\":\"alexs-mobs-continued\"}"));
        assertEquals(List.of("alexs-mobs-continued"), manual.intent().mustHave());
    }
    private Map<String, String> confirmed() {
        return payload("{\"slugs\":[\"alexs-mobs-continued\"],\"drop\":false,\"manualSlug\":\"\"}");
    }
    @Test void newerWaitingPlanReplacesOnlySameAccountAndCancelChecksOwnership() {
        String old = ticket();
        String other = selections.put(pendingFor(2L));
        String current = ticket();
        assertThrows(IllegalArgumentException.class, () -> selections.resume(old, 1L, confirmed()));
        assertFalse(selections.cancel(current, 2L));
        assertTrue(selections.cancel(current, 1L));
        assertThrows(IllegalArgumentException.class, () -> selections.resume(current, 1L, confirmed()));
        assertDoesNotThrow(() -> selections.resume(other, 2L, confirmed()));
    }
    @Test void admissionCapturesPlanBeforeWaitingTicketExpires() throws Exception {
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var store = new ReplacementSelections(nanos::get);
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var tasks = new DelegationTasks(false, 1000, 1, 2);
        try {
            tasks.submit(null, 99L, "busy", null, () -> { started.countDown(); release.await(); return "done"; });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            String ticket = store.put(pendingFor(1L));
            var queued = store.admit(ticket, 1L, confirmed(), resumed -> tasks.submit(null, 1L, "resume", null,
                    () -> String.join(",", resumed.intent().mustHave())));
            assertEquals(1, tasks.queuePosition(queued));
            nanos.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(16));
            assertThrows(IllegalArgumentException.class, () -> store.resume(ticket, 1L, confirmed()));
            release.countDown();
            assertEquals("alexs-mobs-continued", queued.result().get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { release.countDown(); tasks.close(); }
    }
    @Test void fullExecutionQueueRollsBackTicketForExplicitRetry() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        var started = new java.util.concurrent.CountDownLatch(1);
        var tasks = new DelegationTasks(false, 1000, 1, 1);
        try {
            tasks.submit(null, 99L, "busy", null, () -> { started.countDown(); release.await(); return "done"; });
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            tasks.submit(null, 98L, "queued", null, () -> "done");
            String ticket = ticket();
            var error = assertThrows(org.springframework.web.server.ResponseStatusException.class,
                    () -> selections.admit(ticket, 1L, confirmed(), resumed -> tasks.submit(null, 1L, "retry", null, () -> "done")));
            assertEquals(429, error.getStatusCode().value());
            assertDoesNotThrow(() -> selections.resume(ticket, 1L, confirmed()));
        } finally { release.countDown(); tasks.close(); }
    }
    @Test void hundredUnconfirmedPlansLeaveExecutionWorkerAvailable() throws Exception {
        var tasks = new DelegationTasks(false, 1000, 1, 1);
        try {
            for (long owner = 1; owner <= 100; owner++) {
                long account = owner;
                tasks.submit(null, account, "pause", null, () -> selections.put(pendingFor(account)))
                        .result().get(2, java.util.concurrent.TimeUnit.SECONDS);
            }
            var unrelated = tasks.submit(null, 999L, "other", null, () -> "available");
            assertEquals("available", unrelated.result().get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally { tasks.close(); }
    }
    @Test void globalWaitingCapacityDoesNotEvictOtherAccounts() {
        var tickets = new ArrayList<String>();
        for (long owner = 1; owner <= 256; owner++) tickets.add(selections.put(pendingFor(owner)));
        assertThrows(IllegalArgumentException.class, () -> selections.put(pendingFor(257L)));
        assertDoesNotThrow(() -> selections.resume(tickets.getFirst(), 1L, confirmed()));
    }
    @Test void expiredWaitingPlanRequiresNewConfirmationAndNeverRunsDefaults() {
        var nanos = new java.util.concurrent.atomic.AtomicLong();
        var store = new ReplacementSelections(nanos::get);
        String ticket = store.put(pendingFor(1L));
        nanos.addAndGet(java.util.concurrent.TimeUnit.MINUTES.toNanos(16));
        var dispatched = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> store.admit(ticket, 1L, confirmed(), resumed -> dispatched.incrementAndGet()));
        assertEquals(0, dispatched.get());
    }
}
