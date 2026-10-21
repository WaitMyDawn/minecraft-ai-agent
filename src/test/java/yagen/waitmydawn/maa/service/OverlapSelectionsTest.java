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
    @Test void confirmingFewerProjectsRefreshesGlobalQuantityAndTraceTogether() {
        String original = draft(false);
        var before = java.util.regex.Pattern.compile("(?s)<trace>(.*?)</trace>").matcher(original);
        assertTrue(before.find());
        var trace = (tools.jackson.databind.node.ObjectNode) json.readTree(before.group(1));
        trace.withObject("/contract/quantity").put("target", 4).put("min", 4).put("max", 4).put("precision", "EXACT");
        original = original.replace(before.group(), "<trace>" + MessageJson.write(trace) + "</trace>");
        var selections = new OverlapSelections();
        String ticket = prepare(selections, false, BuildCompletion.enrich(original));
        clearInvocations(api);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        var reportMatch = java.util.regex.Pattern.compile("(?s)<requirement_report>(.*?)</requirement_report>").matcher(result);
        var traceMatch = java.util.regex.Pattern.compile("(?s)<trace>(.*?)</trace>").matcher(result);
        assertTrue(reportMatch.find()); assertTrue(traceMatch.find());
        var report = json.readTree(reportMatch.group(1));
        assertEquals("PARTIAL", report.path("completion").path("outcome").asText());
        assertEquals("NOT_MET", report.path("completion").path("quantity").path("status").asText());
        assertEquals(2, report.path("completion").path("quantity").path("actual").asInt());
        assertEquals(report, json.readTree(traceMatch.group(1)).path("requirementCoverage"));
        verifyNoInteractions(api);
    }

    @org.junit.jupiter.api.Test void laterCrossDimensionCandidateRequiresAnotherCombinationChoice() {
        var versions = Map.of("earlier", "v1", "later", "v2");
        var assembly = new AssemblySnapshot("neoforge", "1.21.1", List.of("earlier", "later"), versions, Map.of());
        var closure = new DependencyClosure(versions, List.of(), List.of());
        Map<String,tools.jackson.databind.JsonNode> facts = Map.of(
                "earlier", json.createObjectNode().put("description", "Overhauls the Nether terrain with many new biomes").put("downloads",100),
                "later", json.createObjectNode().put("description", "Overhauls both Overworld and Nether terrain with many new biomes").put("downloads",50));
        String reply = new OverlapSelections().prepareFromFacts(1L,2L,Map.of(),
                "<mods>earlier,later</mods><trace>{}</trace>",assembly,closure,api,()->false,
                Map.of("generation:nether",List.of("earlier")),PopupPreferences.defaults(),facts);
        assertTrue(reply.contains("<replacement_options>"), reply);
        assertTrue(reply.contains("earlier") && reply.contains("later"), reply);
        assertFalse(reply.contains("<mods>"), reply);
        verifyNoInteractions(api);
    }
    @Test void confirmationUpdatesAdmissionAndProvenanceToThePublishedSnapshotWithoutRefetching() {
        var assembled=assembly(false);var graph=new DependencyGraph();graph.allSlugs.addAll(assembled.versions().keySet());
        assembled.versions().forEach((slug,version) -> graph.selectedVersions.put(slug,json.createObjectNode().put("id",version)));
        closure().requirements().forEach(r -> graph.addEdge(r.dependent(),r.dependency()));
        var journal=new CandidateProvenance();journal.offer(graph.allSlugs,CandidateProvenance.Source.RETRIEVAL);
        journal.checkpoint("ASSEMBLED",graph.allSlugs,graph.selectedVersions);
        var before=journal.report(graph,graph.allSlugs,new HashSet<>(assembled.roots()),Map.of(),Map.of());
        var admission=new CandidateAdmission.Report(graph.allSlugs.stream().sorted().map(slug -> new CandidateAdmission.Row(slug,Set.of("ROOT"),"ALLOWED",List.of(),
                new PackAddonPolicy.Assessment(List.of(),false,false))).toList());
        String draft=draft(false);var matcher=java.util.regex.Pattern.compile("<trace>(.*?)</trace>",java.util.regex.Pattern.DOTALL).matcher(draft);assertTrue(matcher.find());
        var trace=(tools.jackson.databind.node.ObjectNode)json.readTree(matcher.group(1));
        trace.set("candidateAdmission",json.valueToTree(admission));trace.set("candidateProvenance",json.valueToTree(before));
        draft=draft.replace(matcher.group(),"<trace>"+MessageJson.write(trace)+"</trace>");
        var selections=new OverlapSelections();String ticket=prepare(selections,false,draft);clearInvocations(api);
        String reply=selections.admit(ticket,1L,2L,choices("animals-a"),OverlapSelections.Confirmed::complete);
        var afterMatcher=java.util.regex.Pattern.compile("<trace>(.*?)</trace>",java.util.regex.Pattern.DOTALL).matcher(reply);assertTrue(afterMatcher.find());
        var after=json.readTree(afterMatcher.group(1));
        for(var row : after.path("candidateProvenance").path("projects"))
            assertEquals(Set.of("animals-a","other").contains(row.path("slug").asText()),row.path("retained").asBoolean());
        assertTrue(after.path("candidateProvenance").path("changes").valueStream().anyMatch(c -> c.path("stage").asText().equals("USER_SELECTION")));
        assertTrue(after.path("candidateAdmission").path("projects").valueStream().anyMatch(r -> r.path("slug").asText().equals("animals-b") && r.path("status").asText().equals("NOT_RETAINED")));
        verifyNoInteractions(api);
    }
    @Test void independentAutomaticGroupsAreResolvedWithTheManualSnapshotAndCannotBeOverridden() {
        var fixture = new OverlapSelections(); prepare(fixture, false);
        var old = assembly(false);
        var versions = new LinkedHashMap<>(old.versions()); versions.put("nether-a","N1");versions.put("nether-b","N2");
        var roots = new ArrayList<>(old.roots()); roots.addAll(List.of("nether-a","nether-b"));
        var combined = new AssemblySnapshot(old.loader(),old.mcVersion(),roots,versions,old.origins());
        var facts = new LinkedHashMap<String,tools.jackson.databind.JsonNode>();
        for (String slug : List.of("animals-a","animals-b")) facts.put(slug,json.createObjectNode().put("description","Adds real-world animals").put("downloads",slug.endsWith("a") ? 100 : 10));
        for (String slug : List.of("nether-a","nether-b")) facts.put(slug,json.createObjectNode().put("description","Overhauls Nether terrain with many new biomes").put("downloads",slug.endsWith("a") ? 100 : 10));
        when(api.getProjectsBatch(anyCollection())).thenReturn(facts);
        String reply = draft(false).replace("<mods>animals-a,animals-b,library,other</mods>","<mods>animals-a,animals-b,library,other,nether-a,nether-b</mods>");
        var selections = new OverlapSelections();
        String offered = selections.prepare(1L,2L,payload,reply,combined,new DependencyClosure(versions,closure().requirements(),List.of()),api,
                () -> false,Map.of(),new PopupPreferences(true,true,true,false));
        var matcher = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>",java.util.regex.Pattern.DOTALL).matcher(offered);
        assertTrue(matcher.find(),offered); var options = json.readTree(matcher.group(1));
        assertEquals(1,options.path("groups").size());
        assertEquals("real-animals",options.path("groups").get(0).path("original").asText());
        String ticket = options.path("ticket").asText();
        var forged = new HashMap<>(payload);
        forged.put("replacementChoices",MessageJson.write(Map.of("generation:nether",new ReplacementSelections.Selection(List.of("nether-b"),false,""))));
        assertThrows(IllegalArgumentException.class,() -> selections.admit(ticket,1L,2L,forged,OverlapSelections.Confirmed::complete));
        String result = selections.admit(ticket,1L,2L,choices("animals-a"),OverlapSelections.Confirmed::complete);
        assertTrue(result.contains("<mods>animals-a,nether-a,other</mods>"),result);
        assertTrue(result.contains("automaticGroups"),result);
    }

    @Test void disabledOverlapUsesCardDefaultsAndPrunesOrphansWithoutAnotherFetch() {
        var fixture = new OverlapSelections(); prepare(fixture, false);
        clearInvocations(api);
        var result = new OverlapSelections().prepare(1L, 2L, payload, draft(false), assembly(false), closure(), api,
                () -> false, Map.of(), new PopupPreferences(true, true, false, true));
        assertTrue(result.contains("<mods>animals-a,other</mods>"), result);
        assertFalse(result.contains("replacement_options"));
        assertTrue(result.contains("已按设置采用推荐组合"));
        assertTrue(result.contains("\"exclude\":[\"animals-b\"]"));
        verify(api, times(1)).getProjectsBatch(anyCollection()); verifyNoMoreInteractions(api);
    }
    @Test void overlapSwitchDoesNotDisableGenerationConfirmation() {
        var fixture = new OverlapSelections(); prepare(fixture, false);
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of(
                "animals-a", json.readTree("{\"title\":\"A\",\"description\":\"Overhauls Nether terrain with many new biomes\",\"downloads\":100}"),
                "animals-b", json.readTree("{\"title\":\"B\",\"description\":\"Overhauls Nether terrain with many new biomes\",\"downloads\":10}")));
        String reply = new OverlapSelections().prepare(1L,2L,payload,draft(false),assembly(false),closure(),api,
                () -> false, Map.of(), new PopupPreferences(true,true,false,true));
        assertTrue(reply.contains("replacement_options"),reply);
        assertTrue(reply.contains("generation:nether"),reply);
    }

    @Test void quantityIsRecountedAfterRemovingAnOptionalRoot() {
        String original = draft(false);
        var pattern = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL);
        var match = pattern.matcher(original); assertTrue(match.find());
        var report = json.readTree(match.group(1));
        var row = (tools.jackson.databind.node.ObjectNode)report.path("requirements").get(0);
        ((tools.jackson.databind.node.ArrayNode)row.path("mods")).addObject().put("slug","animals-a").put("purpose","生态").put("quote","Adds real-world animals");
        row.put("modCount",2);
        row.putObject("quantity").put("min",2).put("max",3).put("scope","RETAINED_ROOTS").put("actual",2).put("complete",true)
                .putArray("projects").add("animals-a").add("animals-b");
        original = original.replace(match.group(),"<requirement_report>"+MessageJson.write(report)+"</requirement_report>");
        var selections = new OverlapSelections(); String ticket = prepare(selections,false,original);
        String result = selections.admit(ticket,1L,2L,choices("animals-a"),OverlapSelections.Confirmed::complete);
        var after = pattern.matcher(result);assertTrue(after.find());
        var updated = json.readTree(after.group(1)).path("requirements").get(0);
        assertEquals(1,updated.path("quantity").path("actual").asInt());
        assertEquals("PARTIAL",updated.path("status").asText());
        assertEquals("animals-a",updated.path("quantity").path("projects").get(0).asText());
    }

    @Test void partialMetadataStillOffersConfirmedOverlapInsteadOfDiscardingSuccessfulDescriptions() {
        var first = json.createObjectNode().put("title", "Animals A").put("description", "Adds real-world animals");
        var second = json.createObjectNode().put("title", "Animals B").put("description", "Adds real-world animals");
        when(api.getProjectsBatch(anyCollection())).thenThrow(new ModrinthApiClient.PartialProjectBatchException(
                Map.of("animals-a", first, "animals-b", second)));
        String result = new OverlapSelections().prepare(1L, 2L, payload, draft(false), assembly(false), closure(), api);
        assertTrue(result.contains("<replacement_options>"), result);
        assertTrue(result.contains("real-animals"), result);
        assertTrue(result.contains("\"unverifiedDescriptions\":2"), result);
        verify(api, times(1)).getProjectsBatch(anyCollection());
        verifyNoMoreInteractions(api);
    }

    @Test void cancellationOfFinalAuditDoesNotPublishAnUncheckedDraft() {
        when(api.getProjectsBatch(anyCollection())).thenThrow(new CancellationException());
        assertThrows(CancellationException.class, () -> new OverlapSelections().prepare(
                1L, 2L, payload, draft(false), assembly(false), closure(), api));
    }

    @Test void cancellingACoreAlsoRetractsItsProjectMetadataGenerationProof() {
        var selections = new OverlapSelections();
        String original = draft(false);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(original);
        assertTrue(match.find()); var report = json.readTree(match.group(1));
        var row = (tools.jackson.databind.node.ObjectNode) report.path("requirements").get(0);
        var mod = (tools.jackson.databind.node.ObjectNode) row.path("mods").get(0);
        mod.put("evidenceType", "PROJECT_GENERATION").put("quote", "Overhauls Nether terrain with many new biomes");
        var state = row.putObject("evidenceState").put("code", "VERIFIED").put("countsComplete", true);
        state.putArray("projects"); state.putArray("generation").addObject().put("dimension", "nether").put("label", "下界").putArray("projects").add("animals-b");
        String reply = original.replace(match.group(), "<requirement_report>" + MessageJson.write(report) + "</requirement_report>");
        String ticket = prepare(selections, false, reply);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        var finalMatch = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(finalMatch.find()); var finalRow = json.readTree(finalMatch.group(1)).path("requirements").get(0);
        assertTrue(finalRow.path("mods").isEmpty());
        assertEquals("CORE_GENERATION_PENDING", finalRow.path("evidenceState").path("code").asText());
        assertTrue(finalRow.path("evidenceState").path("generation").get(0).path("projects").isEmpty());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void removingTheCoreCannotLeaveDimensionCoverageVerifiedThroughAnUnrelatedQuote(boolean truncated) {
        var selections = new OverlapSelections();
        String original = draft(false);
        var row = json.createObjectNode().put("id", "r-0000000000000001").put("source", "丰富下界生态")
                .put("status", "SUPPORTING_EVIDENCE").put("modCount", 2).put("missingCount", 0);
        row.putArray("mods").addObject().put("slug", "animals-b").put("quote", "Nether expansion with custom terrain");
        ((tools.jackson.databind.node.ArrayNode)row.path("mods")).addObject().put("slug", "other").put("quote", "Adds Nether structures");
        if (truncated) {
            ((tools.jackson.databind.node.ArrayNode)row.path("mods")).remove(0);
            row.put("modCount", 25);
        }
        row.putArray("missing"); row.putArray("criteria");
        var state = row.putObject("evidenceState").put("code", "VERIFIED").put("countsComplete", true);
        state.putArray("projects");
        state.putArray("generation").addObject().put("dimension", "nether").put("label", "下界").putArray("projects").add("animals-b");
        var report = json.createObjectNode(); report.putArray("requirements").add(row);
        String replacement = original.replaceFirst("(?s)<requirement_report>.*?</requirement_report>",
                java.util.regex.Matcher.quoteReplacement("<requirement_report>" + MessageJson.write(report) + "</requirement_report>"));
        String ticket = prepare(selections, false, replacement);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        assertTrue(result.contains("CORE_GENERATION_PENDING"), result);
        assertTrue(result.contains("\"status\":\"PARTIAL\""), result);
        assertTrue(result.contains("Adds Nether structures"), result);
    }
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
    @Test void verifiedGoalUsesSameDefaultsInCardsAndAutomaticSelectionButRemainsOptional() {
        prepare(new OverlapSelections(), false);
        String original = draft(false);
        var matcher = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(original);
        assertTrue(matcher.find());
        var coverage = (tools.jackson.databind.node.ObjectNode) json.readTree(matcher.group(1));
        coverage.putArray("ledger").addObject().put("targetId", "r-0000000000000001").put("slug", "animals-b")
                .put("versionId", "V2").put("status", "VERIFIED");
        original = original.replace(matcher.group(), "<requirement_report>" + MessageJson.write(coverage) + "</requirement_report>");
        var selections = new OverlapSelections();
        String pending = selections.prepare(1L, 2L, payload, original, assembly(false), closure(), api);
        var optionsMatcher = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>", java.util.regex.Pattern.DOTALL).matcher(pending);
        assertTrue(optionsMatcher.find()); var options = json.readTree(optionsMatcher.group(1));
        assertEquals(2, options.path("recommendedChoices").path("real-animals").path("slugs").size());
        assertTrue(options.path("groups").get(0).path("candidates").valueStream().allMatch(c -> c.path("recommended").asBoolean()));
        String automatic = new OverlapSelections().prepare(1L, 2L, payload, original, assembly(false), closure(), api,
                () -> false, Map.of(), new PopupPreferences(true, true, false, true));
        assertTrue(automatic.contains("<mods>animals-a,animals-b,library,other</mods>"));
        assertFalse(automatic.contains("replacement_options"));
        String manual = selections.admit(options.path("ticket").asText(), 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        assertTrue(manual.contains("<mods>animals-a,other</mods>"));
    }
    private String prepare(OverlapSelections selections, boolean core) {
        return prepare(selections, core, draft(core));
    }
    private String prepare(OverlapSelections selections, boolean core, String reply) {
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of(
                "animals-a", json.readTree("{\"title\":\"Wildlife A\",\"description\":\"Adds real-world animals\",\"downloads\":100,\"categories\":[\"mobs\"]}"),
                "animals-b", json.readTree("{\"title\":\"Wildlife B\",\"description\":\"Adds real-world animals\",\"downloads\":10,\"categories\":[\"mobs\"]}"),
                "library", json.readTree("{\"categories\":[\"library\"]}"), "other", json.readTree("{\"categories\":[\"utility\"]}")));
        String result = selections.prepare(1L, 2L, payload, reply, assembly(core), closure(), api);
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
    @Test void duplicateInventoryHudsUseTheSameProtectedAndExplicitConfirmationBoundary() {
        var selections = new OverlapSelections();
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of(
                "animals-a", json.readTree("{\"description\":\"Displays your inventory on screen\",\"categories\":[\"utility\"]}"),
                "animals-b", json.readTree("{\"description\":\"Shows player inventory as an overlay\",\"categories\":[\"utility\"]}")));
        String waiting = selections.prepare(1L, 2L, payload, draft(true), assembly(true), closure(), api);
        assertFalse(waiting.contains("<mods>"));
        String optionsText = waiting.substring(waiting.indexOf("<replacement_options>") + "<replacement_options>".length(), waiting.indexOf("</replacement_options>"));
        var options = json.readTree(optionsText);
        assertEquals("inventory-hud", options.path("groups").get(0).path("original").asText());
        assertTrue(options.path("groups").get(0).path("explanation").asText().contains("重复显示"));
        var choice = new HashMap<>(payload);
        choice.put("replacementChoices", json.writeValueAsString(Map.of("inventory-hud",
                Map.of("slugs", List.of("animals-a"), "drop", false, "manualSlug", ""))));
        String result = selections.admit(options.path("ticket").asText(), 1L, 2L, choice, OverlapSelections.Confirmed::complete);
        assertTrue(result.contains("<mods>animals-a,other</mods>"), result);
        assertFalse(result.contains("iconUrl"));
    }
    @Test void selectingOneOfTwoSupportingProjectsRetainsRemainingPurposeEvidence() {
        String reply = draft(false).replace("\"modCount\":1", "\"modCount\":2")
                .replace("\"mods\":[{\"slug\":\"animals-b\"", "\"mods\":[{\"slug\":\"animals-a\",\"purpose\":\"生态\",\"quote\":\"Adds real-world animals\"},{\"slug\":\"animals-b\"");
        var selections = new OverlapSelections(); String ticket = prepare(selections, false, reply);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find()); var row = json.readTree(match.group(1)).path("requirements").get(0);
        assertEquals("SUPPORTING_EVIDENCE", row.path("status").asText());
        assertEquals("VERIFIED", row.path("evidenceState").path("code").asText());
        assertEquals("animals-a", row.path("mods").get(0).path("slug").asText());
        assertEquals(1, row.path("modCount").asInt());
    }
    @Test void truncatedEvidenceCannotBecomeAnExactRecountAfterSelection() {
        String reply = draft(false).replace("\"modCount\":1", "\"modCount\":30");
        var selections = new OverlapSelections(); String ticket = prepare(selections, false, reply);
        String result = selections.admit(ticket, 1L, 2L, choices("animals-a"), OverlapSelections.Confirmed::complete);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find()); var row = json.readTree(match.group(1)).path("requirements").get(0);
        assertFalse(row.path("evidenceState").path("countsComplete").asBoolean(true));
        assertEquals("DISPLAY_TRUNCATED", row.path("evidenceState").path("code").asText());
        assertEquals(0, row.path("missingCount").asInt());
    }
    @Test void changedIntegrationPartnerDoesNotTurnRetainedIdentityOrPurposeIntoMissingProject() {
        for (String type : List.of("PROJECT_IDENTITY", "REVIEW_QUOTE", "VERSION_RELATION", "DESCRIPTION_RELATION")) {
            var report = json.readTree("""
                    {"mods":[{"slug":"retained-addon","purpose":"作用","quote":"Adds content","evidenceType":"%s"}],
                    "modCount":1,"missing":[],"missingCount":0,"status":"SUPPORTING_EVIDENCE","criteria":[],"note":""}
                    """.formatted(type));
            org.springframework.test.util.ReflectionTestUtils.invokeMethod(OverlapSelections.class, "adjustCoverage", report,
                    Set.of("removed-partner"), Set.of("removed-partner", "retained-addon"));
            assertEquals(0, report.path("missingCount").asInt(), type);
            if (Set.of("VERSION_RELATION", "DESCRIPTION_RELATION").contains(type)) {
                assertEquals("RELATION_PENDING", report.path("evidenceState").path("code").asText());
                assertEquals("UNVERIFIED", report.path("status").asText());
            } else {
                assertEquals(1, report.path("mods").size(), type);
                assertEquals("SUPPORTING_EVIDENCE", report.path("status").asText(), type);
            }
        }
    }
    @Test void explicitPartnerRemovalWithdrawsRelationEvenWithoutLegacyTraceLinks() {
        var report = json.readTree("""
                {"mods":[{"slug":"retained-addon","purpose":"联动","quote":"Integration between cooking-core and factory",
                "evidenceType":"DESCRIPTION_RELATION","partners":["cooking-core","factory"]}],
                "modCount":1,"missing":[],"missingCount":0,"status":"SUPPORTING_EVIDENCE","criteria":[],"note":""}
                """);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(OverlapSelections.class, "adjustCoverage", report,
                Set.of("factory"), Set.of());
        assertEquals("RELATION_PENDING", report.path("evidenceState").path("code").asText());
        assertEquals("UNVERIFIED", report.path("status").asText());
        assertEquals(0, report.path("missingCount").asInt());
    }
    @Test void actualRemovalOfNamedProjectIsDistinctFromLossOfPurposeEvidence() {
        var report = json.readTree("""
                {"mods":[{"slug":"named","purpose":"点名","quote":"","evidenceType":"PROJECT_IDENTITY"}],
                "modCount":1,"missing":[],"missingCount":0,"status":"VERIFIED_PROJECTS","criteria":[],"note":""}
                """);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(OverlapSelections.class, "adjustCoverage", report,
                Set.of("named"), Set.of("named"));
        assertEquals("PROJECT_MISSING", report.path("evidenceState").path("code").asText());
        assertEquals("named", report.path("missing").get(0).asText());
        assertEquals(1, report.path("missingCount").asInt());
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
    @Test void concurrentDuplicateConfirmationEnqueuesAtMostOnce() throws Exception {
        var selections = new OverlapSelections(); String ticket = prepare(selections, false);
        var start = new CountDownLatch(1); var submitted = new java.util.concurrent.atomic.AtomicInteger();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> confirm = () -> {
                start.await();
                try {
                    selections.admit(ticket, 1L, 2L, choices("animals-a"), value -> { submitted.incrementAndGet(); return value.complete(); });
                    return true;
                } catch (IllegalArgumentException consumed) { return false; }
            };
            var first = executor.submit(confirm); var second = executor.submit(confirm); start.countDown();
            assertNotEquals(first.get(2, TimeUnit.SECONDS), second.get(2, TimeUnit.SECONDS));
            assertEquals(1, submitted.get());
        } finally { start.countDown(); executor.shutdownNow(); }
    }
    @Test void cancellingQueuedConfirmationDoesNotAcknowledgeOrPublishDraft() throws Exception {
        var selections = new OverlapSelections(); String ticket = prepare(selections, false);
        var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        var tasks = new DelegationTasks(true, 5000, 1, 8);
        try {
            var blocker = tasks.submit(null, 2L, "owner-two", null, () -> { started.countDown(); release.await(); return "done"; });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            var queued = selections.admit(ticket, 1L, 2L, choices("animals-a", "animals-b"),
                    value -> tasks.submit(null, 1L, "owner-one", null, value::complete));
            assertEquals(1, tasks.queuePosition(queued)); tasks.cancel(queued);
            release.countDown(); assertEquals("done", blocker.result().get(2, TimeUnit.SECONDS));
            assertTrue(queued.result().isCancelled());
            assertTrue(selections.prepare(1L, 2L, payload, draft(false), assembly(false), closure(), api).contains("replacement_options"));
        } finally { release.countDown(); tasks.close(); }
    }
    @Test void expiredTicketNeverCallsSubmissionAndAnotherAccountRemainsUsable() {
        var nanos = new java.util.concurrent.atomic.AtomicLong(); var selections = new OverlapSelections(nanos::get);
        String ticket = prepare(selections, false); nanos.set(TimeUnit.MINUTES.toNanos(15));
        var submitted = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(IllegalArgumentException.class, () -> selections.admit(ticket, 1L, 2L, choices("animals-a"),
                value -> { submitted.incrementAndGet(); return value.complete(); }));
        assertEquals(0, submitted.get());
        String reply = selections.prepare(2L, 3L, payload, draft(false), assembly(false), closure(), api);
        var match = java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>", java.util.regex.Pattern.DOTALL).matcher(reply);
        assertTrue(match.find());
        assertTrue(selections.admit(json.readTree(match.group(1)).path("ticket").asText(), 2L, 3L,
                choices("animals-a"), OverlapSelections.Confirmed::complete).contains("<mods>"));
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
