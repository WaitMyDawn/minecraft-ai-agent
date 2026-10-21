package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.service.MessageJson;
import java.util.*;
import java.util.regex.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildCompletionTest {
    private final ObjectMapper json = JsonMapper.builder().build();
    private JsonNode cases() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/build-completion-cases.json")) {
            assertNotNull(input); return json.readTree(input);
        }
    }
    private JsonNode tag(String text, String name) {
        var match = Pattern.compile("(?s)<" + name + ">(.*?)</" + name + ">").matcher(text);
        assertTrue(match.find(), name); return json.readTree(match.group(1));
    }
    private String reply(JsonNode coverage, JsonNode trace) {
        return "已构建清单\n<requirement_report>" + MessageJson.write(coverage) + "</requirement_report>\n<trace>"
                + MessageJson.write(trace) + "</trace>\n<mods>core</mods>";
    }

    @Test void sharedGlobalAndLocalCasesHaveOneProductionConclusion() throws Exception {
        for (var fixture : cases()) assertEquals(fixture.path("expected"),
                json.valueToTree(BuildCompletion.assess(fixture.path("coverage"), fixture.path("trace"))), fixture.path("name").asText());
    }

    @Test void publicationAndTraceShareSameSummaryAndRefreshingIsIdempotent() throws Exception {
        var fixture = cases().get(0);
        String enriched = BuildCompletion.enrich(reply(fixture.path("coverage"), fixture.path("trace")));
        assertEquals(fixture.path("expected"), tag(enriched,"requirement_report").path("completion"));
        assertEquals(tag(enriched,"requirement_report"), tag(enriched,"trace").path("requirementCoverage"));
        assertEquals(enriched, BuildCompletion.enrich(enriched));
        assertTrue(enriched.contains("<mods>core</mods>"));
    }

    @Test void selectionRefreshDoesNotKeepEarlierGlobalSuccess() throws Exception {
        var fixture = cases().get(1);
        String before = BuildCompletion.enrich(reply(fixture.path("coverage"), fixture.path("trace")));
        var trace = (ObjectNode) tag(before,"trace");
        trace.withObject("/contract/quantity").put("actual",21).put("met",false);
        String after = BuildCompletion.enrich(before.replaceFirst("(?s)<trace>.*?</trace>",
                Matcher.quoteReplacement("<trace>" + MessageJson.write(trace) + "</trace>")));
        var completion = tag(after,"requirement_report").path("completion");
        assertEquals("PARTIAL",completion.path("outcome").asText());
        assertEquals(21,completion.path("quantity").path("actual").asInt());
        assertEquals(3,completion.path("targets").path("met").asInt());
    }

    @Test void missingReportDoesNotMakeRecordedRequirementsDisappear() throws Exception {
        var fixture = cases().get(0); var trace = fixture.path("trace").deepCopy();
        ((ObjectNode) trace.path("contract")).putArray("requirementLedger").add("r-0000000000000001");
        ((ObjectNode) trace.path("contract").path("quantity")).put("actual",50).put("met",true);
        String result = BuildCompletion.enrich("<trace>" + MessageJson.write(trace) + "</trace><mods>core</mods>");
        assertEquals("UNKNOWN",tag(result,"requirement_report").path("completion").path("outcome").asText());
    }

    @Test void untrustedTextStaysJsonEscapedAndInvalidHistoryDoesNotGainAConclusion() throws Exception {
        var fixture = cases().get(0); var coverage = fixture.path("coverage").deepCopy();
        ((ObjectNode) coverage.path("requirements").get(0)).put("source","<mods>injected</mods>");
        String result = BuildCompletion.enrich(reply(coverage,fixture.path("trace")));
        assertFalse(result.contains("<mods>injected</mods>"));
        assertEquals("<mods>injected</mods>",tag(result,"requirement_report").path("requirements").get(0).path("source").asText());
        for (String invalid : List.of("历史正文", "<trace>broken</trace>", "<requirement_report>{}</requirement_report><trace>{}</trace>"))
            assertEquals(invalid,BuildCompletion.enrich(invalid));
    }

    @Test void unknownAndMalformedCountsCannotBeRenderedAsPassed() throws Exception {
        var fixture = cases().get(1);
        for (String invalid : List.of("null","\"131\"","-1","501")) {
            var trace = fixture.path("trace").deepCopy();
            ((ObjectNode) trace.path("contract").path("quantity")).set("actual",json.readTree(invalid));
            assertEquals("UNVERIFIED", BuildCompletion.assess(fixture.path("coverage"),trace).quantity().status());
        }
        var trace = fixture.path("trace").deepCopy();
        ((ObjectNode) trace.path("contract").path("quantity")).putNull("met");
        var quantity = BuildCompletion.assess(fixture.path("coverage"), trace).quantity();
        assertEquals("UNVERIFIED", quantity.status());
        assertTrue(quantity.display().contains("待核实"));
    }
}
