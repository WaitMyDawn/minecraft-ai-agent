package yagen.waitmydawn.maa.eval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class GoldenAssertionsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @Test void bothFrozenDatasetsUseSupportedAssertions() throws Exception {
        for (String file : List.of("eval/golden-set.json", "eval/golden-set-v2.json", "eval/golden-set-v3.json", "eval/golden-set-v4.json"))
            assertDoesNotThrow(() -> GoldenSetEval.validateSet(mapper.readTree(Files.readAllBytes(Path.of(file)))));
    }
    @Test void hiddenReportsCannotPassVisibleReplyAssertions() {
        assertEquals("结果", GoldenSetEval.visibleReply("结果<requirement_report>{\"note\":\"隐藏目标\"}</requirement_report><build_report>{}</build_report><replacement_choices>{}</replacement_choices>"));
    }
    @Test void coverageRejectsInventedRetentionAndMissingOriginalRows() {
        var rule = mapper.readTree("{\"sourcesContain\":[\"自动化\"],\"minSupporting\":1,\"retainedEvidence\":true,\"maxRepairAdds\":3}");
        var facts = mapper.readTree("""
                {"requirement_report":{"schemaVersion":1,"repaired":[],"requirements":[{"id":"r-0000000000000000","source":"自动化","status":"SUPPORTING_EVIDENCE","mods":[{"slug":"factory"}],"missing":[]}]},
                "trace":{"assembly":{"versions":{"factory":"V1"}},"contract":{"requirementLedger":[{"id":"r-0000000000000000","kind":"CAPABILITY"}]}}}
                """);
        assertTrue(GoldenAssertions.checkCoverage(facts, Set.of("factory"), rule).isEmpty());
        assertFalse(GoldenAssertions.checkCoverage(facts, Set.of(), rule).isEmpty());
        ((tools.jackson.databind.node.ArrayNode) facts.at("/trace/contract/requirementLedger")).addObject().put("id", "r-1111111111111111").put("kind", "CAPABILITY");
        assertFalse(GoldenAssertions.checkCoverage(facts, Set.of("factory"), rule).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> GoldenAssertions.validateCoverage(mapper.readTree("{\"minSupporting\":\"1\"}")));
    }
    @Test void missingMetricsNeverPassAsZeroOrEmpty() {
        var facts = mapper.readTree("{\"/retrieval/count\":{\"max\":300},\"/exclude\":{\"absent\":[\"jade\"]}}");
        assertEquals(2, GoldenAssertions.check(mapper.readTree("{}"), facts).size());
        assertEquals(2, GoldenAssertions.check(mapper.readTree("{\"retrieval\":{\"count\":\"0\"},\"exclude\":null}"), facts).size());
        assertTrue(GoldenAssertions.check(mapper.readTree("{\"retrieval\":{\"count\":0},\"exclude\":[]}"), facts).isEmpty());
    }
    @Test void parentSupportCannotHideMissingOrUnretainedChildEvidence() {
        var facts = mapper.readTree("""
                {"requirement_report":{"schemaVersion":1,"repaired":[],"requirements":[{"id":"r-0000000000000000","source":"自动化和物流","status":"SUPPORTING_EVIDENCE","mods":[],"missing":[],"criteria":[{"id":"r-1111111111111111","source":"自动化","status":"SUPPORTING_EVIDENCE","mods":[{"slug":"factory"}],"missing":[]},{"id":"r-2222222222222222","source":"物流","status":"UNVERIFIED","mods":[],"missing":[]}]}]},
                "trace":{"assembly":{"versions":{"factory":"V1"}},"contract":{"requirementLedger":[{"id":"r-0000000000000000","kind":"CAPABILITY","criteria":[{"source":"自动化","kind":"CAPABILITY"},{"source":"物流","kind":"CAPABILITY"}]}]}}}
                """);
        var rule = mapper.readTree("{\"retainedEvidence\":true}");
        assertTrue(GoldenAssertions.checkCoverage(facts, Set.of("factory"), rule).stream().anyMatch(s -> s.contains("掩盖")));
        ((tools.jackson.databind.node.ObjectNode)facts.at("/requirement_report/requirements/0")).put("status", "PARTIAL");
        assertTrue(GoldenAssertions.checkCoverage(facts, Set.of("factory"), rule).isEmpty());
        assertTrue(GoldenAssertions.checkCoverage(facts, Set.of(), rule).stream().anyMatch(s -> s.contains("子目标用途依据")));
        ((tools.jackson.databind.node.ArrayNode)facts.at("/requirement_report/requirements/0/criteria")).remove(1);
        assertTrue(GoldenAssertions.checkCoverage(facts, Set.of("factory"), rule).stream().anyMatch(s -> s.contains("遗漏子目标")));
    }
    @Test void typosFailBeforeAnyHttpRequest() {
        assertThrows(IllegalArgumentException.class, () -> GoldenAssertions.validate(mapper.readTree("{\"/count\":{\"maximum\":300}}")));
        assertThrows(IllegalArgumentException.class, () -> GoldenSetEval.validateSet(mapper.readTree("""
                {"cases":[{"id":"x","layer":"agent","assert":{"modContains":["jade"]}}]}
                """)));
        assertThrows(IllegalArgumentException.class, () -> GoldenSetEval.validateSet(mapper.readTree("""
                {"cases":[{"id":"x","layer":"agent","assert":{}},{"id":"x","layer":"agent","assert":{}}]}
                """)));
    }
    @Test void honestDiagnosticsDoNotTurnIncompleteBuildIntoSatisfiedGoal() {
        var trace = mapper.readTree("""
                {"contract":{"mode":"NEW","missingMustHave":[],"quantity":{"met":true}},"resolution":{"status":"COMPLETE"}}
                """);
        assertEquals("MET_STATIC_CONSTRAINTS", GoldenSetEval.constructionStatus(trace));
        ((tools.jackson.databind.node.ObjectNode)trace.get("resolution")).put("status", "INCOMPLETE");
        assertEquals("UNMET", GoldenSetEval.constructionStatus(trace));
        ((tools.jackson.databind.node.ObjectNode)trace.get("resolution")).put("status", "COMPLETE");
        ((tools.jackson.databind.node.ArrayNode)trace.get("contract").get("missingMustHave")).add("alexs-mobs");
        assertEquals("UNMET", GoldenSetEval.constructionStatus(trace));
        assertEquals("NOT_ASSESSED", GoldenSetEval.constructionStatus(mapper.readTree("{}")));
    }
    @Test void multiRoundCarriesReturnedStateAndSeparatesRepeatedSessions() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<tools.jackson.databind.JsonNode> requests = Collections.synchronizedList(new ArrayList<>());
        String constraints = "{\"exclude\":[\"journeymap\"]}";
        server.createContext("/api/chat", exchange -> {
            var input = mapper.readTree(exchange.getRequestBody().readAllBytes()); requests.add(input);
            String mods = input.path("prompt").asText().equals("first") ? "jade" : "jade,jei";
            byte[] reply = ("<mods>" + mods + "</mods><mc>1.21.1</mc><loader>neoforge</loader><constraints>"
                    + constraints + "</constraints><trace>{\"assembly\":{\"versions\":{\"jade\":\"V1\"},\"roots\":[\"jade\"]}}</trace>").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            var scenario = mapper.readTree("""
                    {"turns":[{"prompt":"first","assert":{"modsContain":["jade"]}},
                    {"prompt":"second","assert":{"modsContain":["jade","jei"],"facts":{"/constraints/exclude":{"contains":["journeymap"]}}}}]}
                    """);
            var eval = new GoldenSetEval();
            org.springframework.test.util.ReflectionTestUtils.setField(eval, "client",
                    new EvalTaskClient("http://127.0.0.1:" + server.getAddress().getPort(), "test-token", ""));
            var first = eval.callScenario(scenario); var second = eval.callScenario(scenario);
            assertTrue(first.failures.isEmpty(), first.failures.toString()); assertTrue(second.failures.isEmpty());
            assertEquals(2, first.turns.size());
            assertEquals(2, first.nodeCount);
            assertEquals("neoforge", first.envLoader);
            assertEquals("jade", requests.get(1).path("currentMods").asText());
            assertEquals("neoforge", requests.get(1).path("loader").asText());
            assertEquals("1.21.1", requests.get(1).path("mcVersion").asText());
            assertEquals(mapper.readTree(constraints), mapper.readTree(requests.get(1).path("constraints").asText()));
            assertEquals("V1", mapper.readTree(requests.get(1).path("assembly").asText()).path("versions").path("jade").asText());
            assertEquals(requests.get(0).path("uuid"), requests.get(1).path("uuid"));
            assertNotEquals(requests.get(0).path("uuid"), requests.get(2).path("uuid"));
        } finally { server.stop(0); }
    }

    @Test void confirmationRetainsWorkspaceAndDoesNotInventDefaultAcceptance() {
        var original = mapper.createObjectNode().put("prompt", "交通主题").put("currentMods", "old")
                .put("assembly", "snapshot").put("uuid", "original");
        var basic = GoldenSetEval.confirmationInput(original, "<clarification_context>{\"schemaVersion\":1,\"ticket\":\"basic-ticket\"}</clarification_context>",
                mapper.readTree("{\"kind\":\"BASIC\",\"answer\":\"好的\"}"));
        assertEquals("好的", basic.path("prompt").asText()); assertEquals("snapshot", basic.path("assembly").asText());
        assertEquals("basic-ticket", basic.path("clarificationTicket").asText()); assertEquals("交通主题", original.path("prompt").asText());
        var overlap = GoldenSetEval.confirmationInput(original,
                "<replacement_options>{\"kind\":\"OVERLAP\",\"ticket\":\"overlap-ticket\",\"groups\":[{\"original\":\"inventory\",\"candidates\":[{\"slug\":\"a\"},{\"slug\":\"b\"}]}]}</replacement_options>",
                mapper.readTree("{\"kind\":\"OVERLAP\",\"strategy\":\"KEEP_ALL\"}"));
        assertEquals("OVERLAP", overlap.path("selectionKind").asText()); assertEquals("交通主题", overlap.path("prompt").asText());
        assertEquals(2, mapper.readTree(overlap.path("replacementChoices").asText()).at("/inventory/slugs").size());
        assertThrows(IllegalArgumentException.class, () -> GoldenSetEval.validateConfirmation(mapper.readTree("{\"kind\":\"OVERLAP\"}")));
        assertThrows(IllegalArgumentException.class, () -> GoldenSetEval.confirmationInput(original, "没有票据", mapper.readTree("{\"kind\":\"BASIC\",\"answer\":\"好的\"}")));
    }
    @Test void optimizationChecksSelectedVersionAndTargetSideInsteadOfGlobalRolePresence() {
        var facts = mapper.readTree("""
            {"build_report":{"optimization":{"schemaVersion":1,"modCount":1,"referenceActive":false,
            "entries":[{"slug":"memory","versionId":"V1","roles":{"MEMORY":"Reduces memory"}}],
            "targets":[{"side":"CLIENT","covered":["MEMORY"]},{"side":"SERVER","covered":[]}]}},
            "trace":{"assembly":{"versions":{"memory":"V1"}}}}
            """);
        var client = mapper.readTree("{\"side\":\"CLIENT\",\"rolesContain\":[\"MEMORY\"],\"minMods\":1,\"referenceActive\":false,\"retainedEvidence\":true}");
        assertTrue(GoldenAssertions.checkOptimization(facts, Set.of("memory"), client).isEmpty());
        assertFalse(GoldenAssertions.checkOptimization(facts, Set.of(), client).isEmpty());
        var server = mapper.readTree("{\"side\":\"SERVER\",\"rolesContain\":[\"MEMORY\"]}");
        assertFalse(GoldenAssertions.checkOptimization(facts, Set.of("memory"), server).isEmpty());
        assertFalse(GoldenAssertions.checkOptimization(mapper.createObjectNode(), Set.of(), client).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> GoldenAssertions.validateOptimization(mapper.readTree("{\"minMods\":\"1\"}")));
    }

    @Test void scenarioResumesBasicAndOverlapWithoutPublishingPausedDraftState() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<tools.jackson.databind.JsonNode> requests = new ArrayList<>();
        server.createContext("/api/chat", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            String reply = switch(requests.size()) {
                case 1 -> "<clarification_context>{\"schemaVersion\":1,\"ticket\":\"basic\"}</clarification_context><trace>{\"awaitingInput\":true}</trace>";
                case 2 -> "<replacement_options>{\"kind\":\"OVERLAP\",\"ticket\":\"overlap\",\"groups\":[{\"original\":\"inventory\",\"candidates\":[{\"slug\":\"a\"},{\"slug\":\"b\"}]}]}</replacement_options><trace>{\"awaitingInput\":true}</trace>";
                default -> "<mods>a,b</mods><mc>1.21.1</mc><loader>neoforge</loader><trace>{\"awaitingInput\":false}</trace>";
            };
            byte[] bytes = reply.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var scenario = mapper.readTree("""
                {"turns":[{"prompt":"transport","assert":{"facts":{"/trace/awaitingInput":{"equals":true}}}},
                {"confirmation":{"kind":"BASIC","answer":"好的"},"assert":{"facts":{"/replacement_options/kind":{"equals":"OVERLAP"}}}},
                {"confirmation":{"kind":"OVERLAP","strategy":"KEEP_ALL"},"assert":{"modsContain":["a","b"]}}]}
                """);
            var eval = new GoldenSetEval();
            org.springframework.test.util.ReflectionTestUtils.setField(eval,"client",new EvalTaskClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-token",""));
            var result = eval.callScenario(scenario);
            assertTrue(result.failures.isEmpty(),result.failures.toString()); assertEquals(3,result.turns.size());
            assertEquals("basic",requests.get(1).path("clarificationTicket").asText());
            assertFalse(requests.get(2).has("clarificationTicket")); assertEquals("OVERLAP",requests.get(2).path("selectionKind").asText());
            assertEquals("",requests.get(2).path("currentMods").asText());
            assertEquals(requests.get(0).path("uuid"),requests.get(2).path("uuid"));
        } finally { server.stop(0); }
    }

    @Test void missingConfirmationTicketFailsTheTurnInsteadOfAbortingAllCases() throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/api/chat",exchange->{
            requests.incrementAndGet();exchange.getRequestBody().readAllBytes();
            byte[] reply="<trace>{\"awaitingInput\":true}</trace>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,reply.length);exchange.getResponseBody().write(reply);exchange.close();
        });
        server.start();
        try {
            var eval=new GoldenSetEval();
            org.springframework.test.util.ReflectionTestUtils.setField(eval,"client",new EvalTaskClient("http://127.0.0.1:"+server.getAddress().getPort(),"test-token",""));
            var scenario=mapper.readTree("""
                {"turns":[{"prompt":"transport","assert":{"trace":{"exists":true}}},
                {"confirmation":{"kind":"BASIC","answer":"好的"},"assert":{"minMods":1}}]}
                """);
            var result=assertDoesNotThrow(()->eval.callScenario(scenario));
            assertEquals(2,result.turns.size()); assertFalse(result.failures.isEmpty()); assertEquals(1,requests.get());
        } finally {server.stop(0);}
    }
}
