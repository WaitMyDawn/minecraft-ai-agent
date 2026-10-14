package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OverlapSelectionsTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModrinthApiClient api = mock(ModrinthApiClient.class);
    private final Map<String, String> payload = Map.of("uuid", "session-one", "prompt", "生态包", "currentMods", "", "assembly", "");
    private AssemblySnapshot assembly(boolean core) {
        return new AssemblySnapshot("neoforge", "1.21.1", List.of("animals-a", "animals-b", "other"),
                Map.of("animals-a", "V1", "animals-b", "V2", "library", "V3", "other", "V4"),
                Map.of("animals-a", Set.of(core ? "CORE" : "CONTENT"), "animals-b", Set.of("CONTENT"), "library", Set.of("DEPENDENCY"), "other", Set.of("CONTENT")));
    }
    private DependencyClosure closure() {
        return new DependencyClosure(assembly(false).versions(), List.of(new DependencyClosure.Requirement("animals-b", "library", "")), List.of());
    }
    private String draft(boolean core) {
        var trace = Map.of("assembly", assembly(core), "resolution", Map.of("status", "COMPLETE", "unresolved", 0),
                "contract", Map.of("mode", "NEW", "quantity", Map.of("scope", "TOTAL_FILES", "actual", 4, "met", true)),
                "goalEvidence", List.of(Map.of("selected", List.of("animals-b"))));
        var coverage = json.readTree("""
                {"schemaVersion":1,"requirements":[{"id":"r-0000000000000001","source":"生态","status":"SUPPORTING_EVIDENCE","mods":[{"slug":"animals-b","purpose":"生态","quote":"Adds real-world animals"}],"modCount":1,"missing":[],"missingCount":0,"note":"","criteria":[]}],"repairStatus":"NOT_NEEDED","repaired":[]}
                """);
        return "已构建4个文件。<name>生态</name><mods>animals-a,animals-b,library,other</mods><trace>" + MessageJson.write(trace)
                + "</trace><build_report>" + MessageJson.write(new BuildPresentation.Report(1, 4, Map.of("mobs", 2), List.of()))
                + "</build_report><requirement_report>" + MessageJson.write(coverage) + "</requirement_report><constraints>"
                + new AgentContractCodec().write(BuildConstraints.empty()) + "</constraints>";
    }
    private String prepare(OverlapSelections selections, boolean core) {
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of(
                "animals-a", json.readTree("{\"title\":\"Wildlife A\",\"description\":\"Adds real-world animals\",\"downloads\":100,\"categories\":[\"mobs\"]}"),
                "animals-b", json.readTree("{\"title\":\"Wildlife B\",\"description\":\"Adds real-world animals\",\"downloads\":10,\"categories\":[\"mobs\"]}"),
                "library", json.readTree("{\"categories\":[\"library\"]}"), "other", json.readTree("{\"categories\":[\"utility\"]}")));
        String result = selections.prepare(1L, 2L, payload, draft(core), assembly(core), closure(), api);
        assertFalse(result.contains("<mods>"));
        var match = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find()); return json.readTree(match.group(1)).path("ticket").asText();
    }
    private Map<String, String> choices(String... slugs) {
        var result = new HashMap<>(payload);
        result.put("replacementChoices", json.writeValueAsString(Map.of("real-animals", Map.of("slugs", List.of(slugs), "drop", false, "manualSlug", ""))));
        return result;
    }
    @Test void selectiveConfirmationPrunesOnlyCancelledRootAndItsOrphanDependencyAndRetractsCoverage() {
        var selections = new OverlapSelections(); String ticket = prepare(selections, false);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        assertTrue(result.contains("<mods>animals-a,other</mods>"), result);
        assertFalse(result.contains("已构建4个"));
        assertTrue(result.contains("UNVERIFIED"));
        assertTrue(result.contains("组合选择后"));
        assertTrue(result.contains("\"exclude\":[\"animals-b\"]"));
        assertTrue(result.contains("\"met\":false" ) || result.contains("\"actual\":2"));
        assertFalse(result.contains("iconUrl"));
        verify(api, times(1)).getProjectsBatch(anyCollection());
        verifyNoMoreInteractions(api);
    }
    @Test void keepAllReusesExactManifestAndDoesNotRepeatPreviouslyConfirmedCombination() {
        var selections = new OverlapSelections(); String ticket = prepare(selections, false);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a", "animals-b"), OverlapSelections.Confirmed::complete);
        assertTrue(result.contains("<mods>animals-a,animals-b,library,other</mods>"));
        assertTrue(result.contains("confirmedGroups") || result.contains("\"groups\""));
        String repeated = selections.prepare(1L, 2L, payload, draft(false), assembly(false), closure(), api);
        assertFalse(repeated.contains("replacement_options"));
        assertTrue(repeated.contains("<mods>"));
    }
    @Test void queueRejectionPreservesTicketAndProtectedCoreCannotBeUnchecked() {
        var selections = new OverlapSelections(); String ticket = prepare(selections, true);
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 2L, choices("animals-b"), OverlapSelections.Confirmed::complete));
        assertThrows(IllegalStateException.class, () -> selections.admit(ticket, 1L, 2L, choices("animals-a"), ignored -> { throw new IllegalStateException("queue full"); }));
        assertTrue(selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete).contains("<mods>"));
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete));
    }
    @Test void ownershipConversationAndAssemblyAreBoundAndCancellationDoesNotPublishDraft() {
        var selections = new OverlapSelections(); String ticket = prepare(selections, false);
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 2L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete));
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 3L, choices("animals-a"), OverlapSelections.Confirmed::complete));
        var changed = new HashMap<>(choices("animals-a")); changed.put("assembly", "different");
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 2L, changed, OverlapSelections.Confirmed::complete));
        assertFalse(selections.cancel(ticket, 2L)); assertTrue(selections.cancel(ticket, 1L));
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete));
    }
    @Test void expirationNeverAcceptsDefaults() {
        var nanos = new java.util.concurrent.atomic.AtomicLong(); var selections = new OverlapSelections(nanos::get);
        String ticket = prepare(selections, false); nanos.set(TimeUnit.MINUTES.toNanos(16));
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete));
    }
    @Test void pendingSelectionReleasesSingleWorkerForOtherAccounts() throws Exception {
        var selections = new OverlapSelections();
        var tasks = new DelegationTasks(true, 5000, 1, 8);
        try {
            var first = tasks.submit(null, 1L, "owner-one", null, () -> prepare(selections, false));
            String ticket = first.result().get(2, TimeUnit.SECONDS);
            var next = tasks.submit(null, 2L, "owner-two", null, () -> "other account finished");
            assertEquals("other account finished", next.result().get(2, TimeUnit.SECONDS));
            var resumed = selections.admit(ticket, 1L, 2L, choices("animals-a"), selected -> tasks.submit(null, 1L, "owner-one", null, selected::complete));
            assertTrue(resumed.result().get(2, TimeUnit.SECONDS).contains("<mods>animals-a,other</mods>"));
        } finally { tasks.close(); }
    }
    @Test void versionChangesInvalidatePriorCombinationAcknowledgement() {
        var selections = new OverlapSelections(); String ticket = prepare(selections, false);
        selections.admit(ticket, 1L, 2L, choices("animals-a", "animals-b"), OverlapSelections.Confirmed::complete);
        var old = assembly(false); var versions = new HashMap<>(old.versions()); versions.put("animals-a", "ChangedVersion");
        var changed = new AssemblySnapshot(old.loader(), old.mcVersion(), old.roots(), versions, old.origins());
        assertTrue(selections.prepare(1L, 2L, payload, draft(false), changed, closure(), api).contains("replacement_options"));
    }
    @Test void unknownVersionFilesCannotBeSilentlyLostBySelectiveFinalization() {
        var selections = new OverlapSelections();
        String draft = draft(false).replace("<mods>animals-a,animals-b,library,other</mods>", "<mods>animals-a,animals-b,library,other,unknown-file</mods>");
        String result = selections.prepare(1L, 2L, payload, draft, assembly(false), closure(), api);
        assertTrue(result.contains("unknown-file</mods>"));
        assertFalse(result.contains("replacement_options"));
        verifyNoInteractions(api);
    }

    @Test void unknownStaticFactsRemainIncompleteInResolutionProtocolAfterSelection() {
        var selections = new OverlapSelections(); prepare(selections, false);
        var unknown = new DependencyClosure(assembly(false).versions(), closure().requirements(),
                List.of(new DependencyClosure.Issue("other", "", "UNVERIFIED_METADATA", DependencyClosure.Severity.UNKNOWN)));
        String reply = selections.prepare(1L, 2L, payload, draft(false), assembly(false), unknown, api);
        var options = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>", java.util.regex.Pattern.DOTALL).matcher(reply);
        assertTrue(options.find()); String ticket = json.readTree(options.group(1)).path("ticket").asText();
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        var trace = java.util.regex.Pattern.compile("<trace>(.*?)</trace>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(trace.find()); var data = json.readTree(trace.group(1));
        assertEquals("INCOMPLETE", data.path("resolution").path("status").asText());
        assertEquals("UNVERIFIED", data.path("overlapSelection").path("staticClosure").path("status").asText());
    }


    @Test void selectiveFinalizationRecountsOptimizationPortfolioWithoutFetchingAgain() {
        var selections = new OverlapSelections(); prepare(selections, false);
        var goal = new yagen.waitmydawn.maa.model.OptimizationPortfolio.Goal("r-0000000000000001", "优化模组",
                yagen.waitmydawn.maa.model.OptimizationPortfolio.Side.BOTH,
                Set.of(yagen.waitmydawn.maa.model.OptimizationPortfolio.Role.MEMORY), true,
                yagen.waitmydawn.maa.model.agent.SelectionPreferences.DEFAULT);
        var facts = Map.of("animals-b", new yagen.waitmydawn.maa.model.OptimizationPortfolio.Fact("animals-b",
                yagen.waitmydawn.maa.model.OptimizationPortfolio.Side.BOTH,
                Map.of(yagen.waitmydawn.maa.model.OptimizationPortfolio.Role.MEMORY, "Reduces memory usage"), false));
        var portfolio = yagen.waitmydawn.maa.model.OptimizationPortfolio.report(List.of(goal), facts,
                assembly(false).versions(), new HashSet<>(assembly(false).roots()), 4, true);
        var original = new BuildPresentation.Report(1, 4, Map.of("mobs", 2), List.of());
        var enriched = new BuildPresentation.Report(1, 4, original.categories(), original.issues(), portfolio);
        String reply = draft(false).replace(MessageJson.write(original), MessageJson.write(enriched));
        String pending = selections.prepare(1L, 2L, payload, reply, assembly(false), closure(), api);
        var match = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>", java.util.regex.Pattern.DOTALL).matcher(pending);
        assertTrue(match.find());
        String result = selections.admit(json.readTree(match.group(1)).path("ticket").asText(), 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        var reportMatch = java.util.regex.Pattern.compile("<build_report>(.*?)</build_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(reportMatch.find()); var report = json.readTree(reportMatch.group(1)).path("optimization");
        assertEquals(0, report.path("modCount").asInt()); assertEquals(2, report.path("totalFiles").asInt());
        assertEquals(0, report.path("covered").size()); assertEquals("MEMORY", report.path("suggestedMissing").get(0).asText());
        verify(api, times(2)).getProjectsBatch(anyCollection()); verifyNoMoreInteractions(api);
    }
}
