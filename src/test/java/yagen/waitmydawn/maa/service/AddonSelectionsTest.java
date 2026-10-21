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

class AddonSelectionsTest {
    private boolean overlappingFacts;
    private final ObjectMapper json = new ObjectMapper();
    private final ModrinthApiClient api = mock(ModrinthApiClient.class);
    private final Map<String,String> payload = Map.of("uuid","fixture-session","prompt","更多核心附属");
    private final AssemblySnapshot assembly = new AssemblySnapshot("neoforge","1.21.1",List.of("core","addon-a","addon-b"),
            Map.of("core","C1","addon-a","A1","addon-b","B1","library","L1"),
            Map.of("core",Set.of("CORE"),"addon-a",Set.of("ADDON"),"addon-b",Set.of("ADDON"),"library",Set.of("DEPENDENCY")));
    private DependencyClosure closure() {
        return new DependencyClosure(assembly.versions(),List.of(new DependencyClosure.Requirement("addon-a","core",""),
                new DependencyClosure.Requirement("addon-a","library",""),new DependencyClosure.Requirement("addon-b","core","")),List.of());
    }
    private String draft() {
        var trace = json.createObjectNode(); trace.set("assembly",json.valueToTree(assembly));
        trace.putObject("upstream").put("browserHttp",42);
        trace.putObject("resolution").put("status","COMPLETE");
        var rows = trace.putArray("addonEvidence");
        for (String slug : List.of("addon-a","addon-b")) {
            var row = rows.addObject().put("slug",slug).put("retained",true).put("finalVersionVerified",true).put("finalEvidenceStatus","ADDON_RELATION");
            var relation = row.putArray("finalEvidence").addObject().put("core","core").put("relationSource","VERSION_RELATION"); relation.putArray("partners");
        }
        return "已构建4个文件<mods>core,addon-a,addon-b,library</mods><name>测试</name><trace>"+MessageJson.write(trace)
                +"</trace><constraints>"+new AgentContractCodec().write(BuildConstraints.empty())+"</constraints><build_report>"
                +MessageJson.write(new BuildPresentation.Report(1,4,Map.of(),List.of()))+"</build_report><requirement_report>{\"requirements\":[]}</requirement_report>";
    }
    private String prepare(AddonSelections service) { return prepare(service,assembly,closure(),draft()); }
    private String prepare(AddonSelections service, AssemblySnapshot snapshot, DependencyClosure closure, String reply) {
        var facts = new HashMap<String,JsonNode>();
        for(String slug:assembly.versions().keySet()) {
            var info=json.createObjectNode().put("slug",slug).put("title",slug).put("description",overlappingFacts ? "Adds real-world animals" : "Adds spells for core").put("downloads",100);
            info.putArray("categories").add(slug.equals("library")?"library":"magic");facts.put(slug,info);
        }
        when(api.getProjectsBatch(anyCollection())).thenReturn(facts);
        String offered=service.prepare(1L,2L,payload,reply,snapshot,closure,api,()->false,Map.of(),PopupPreferences.defaults(),Set.of("core","addon-a"));
        assertFalse(offered.contains("<mods>"),offered);
        return offered;
    }
    private JsonNode options(String reply) {
        var m=java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>",java.util.regex.Pattern.DOTALL).matcher(reply);
        assertTrue(m.find(),reply);return json.readTree(m.group(1));
    }
    private Map<String,String> choose(String... slugs) {
        var choice=new HashMap<>(payload);choice.put("replacementChoices",MessageJson.write(Map.of("addons:core",new ReplacementSelections.Selection(List.of(slugs),false,""))));return choice;
    }
    @Test void subsequentOverlapUsesOnlyChosenProjectsAndReusesTheCapturedFacts() {
        overlappingFacts=true;var overlaps=new OverlapSelections();var service=new AddonSelections(overlaps);
        String ticket=options(prepare(service)).path("ticket").asText();
        String next=service.admit(ticket,1L,2L,choose("addon-b"),api,()->false,AddonSelections.Confirmed::complete);
        assertFalse(next.contains("<mods>"),next);var offered=options(next);assertEquals("OVERLAP",offered.path("kind").asText());
        assertFalse(offered.path("groups").get(0).toString().contains("addon-a"));
        var decision=new HashMap<>(payload);decision.put("replacementChoices",MessageJson.write(Map.of("real-animals",new ReplacementSelections.Selection(List.of("core","addon-b"),false,""))));
        String result=overlaps.admit(offered.path("ticket").asText(),1L,2L,decision,OverlapSelections.Confirmed::complete);
        assertTrue(result.contains("<mods>addon-b,core</mods>"),result);
        assertTrue(result.contains("\"exclude\":[\"addon-a\"]"),result);
        verify(api,times(1)).getProjectsBatch(anyCollection());verifyNoMoreInteractions(api);
    }
    @Test void invalidChoicesRetainTicketAndCancellationBeforeExecutionPublishesNothing() {
        var service=new AddonSelections(new OverlapSelections());String ticket=options(prepare(service)).path("ticket").asText();
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,choose("unknown"),api,()->false,AddonSelections.Confirmed::complete));
        var malformed=new HashMap<>(payload);malformed.put("replacementChoices","{bad}");
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,malformed,api,()->false,AddonSelections.Confirmed::complete));
        String canceled=service.admit(ticket,1L,2L,choose("addon-b"),api,()->true,AddonSelections.Confirmed::complete);
        assertFalse(canceled.contains("<mods>"));assertTrue(canceled.contains("终止"));
    }

    @Test void nonDefaultCandidateCanBeChosenWithoutRepeatingAnyUpstreamCall() {
        var service=new AddonSelections(new OverlapSelections());var options=options(prepare(service));
        assertEquals("ADDONS",options.path("kind").asText());
        assertEquals("addon-a",options.path("recommendedChoices").path("addons:core").path("slugs").get(0).asText());
        assertEquals(3,options.path("recommendedFiles").asInt());assertEquals(4,options.path("candidateFiles").asInt());
        String reply=service.admit(options.path("ticket").asText(),1L,2L,choose("addon-b"),api,()->false,AddonSelections.Confirmed::complete);
        assertTrue(reply.contains("<mods>addon-b,core</mods>"),reply);
        assertTrue(reply.contains("\"exclude\":[\"addon-a\"]"),reply);
        assertTrue(reply.contains("<dependency_audit>"));
        assertTrue(reply.contains("\"upstream\":{\"browserHttp\":42}"),reply);
        assertTrue(reply.contains("selectionUpstream"),reply);
        verify(api,times(1)).getProjectsBatch(anyCollection());verifyNoMoreInteractions(api);
    }
    @Test void optionalGroupCanBeEmptyAndLibrariesDoNotAppearAsChoices() {
        var service=new AddonSelections(new OverlapSelections());var o=options(prepare(service));
        assertEquals(2,o.path("groups").get(0).path("candidates").size());
        String reply=service.admit(o.path("ticket").asText(),1L,2L,choose(),api,()->false,AddonSelections.Confirmed::complete);
        assertTrue(reply.contains("<mods>core</mods>"),reply);
    }
    @Test void protectedExplicitAddonCannotBeUnchecked() {
        var roles=new HashMap<>(assembly.origins());roles.put("addon-a",Set.of("ADDON","EXPLICIT_ADD"));
        var pinned=new AssemblySnapshot(assembly.loader(),assembly.mcVersion(),assembly.roots(),assembly.versions(),roles);
        var service=new AddonSelections(new OverlapSelections());String ticket=options(prepare(service,pinned,closure(),draft())).path("ticket").asText();
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,choose("addon-b"),api,()->false,AddonSelections.Confirmed::complete));
        assertTrue(service.admit(ticket,1L,2L,choose("addon-a"),api,()->false,AddonSelections.Confirmed::complete).contains("<mods>addon-a,core,library</mods>"));
    }
    @Test void dependentAddonsMustBeSelectedTogetherAndMayBeRejectedTogether() {
        var deps=new ArrayList<>(closure().requirements());deps.add(new DependencyClosure.Requirement("addon-b","addon-a",""));
        var service=new AddonSelections(new OverlapSelections());String ticket=options(prepare(service,assembly,new DependencyClosure(assembly.versions(),deps,List.of()),draft())).path("ticket").asText();
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,choose("addon-b"),api,()->false,AddonSelections.Confirmed::complete));
        assertTrue(service.admit(ticket,1L,2L,choose(),api,()->false,AddonSelections.Confirmed::complete).contains("<mods>core</mods>"));
    }
    @Test void accountWorkspaceCancellationAndExpirationNeverPublishDraft() {
        var elapsed=new java.util.concurrent.atomic.AtomicLong();var service=new AddonSelections(new OverlapSelections(),elapsed::get);
        String ticket=options(prepare(service)).path("ticket").asText();
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,3L,2L,choose(),api,()->false,AddonSelections.Confirmed::complete));
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,3L,choose(),api,()->false,AddonSelections.Confirmed::complete));
        var changed=new HashMap<>(choose());changed.put("currentMods","other");
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,changed,api,()->false,AddonSelections.Confirmed::complete));
        assertFalse(service.cancel(ticket,3L));elapsed.set(TimeUnit.MINUTES.toNanos(16));
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,choose(),api,()->false,AddonSelections.Confirmed::complete));
        String next=options(prepare(service)).path("ticket").asText();assertTrue(service.cancel(next,1L));
    }
    @Test void concurrentConfirmationCanSubmitOnlyOneContinuation() throws Exception {
        var service=new AddonSelections(new OverlapSelections());String ticket=options(prepare(service)).path("ticket").asText();
        var start=new CountDownLatch(1);var submitted=new java.util.concurrent.atomic.AtomicInteger();var workers=Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> action=()->{start.await();try {service.admit(ticket,1L,2L,choose("addon-b"),api,()->false,c->{submitted.incrementAndGet();return c.complete();});return true;}
                catch(IllegalArgumentException consumed){return false;}};
            var first=workers.submit(action);var second=workers.submit(action);start.countDown();
            assertNotEquals(first.get(2,TimeUnit.SECONDS),second.get(2,TimeUnit.SECONDS));assertEquals(1,submitted.get());
        } finally {start.countDown();workers.shutdownNow();}
    }

    @Test void queueRejectionRetainsTicketAndSuccessfulSubmissionConsumesItOnce() {
        var service=new AddonSelections(new OverlapSelections());String ticket=options(prepare(service)).path("ticket").asText();
        assertThrows(IllegalStateException.class,()->service.admit(ticket,1L,2L,choose(),api,()->false,c->{throw new IllegalStateException("queue full");}));
        assertTrue(service.admit(ticket,1L,2L,choose(),api,()->false,AddonSelections.Confirmed::complete).contains("<mods>core</mods>"));
        assertThrows(IllegalArgumentException.class,()->service.admit(ticket,1L,2L,choose(),api,()->false,AddonSelections.Confirmed::complete));
    }
    @Test @org.junit.jupiter.api.Timeout(10)
    void pendingAddonChoiceReleasesTheSingleWorkerForOtherAccounts() throws Exception {
        var service=new AddonSelections(new OverlapSelections());var tasks=new DelegationTasks(true,5000,1,8);
        try {
            String ticket=options(tasks.submit(null,1L,"fixture",null,()->prepare(service)).result().get(2,TimeUnit.SECONDS)).path("ticket").asText();
            assertEquals("done",tasks.submit(null,2L,"fixture",null,()->"done").result().get(2,TimeUnit.SECONDS));
            var resumed=service.admit(ticket,1L,2L,choose("addon-b"),api,()->false,c->tasks.submit(null,1L,"fixture",null,c::complete));
            assertTrue(resumed.result().get(2,TimeUnit.SECONDS).contains("<mods>addon-b,core</mods>"));
        } finally {tasks.close();}
    }
    @Test void disabledPopupUsesTheOriginalRecommendationWithoutPreparingExtraCandidates() {
        prepare(new AddonSelections(new OverlapSelections()));clearInvocations(api);
        String original=draft().replace("core,addon-a,addon-b,library","core,addon-a,library");
        var versions=new HashMap<>(assembly.versions());versions.remove("addon-b");
        var snapshot=new AssemblySnapshot(assembly.loader(),assembly.mcVersion(),List.of("core","addon-a"),versions,assembly.origins());
        String reply=new AddonSelections(new OverlapSelections()).prepare(1L,2L,payload,original,snapshot,closure(),api,()->false,Map.of(),
                new PopupPreferences(false,true,true,true),Set.of("core","addon-a"));
        assertTrue(reply.contains("<mods>core,addon-a,library</mods>"),reply);assertFalse(reply.contains("replacement_options"));
    }
}
