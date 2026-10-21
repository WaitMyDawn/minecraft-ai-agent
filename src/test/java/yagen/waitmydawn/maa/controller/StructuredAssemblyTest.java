package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import yagen.waitmydawn.maa.cache.ModrinthCacheService;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.*;
import yagen.waitmydawn.maa.service.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StructuredAssemblyTest {
    @Test void addonVersionReferencesAreBatchedAndUnresolvedConflictsRemainBlocked() {
        var json = new tools.jackson.databind.ObjectMapper();
        var first = new ChatController.CandidateMod("first-addon", "First Addon", "An addon", 1000);
        var second = new ChatController.CandidateMod("second-addon", "Second Addon", "An addon", 900);
        var v1 = json.createObjectNode();
        v1.putArray("dependencies").addObject().put("version_id", "core-v1").put("dependency_type", "required");
        var v2 = json.createObjectNode();
        var deps = v2.putArray("dependencies");
        deps.addObject().put("version_id", "core-v2").put("dependency_type", "required");
        deps.addObject().put("version_id", "unknown-conflict").put("dependency_type", "incompatible");
        when(api.getLatestCompatibleVersionBySlug(eq(first.slug), anyString(), anyString())).thenReturn(v1);
        when(api.getLatestCompatibleVersionBySlug(eq(second.slug), anyString(), anyString())).thenReturn(v2);
        when(api.getVersionsBatch(anyCollection())).thenReturn(Map.of(
                "core-v1", json.createObjectNode().put("project_id", "Core1234"),
                "core-v2", json.createObjectNode().put("project_id", "Core1234")));
        List<ChatController.CandidateMod> found = ReflectionTestUtils.invokeMethod(controller, "verifyAddonRelations",
                "core", "Core1234", List.of(first, second), "neoforge", "1.21.1", new RetrievalBudget());
        assertEquals(List.of(first), found);
        assertEquals("REQUIRED_CORE", first.addonEvidence);
        verify(api).getVersionsBatch(Set.of("core-v1", "core-v2", "unknown-conflict"));
        verify(api, never()).getVersionById(anyString());
        verify(api, never()).prefetchVersions(anyCollection());
    }

    @Test void addonReferenceBatchCancellationPropagates() {
        var json = new tools.jackson.databind.ObjectMapper();
        var addon = new ChatController.CandidateMod("extra", "Extra", "An addon", 1000);
        var version = json.createObjectNode();
        version.putArray("dependencies").addObject().put("version_id", "core-v1").put("dependency_type", "required");
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(version);
        when(api.getVersionsBatch(anyCollection())).thenThrow(new java.util.concurrent.CancellationException());
        assertThrows(java.util.concurrent.CancellationException.class, () -> ReflectionTestUtils.invokeMethod(controller,
                "verifyAddonRelations", "core", "Core1234", List.of(addon), "neoforge", "1.21.1", new RetrievalBudget()));
    }

    @Test void applicabilityBatchRespectsTheExistingReferenceBudgetAndKeepsMissingFactsUnverified() {
        var json = new tools.jackson.databind.ObjectMapper();
        var version = json.createObjectNode().put("id", "addon-version").put("project_id", "Addon123");
        var deps = version.putArray("dependencies");
        for (int i = 0; i < 30; i++) deps.addObject().put("version_id", "ref-%02d".formatted(i)).put("dependency_type", "required");
        var project = new PackAddonPolicy.Project("Addon123", "extra", 1000L, Set.of("food"), "Extra", "", "An addon for cooking-core");
        when(api.getVersionsBatch(anyCollection())).thenReturn(Map.of());
        Map<String, AddonApplicability.Check> checks = ReflectionTestUtils.invokeMethod(controller, "auditAddonApplicability",
                Set.of("extra"), Set.of("extra"), Map.of(), Map.of("extra", version),
                new LinkedHashMap<>(Map.of("extra", project)), new RetrievalBudget(), "neoforge", "1.21.1");
        assertEquals("DEPENDENCIES_UNVERIFIED", checks.get("extra").status());
        verify(api).getVersionsBatch(argThat(ids -> ids.size() == 24 && ids.contains("ref-00") && !ids.contains("ref-24")));
        verify(api, never()).getVersionById(anyString());
        verify(api, never()).prefetchVersions(anyCollection());
    }

    @Test void applicabilityRemovalAlsoPrunesAnOptionalCompatibilityRootThatWouldReintroduceIt() {
        var json = new tools.jackson.databind.ObjectMapper();
        prepareAddonPool(json.writeValueAsString(new ReviewResponse(1, List.of("create-good", "create-vetoed"), List.of(), false)));
        var metadata = new HashMap<String, tools.jackson.databind.JsonNode>();
        var versions = new HashMap<String, tools.jackson.databind.JsonNode>();
        var dependencies = Map.of("create-good", List.of("create", "extra-content"), "create-vetoed", List.of("create", "create-good"));
        for (String slug : List.of("create", "create-good", "create-vetoed", "extra-content")) {
            String description = slug.equals("create-good") ? "An addon for create" : slug.equals("create-vetoed")
                    ? "Compatibility for create-good" : "Adds ordinary content";
            var info = json.createObjectNode().put("id", "p-" + slug).put("slug", slug).put("title", slug).put("description", description);
            info.putArray("categories").add("technology"); metadata.put(slug, info);
            var version = json.createObjectNode().put("id", "v-" + slug).put("project_id", "p-" + slug);
            var required = version.putArray("dependencies");
            dependencies.getOrDefault(slug, List.of()).forEach(dep -> required.addObject().put("project_id", "p-" + dep).put("dependency_type", "required"));
            versions.put(slug, version);
        }
        when(api.getProjectInfo(anyString())).thenAnswer(call -> metadata.get(call.getArgument(0)));
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var found = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String ref : (Collection<String>) call.getArgument(0)) {
                String slug = ref.startsWith("p-") ? ref.substring(2) : ref;
                if (metadata.containsKey(slug)) found.put(ref, metadata.get(slug));
            }
            return found;
        });
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenAnswer(call -> versions.get(call.getArgument(0)));
        java.util.function.Function<Set<String>, ResolutionResult> resolve = roots -> {
            var graph = new DependencyGraph(); var pending = new ArrayDeque<>(roots);
            while (!pending.isEmpty()) {
                String slug = pending.removeFirst(); if (!graph.allSlugs.add(slug)) continue;
                graph.selectedVersions.put(slug, versions.get(slug)); graph.projectAliases.put("p-" + slug, slug);
                for (String dep : dependencies.getOrDefault(slug, List.of())) { graph.addEdge(slug, dep); pending.add(dep); }
            }
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        };
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> resolve.apply(call.getArgument(0)));
        when(engine.resolveUsingFacts(anySet(), anyString(), anyString(), any(), anyMap())).thenAnswer(call -> resolve.apply(call.getArgument(0)));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create</mods>"), result);
        assertFalse(result.contains("ADMISSION_BLOCKED"), result);
        assertTrue(result.contains("OPTIONAL_DEPENDENCY_CONFLICT"), result);
    }

    @Test void finalQuantityTrialHonorsVetoesAndUsesRetainedVersionFacts() {
        var json=new tools.jackson.databind.ObjectMapper(); var metadata=new LinkedHashMap<String,PackAddonPolicy.Project>();
        var versions=new LinkedHashMap<String,tools.jackson.databind.JsonNode>();var generationFacts=new LinkedHashMap<String,String>();
        for(String slug : List.of("core","allowed","vetoed")) {
            metadata.put(slug,new PackAddonPolicy.Project("id-"+slug,slug,1000L,Set.of("utility"),slug,"","Content"));
            var v=json.createObjectNode().put("id","version-"+slug).put("project_id","id-"+slug);v.putArray("dependencies");versions.put(slug,v);generationFacts.put(slug,"Content");
        }
        var graph=new DependencyGraph();graph.allSlugs.add("core");graph.selectedVersions.put("core",versions.get("core"));
        var baseline=new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,Set.of("core"),List.of(),List.of(),500,false);
        var plan=intent(BuildIntent.Mode.NEW,List.of("core"),List.of(),new BuildIntent.CountTarget(2,BuildIntent.CountScope.ROOT_MODS,BuildIntent.Precision.EXACT));
        when(engine.resolveUsingFacts(anySet(),anyString(),anyString(),any(),anyMap())).thenAnswer(call -> {
            Set<String> roots=call.getArgument(0);assertEquals(Set.of("core","allowed"),roots);
            Map<String,tools.jackson.databind.JsonNode> held=call.getArgument(4);assertEquals("version-core",held.get("core").path("id").asText());
            var trial=new DependencyGraph();trial.allSlugs.addAll(roots);roots.forEach(s -> trial.selectedVersions.put(s,versions.get(s)));
            return new ResolutionResult(trial,ResolutionResult.Status.COMPLETE,roots,List.of(),List.of(),500,false);
        });
        FinalQuantityCoordinator.Result result=ReflectionTestUtils.invokeMethod(controller,"coordinateFinalQuantities",plan,baseline,Set.of("core"),Set.of("core"),Set.of(),Set.of("vetoed"),
                Set.of(),new PackSessionState(),Map.of(),metadata,Map.of(),List.of(),Map.of(),Map.of(),Map.of(),List.of("vetoed","allowed"),Map.of(),generationFacts,Set.of(),"",Set.of(),
                Map.of(),List.of(),Map.of(),new RetrievalBudget(),KnowledgeRule.SourceType.ADMIN,"fixture-session",false,0);
        assertEquals(Set.of("core","allowed"),result.roots());assertEquals("WITHIN_POLICY",result.report().status());
        verify(engine,times(1)).resolveUsingFacts(anySet(),anyString(),anyString(),any(),anyMap());verifyNoInteractions(api);
    }
    @Test void inactiveRelationStageCannotExpandThePermittedSubjectScope() {
        var json=new tools.jackson.databind.ObjectMapper();var graph=new DependencyGraph();graph.allSlugs.add("core");
        graph.selectedVersions.put("core",json.createObjectNode().put("id","V1").put("project_id","Core1234"));
        var baseline=new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,Set.of("core"),List.of(),List.of(),500,false);
        var plan=intent(BuildIntent.Mode.NEW,List.of("core"),List.of(),new BuildIntent.CountTarget(1,BuildIntent.CountScope.ROOT_MODS,BuildIntent.Precision.EXACT));
        Object result=ReflectionTestUtils.invokeMethod(controller,"completePackRelations",new RequirementExecution(List.of()),plan,baseline,Set.of("core"),
                new LinkedHashMap<String,ChatController.CandidateMod>(),new LinkedHashMap<String,PackAddonPolicy.Project>(),Set.of(),null,
                new RetrievalBudget(),new CandidateProvenance(),KnowledgeRule.SourceType.ADMIN,"fixture-session");
        RequirementExecution.Report report=ReflectionTestUtils.invokeMethod(result,"report");
        assertTrue(report.evaluatedVersions().isEmpty());assertEquals("NOT_REQUESTED",report.status());
        verifyNoInteractions(api,engine);
    }
    @Test void relationStageDoesNotLegitimizeContentImplicitlyIntroducedByAnEarlierAddon() {
        var json=new tools.jackson.databind.ObjectMapper();var graph=new DependencyGraph();
        var metadata=new LinkedHashMap<String,PackAddonPolicy.Project>();
        for(String slug : List.of("cooking-core","addon","introduced-content")) {
            graph.allSlugs.add(slug);var version=json.createObjectNode().put("id","version-"+slug).put("project_id","id-"+slug);
            version.putArray("dependencies");graph.selectedVersions.put(slug,version);
            metadata.put(slug,new PackAddonPolicy.Project("id-"+slug,slug,1L,Set.of("food"),slug,"",slug.equals("addon") ? "An addon for cooking-core" : "Content"));
        }
        ((tools.jackson.databind.node.ObjectNode)graph.selectedVersions.get("addon")).putArray("dependencies").addObject()
                .put("project_id","id-introduced-content").put("dependency_type","required");
        graph.addEdge("addon","introduced-content");
        var requirement=new BuildRequirement("r-0000000000000001","扩展包内联动",BuildRequirement.Kind.PACK_INTEGRATIONS,List.of(),List.of(),List.of("cooking-core"));
        var roots=Set.of("cooking-core","addon");var baseline=new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,roots,List.of(),List.of(),500,false);
        Object result=ReflectionTestUtils.invokeMethod(controller,"completePackRelations",new RequirementExecution(List.of(requirement)),
                intent(BuildIntent.Mode.NEW,List.of("cooking-core","addon"),List.of(),new BuildIntent.CountTarget(2,BuildIntent.CountScope.ROOT_MODS,BuildIntent.Precision.EXACT)),baseline,roots,
                new LinkedHashMap<String,ChatController.CandidateMod>(),metadata,Set.of(),null,new RetrievalBudget(),new CandidateProvenance(),KnowledgeRule.SourceType.ADMIN,"fixture-session");
        RequirementExecution.Report report=ReflectionTestUtils.invokeMethod(result,"report");
        assertEquals("SUBJECT_ADMISSION_REJECTED",report.status());
        assertFalse(((String)ReflectionTestUtils.invokeMethod(result,"pause")).isEmpty());
        verifyNoInteractions(engine);verify(api,never()).search(anyString(),anyInt(),anyString());
    }
    @Test void nonDedicatedAddonReceivesFinalEvidenceAndTheSameSelectionCard() {
        var json=new tools.jackson.databind.ObjectMapper();
        var core=json.createObjectNode().put("slug","cooking-core").put("id","Core1234").put("title","Cooking Core").put("description","Adds cooking");
        core.putArray("categories").add("food");
        var addon=json.createObjectNode().put("slug","cooking-extra").put("id","Extra123").put("title","Cooking Extra").put("description","An addon for cooking-core");
        addon.putArray("categories").add("food");
        var infos=Map.of("cooking-core",core,"Core1234",core,"cooking-extra",addon,"Extra123",addon);
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts=new LinkedHashMap<String,tools.jackson.databind.JsonNode>();
            for(String ref : (Collection<String>)call.getArgument(0))if(infos.containsKey(ref))facts.put(ref,infos.get(ref));return facts;
        });
        var graph=new DependencyGraph();graph.allSlugs.addAll(Set.of("cooking-core","cooking-extra"));graph.addEdge("cooking-extra","cooking-core");
        var coreVersion=json.createObjectNode().put("id","Vcore001").put("project_id","Core1234");coreVersion.putArray("dependencies");
        var addonVersion=json.createObjectNode().put("id","Vextra01").put("project_id","Extra123");
        addonVersion.putArray("dependencies").addObject().put("project_id","Core1234").put("dependency_type","required");
        graph.selectedVersions.put("cooking-core",coreVersion);graph.selectedVersions.put("cooking-extra",addonVersion);
        when(api.getLatestCompatibleVersionBySlug(anyString(),anyString(),anyString())).thenAnswer(call -> graph.selectedVersions.get(call.getArgument(0)));
        when(engine.resolveCachedResult(anySet(),anyString(),anyString(),any())).thenReturn(new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,
                graph.allSlugs,List.of(),List.of(),500,false));
        var state=new PackSessionState();
        var plan=intent(BuildIntent.Mode.NEW,List.of("cooking-core","cooking-extra"),List.of(),new BuildIntent.CountTarget(2,BuildIntent.CountScope.ROOT_MODS,BuildIntent.Precision.EXACT));
        String reply=ReflectionTestUtils.invokeMethod(controller,"processAndAssembleBlueprint",new AgentContractCodec().write(plan),"","只加入点名模组",
                "fixture-key","fixture-session",state,Set.<String>of(),KnowledgeRule.SourceType.ADMIN,new AiAgentService.AgentCallResult("",1,1,10,10),"1.21.1","neoforge",null);
        assertTrue(reply.contains("<mods>cooking-core,cooking-extra</mods>"),reply);
        assertTrue(reply.contains("UNIFIED_ADMISSION"),reply);assertTrue(reply.contains("ADDON_RELATION"),reply);
        assertTrue(state.getFinishedAssembly().origins().get("cooking-extra").contains("ADDON"));
        var selections=new AddonSelections(new OverlapSelections());
        String offered=selections.prepare(1L,2L,Map.of("uuid","fixture-session","prompt","只加入点名模组"),reply,state.getFinishedAssembly(),
                state.getFinishedClosure(),api,()->false,Map.of(),PopupPreferences.defaults(),state.getRecommendedAddonRoots(),state.getSelectionCoverage());
        assertTrue(offered.contains("\"kind\":\"ADDONS\""),offered);assertTrue(offered.contains("addons:cooking-core"),offered);
        clearInvocations(api);
        Map<String,AddonApplicability.Check> checks=ReflectionTestUtils.invokeMethod(controller,"auditAddonApplicability",Set.of("cooking-core"),graph.allSlugs,
                Map.of(),graph.selectedVersions,new LinkedHashMap<String,PackAddonPolicy.Project>(),new RetrievalBudget(10),"neoforge","1.21.1");
        assertTrue(checks.containsKey("cooking-extra"));
        verify(api,never()).getLatestCompatibleVersionBySlug(anyString(),anyString(),anyString());
    }
    @Test void evidenceOnlyReviewUsesHeldFactsAndCannotAddOrRemoveProjects() {
        var json = new tools.jackson.databind.ObjectMapper();
        String id = "r-0000000000000099";
        var criterion = new RequirementCriterion("物流", BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of());
        var requirement = new BuildRequirement(id,"物流",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of(),List.of(),
                SelectionPreferences.DEFAULT,List.of(criterion));
        var graph = new DependencyGraph();graph.allSlugs.add("pipes");graph.selectedVersions.put("pipes",json.createObjectNode().put("id","version-pipes"));
        var facts=Map.of("pipes","Moves items through pipes");
        var report=RequirementCoverage.assess(List.of(requirement),List.of(),graph,Set.of("pipes"),Map.of(),Map.of(),"NONE",List.of());
        var review=new RequirementReview(id,List.of(),"",List.of(new CriterionReview("物流",
                List.of(new RequirementReview.Contribution("pipes","Moves items through pipes","物流")),"")));
        // 越界选择不获得执行权限；独立可核对的已有项目引用仍可提供用途依据。
        String response=json.writeValueAsString(new ReviewResponse(1,List.of("foreign"),List.of(),true,List.of(review)));
        when(agents.criticPools(anyString(),anyString(),anySet(),anyInt())).thenReturn(new AiAgentService.AgentCallResult(response,1,1,10,10));
        Object followup=ReflectionTestUtils.invokeMethod(controller,"reviewMissingEvidence",List.of(requirement),report,facts,
                Set.of("previously-approved"),Set.of("pipes"),Set.of(),"proof-fixture","fixture-key");
        List<RequirementReview> updates=ReflectionTestUtils.invokeMethod(followup,"updates");
        Set<String> eligible=ReflectionTestUtils.invokeMethod(followup,"eligible");
        assertEquals(Set.of("previously-approved"),eligible);
        assertEquals("pipes",updates.getFirst().criteria().getFirst().evidence().getFirst().slug());
        verify(agents,times(1)).criticPools(argThat(s -> s.contains("approved=[]") && s.contains("Moves items through pipes")),
                eq("fixture-key"),eq(Set.of()),eq(0));
        verifyNoInteractions(api,engine);
    }
    @Test void remainingRankingReservesExplicitOpportunitiesThenUsesDiversityWithoutNetworkOrAgents() {
        var core=new ChatController.CandidateMod("core","Core","",1);core.category="magic";core.hitTerms.add("spells");
        var required=new ChatController.CandidateMod("required","Required","",1);required.category="magic";required.hitTerms.add("spells");required.finalScore=1;
        var same=new ChatController.CandidateMod("same","Same","",1000);same.category="magic";same.hitTerms.add("spells");same.finalScore=100;
        var fresh=new ChatController.CandidateMod("fresh","Fresh","",100);fresh.category="adventure";fresh.hitTerms.add("ruins");fresh.finalScore=95;
        var state=new PackSessionState();
        List<String> order=ReflectionTestUtils.invokeMethod(controller,"rankRemaining",List.of("same","required","fresh"),List.of(core,required,same,fresh),
                Set.of("core"),Map.of("required",Set.of("r-0000000000000001")),Map.of(),state,false,List.of());
        assertEquals(List.of("required","fresh","same"),order);
        assertEquals(List.of("required"),state.getRemainingRanking().get("approved").priorities());
        verifyNoInteractions(api,agents,engine);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 1, 2})
    void explicitAddonRangeCanExceedTheFivePerCoreDefaultWithoutExceedingItsUpperBound(int selectionMode) {
        boolean popup = selectionMode > 0;
        var json = new tools.jackson.databind.ObjectMapper();
        var slugs = java.util.stream.IntStream.range(0,12).mapToObj(i -> "create-extra-"+i).toList();
        prepareAddonPool(json.writeValueAsString(new ReviewResponse(1,slugs,List.of(),false)));
        var hits = json.createObjectNode(); var array = hits.putArray("hits");
        slugs.forEach(slug -> array.addObject().put("slug",slug).put("title",slug).put("description","An addon for create").put("downloads",1000));
        when(api.search(anyString(),anyInt(),anyString())).thenReturn(hits);
        when(api.getProjectInfo(anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            var info = json.createObjectNode().put("id","p-"+slug).put("slug",slug).put("title",slug).put("description","An addon for create");
            info.putArray("categories").add("technology"); return info;
        });
        when(api.getLatestCompatibleVersionBySlug(anyString(),anyString(),anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            var v = json.createObjectNode().put("id","v-"+slug).put("project_id","p-"+slug);
            var deps = v.putArray("dependencies");
            if (!slug.equals("create")) deps.addObject().put("project_id","p-create").put("dependency_type","required");
            return v;
        });
        when(engine.resolveCachedResult(anySet(),anyString(),anyString(),any())).thenAnswer(call -> {
            Set<String> roots = call.getArgument(0); var graph = new DependencyGraph();graph.allSlugs.addAll(roots);
            for (String slug : roots) graph.selectedVersions.put(slug,api.getLatestCompatibleVersionBySlug(slug,"neoforge","1.21.1"));
            return new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,roots,List.of(),List.of(),500,false);
        });
        var base = addonIntent();
        var r = new BuildRequirement("r-0000000000000001","补充create附属6-8个",BuildRequirement.Kind.ADDONS,List.of(),List.of(),List.of("create"));
        var plan = new BuildIntent(1,base.mode(),base.reply(),base.name(),base.environment(),base.count(),base.mustHave(),base.add(),base.remove(),base.exclude(),
                base.expandAddons(),base.replacementPolicy(),base.searchIntents(),base.excludedCategories(),base.maxDownloads(),base.constraintChanges(),List.of(r));
        var state = new PackSessionState(); state.setAddonSelectionEnabled(popup);
        when(engine.resolveUsingFacts(anySet(),anyString(),anyString(),any(),anyMap())).thenAnswer(call -> {
            Set<String> roots=call.getArgument(0); var graph=new DependencyGraph();graph.allSlugs.addAll(roots);
            roots.forEach(slug -> graph.selectedVersions.put(slug,api.getLatestCompatibleVersionBySlug(slug,"1.21.1","neoforge")));
            if (selectionMode == 2) graph.selectedVersions.put("create",json.createObjectNode().put("id","changed-version").put("project_id","p-create"));
            return new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,roots,List.of(),List.of(),500,false);
        });
        String output = ReflectionTestUtils.invokeMethod(controller,"processAndAssembleBlueprint",new AgentContractCodec().write(plan),"create","需求",
                "fixture-key","fixture-session",state,Set.<String>of(),KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("",1,1,10,10),"1.21.1","neoforge",null);
        if (popup) {
            var selection=new AddonSelections(new OverlapSelections());var payload=Map.of("uuid","fixture-session","prompt","需求");
            output += "<constraints>"+new AgentContractCodec().write(BuildConstraints.empty())+"</constraints>";
            String offered=selection.prepare(1L,2L,payload,output,state.getFinishedAssembly(),state.getFinishedClosure(),api,()->false,Map.of(),
                    PopupPreferences.defaults(),state.getRecommendedAddonRoots(),state.getSelectionCoverage());
            var matcher=java.util.regex.Pattern.compile("<replacement_options>(.*?)</replacement_options>",java.util.regex.Pattern.DOTALL).matcher(offered);
            assertTrue(matcher.find(),offered);var options=json.readTree(matcher.group(1));
            assertEquals(selectionMode == 1 ? 12 : 8,options.path("groups").get(0).path("candidates").size(),offered);
            var chosen=new HashMap<>(payload);chosen.put("replacementChoices",json.writeValueAsString(options.path("recommendedChoices")));
            output=selection.admit(options.path("ticket").asText(),1L,2L,chosen,api,()->false,AddonSelections.Confirmed::complete);
        }
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>",java.util.regex.Pattern.DOTALL).matcher(output);
        assertTrue(match.find(),output);
        assertEquals(8,json.readTree(match.group(1)).path("requirements").get(0).path("quantity").path("actual").asInt());
        assertTrue(output.contains("<mods>"),output);
        verify(agents,times(1)).criticPools(anyString(),anyString(),anySet(),anyInt());
    }

    @Test void duplicateApprovalsDoNotTriggerAnotherModelCallOrRestoreVetoedCandidates() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["create-good","create-vetoed","create-good","create-vetoed"],
                 "rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"unrelated"}],
                 "allowBackfill":false,"requirements":[]}
                """);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertFalse(result.contains("审核员（Critic）的输出未通过校验"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void tailStagesSeparateValidationPresentationAndSerializationWithoutInventingARepair() {
        var context = new yagen.waitmydawn.maa.runtime.DelegationContext(1000);
        var plan = intent(BuildIntent.Mode.NEW, List.of("ordinary-core"), List.of(),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT));
        var output = new java.util.concurrent.atomic.AtomicReference<String>();
        yagen.waitmydawn.maa.runtime.DelegationContext.runWith(context, () -> output.set(assemble(plan, "")));
        assertTrue(output.get().contains("<mods>ordinary-core</mods>"), output.get());
        var titles = context.progress().snapshot().stream().map(yagen.waitmydawn.maa.runtime.BuildProgress.Entry::title).toList();
        var tail = List.of("解析依赖与协调版本", "核对需求覆盖与数量", "协调主体配额与剩余数量", "核对联动后的最终数量", "复核最终附属与生成准入",
                "整理类别与依赖问题", "核对逐项目标与用途证据", "汇总构建来源与诊断", "序列化构建结果");
        assertEquals(tail, titles.subList(titles.indexOf(tail.getFirst()), titles.size()));
        context.progress().finish();
        assertTrue(context.progress().snapshot().stream().allMatch(entry -> entry.endedAt() != null));
        verifyNoInteractions(agents);
        verify(engine,never()).resolveUsingFacts(anySet(),anyString(),anyString(),any(),anyMap());
    }

    @Test void partialAdmissionFactsStillBlockAnUnrequestedExtremePresetAndRemainReusable() {
        var json = new tools.jackson.databind.ObjectMapper();
        var known = json.createObjectNode().put("id", "Preset01").put("slug", "world-preset")
                .put("description", "Classic Skyblock with infinite void generation");
        when(api.getProjectsBatch(anyCollection())).thenThrow(new ModrinthApiClient.PartialProjectBatchException(Map.of("world-preset", known)));
        Map<String, String> facts = new LinkedHashMap<>();
        Map<String, ?> blocked = ReflectionTestUtils.invokeMethod(controller, "generationAdmission",
                List.of("world-preset", "unknown"), "冒险整合包", facts);
        assertTrue(blocked.containsKey("world-preset"));
        assertFalse(blocked.containsKey("unknown"), "缺失资料只能视为未核实，不能编造准入依据");
        assertEquals(facts.get("world-preset"), facts.get("Preset01"));
        ReflectionTestUtils.invokeMethod(controller, "generationAdmission", List.of("world-preset", "Preset01"), "冒险整合包", facts);
        verify(api, times(1)).getProjectsBatch(anyCollection());
    }

    @Test void admissionCancellationCannotBeDowngradedIntoMissingDescriptions() {
        when(api.getProjectsBatch(anyCollection())).thenThrow(new java.util.concurrent.CancellationException());
        assertThrows(java.util.concurrent.CancellationException.class, () -> ReflectionTestUtils.invokeMethod(controller,
                "generationAdmission", List.of("ordinary-core"), "冒险整合包", new LinkedHashMap<String, String>()));
    }

    @Test void reportAlignmentRepairIsTargetedAndDoesNotReauthorizeSelection() {
        checkReportAlignmentRepair(false, false);
    }

    @Test void reportRepairChangingPermissionsKeepsOriginalVetoAndBackfillRestriction() {
        checkReportAlignmentRepair(true, false);
    }

    @Test void repeatedAlignmentFailureStillBuildsFromLegalSelectionWithoutAThirdRepair() {
        checkReportAlignmentRepair(false, true);
    }

    private void checkReportAlignmentRepair(boolean changePermissions, boolean stillInvalid) {
        var json = new tools.jackson.databind.ObjectMapper();
        var criteria = List.of(new RequirementCriterion("物流", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()),
                new RequirementCriterion("仓储", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of()));
        var requirement = new BuildRequirement("r-0000000000000001", "物流和仓储", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(), SelectionPreferences.DEFAULT, criteria);
        var base = addonIntent();
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(),
                base.add(), base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(),
                base.excludedCategories(), base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var selection = new ReviewResponse(1, List.of("create-good", "create-vetoed"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "不符合主题")), false,
                List.of(new RequirementReview(requirement.id(), List.of(), "错误的父项缺口",
                        List.of(new CriterionReview("物流", List.of(), "缺少依据")))));
        String invalid = json.writeValueAsString(selection);
        var corrected = (tools.jackson.databind.node.ObjectNode) json.readTree(invalid);
        corrected.set("requirements", json.valueToTree(List.of(new RequirementReview(requirement.id(), List.of(), "",
                criteria.stream().map(c -> new CriterionReview(c.source(), List.of(), "缺少可核实依据")).toList()))));
        if (changePermissions) { corrected.putArray("rejected"); corrected.put("allowBackfill", true); }
        prepareAddonPool(invalid);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(invalid, 1, 1, 10, 10),
                new AiAgentService.AgentCallResult(stillInvalid ? invalid : corrected.toString(), 1, 1, 10, 10));
        String result = assemble(plan, "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        assertTrue(result.contains("\"criticCoverageDegraded\":" + (changePermissions || stillInvalid)), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
        verify(agents).criticPools(argThat((String context) -> context.contains("【用途报告定向修正】")
                && context.contains("MISSING_CRITERION") && context.contains("PARENT_GAP")
                && context.contains("【审核子目标结构骨架】")), anyString(), anySet(), anyInt());
    }
    @Test void confirmedProjectFactsReachFinalCoverageEvenWhenNamedOnlyBuildSkipsCritic() {
        var json = new tools.jackson.databind.ObjectMapper();
        var base = intent(BuildIntent.Mode.NEW, List.of("terrain-core"), List.of(),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT));
        var requirement = new BuildRequirement("r-0000000000000001", "丰富下界生态", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of());
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(), base.add(),
                base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(), base.excludedCategories(),
                base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var version = json.createObjectNode().put("id", "VCore").put("project_id", "PCore"); version.putArray("dependencies");
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(version);
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            var graph = new DependencyGraph(); graph.allSlugs.add("terrain-core"); graph.selectedVersions.put("terrain-core", version);
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("terrain-core"), List.of(), List.of(), 500, false);
        });
        var state = new PackSessionState(); state.setGenerationFacts(Map.of("terrain-core", "Overhauls Nether terrain with many new biomes"));
        String result = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint", new AgentContractCodec().write(plan), "",
                "仅添加terrain-core，不额外推荐", "fixture-key", "fixture-session", state, Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
        var match = java.util.regex.Pattern.compile("<requirement_report>(.*?)</requirement_report>", java.util.regex.Pattern.DOTALL).matcher(result);
        assertTrue(match.find(), result);
        var row = json.readTree(match.group(1)).path("requirements").get(0);
        assertEquals("SUPPORTING_EVIDENCE", row.path("status").asText());
        assertEquals("PROJECT_GENERATION", row.path("mods").get(0).path("evidenceType").asText());
        verifyNoInteractions(agents);
    }
    @Test void dimensionCoreRemainsInTheCompatiblePoolAheadOfPopularLocalStructures() {
        var json = new tools.jackson.databind.ObjectMapper();
        var hits = json.createObjectNode(); var values = hits.putArray("hits");
        for (int i = 0; i < 20; i++) values.addObject().put("slug", "nether-structure-" + i).put("title", "Nether structures")
                .put("description", "Adds Nether structures and fortresses").put("downloads", 10000000);
        values.addObject().put("slug", "terrain-core").put("title", "Dimension Expansion")
                .put("description", "Nether expansion with custom terrain and new biomes").put("downloads", 100);
        when(api.searchPage(anyString(), anyInt(), anyInt(), nullable(String.class), anyString())).thenReturn(hits);
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(json.createObjectNode().put("id", "compatible-version"));
        var requirement = new BuildRequirement("r-0000000000000001", "丰富下界生态", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(new BuildIntent.SearchIntent("adventure", 100, List.of("nether structures"))));
        var base = intent(BuildIntent.Mode.NEW, List.of(), List.of(), new BuildIntent.CountTarget(20, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE));
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(), base.add(),
                base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(), base.excludedCategories(),
                base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var hints = new LinkedHashMap<String, Set<String>>(); var budget = new RetrievalBudget();
        List<?> pool = ReflectionTestUtils.invokeMethod(controller, "buildRequirementPool", List.of(requirement), Set.of(), "neoforge", "1.21.1",
                base.maxDownloads(), new BuildExecutionPlan(plan, Set.of(), Set.of()), budget, "fixture-session", hints);
        assertNotNull(pool);
        assertTrue(hints.containsKey("terrain-core"), hints.toString());
        assertTrue(hints.get("terrain-core").contains(requirement.id() + "::generation:nether"));
        var searches = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(api, atLeastOnce()).searchPage(searches.capture(), anyInt(), anyInt(), nullable(String.class), anyString());
        assertEquals("nether terrain overhaul", searches.getAllValues().getFirst());
        assertTrue(budget.snapshot().get("searchQueries") <= RetrievalBudget.MAX_SEARCHES);
        assertTrue(pool.size() <= 8);
    }
    @Test void namedOnlyCountTargetCannotTriggerFillersButStillResolvesRequiredDependencies() {
        var base = intent(BuildIntent.Mode.NEW, List.of("core-mod"), List.of(),
                new BuildIntent.CountTarget(14, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE, 8, 20));
        var p = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(), base.add(),
                base.remove(), base.exclude(), List.of(), base.replacementPolicy(), List.of(new BuildIntent.SearchIntent("worldgen", 100, List.of("biomes"))),
                base.excludedCategories(), base.maxDownloads());
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(
                new tools.jackson.databind.ObjectMapper().createObjectNode().put("id", "core-version").put("project_id", "core-project"));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            var graph = new DependencyGraph(); graph.allSlugs.addAll(Set.of("core-mod", "library")); graph.addEdge("core-mod", "library");
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("core-mod"), List.of(), List.of(), 500, false);
        });
        String result = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint", new AgentContractCodec().write(p), "",
                "包含core-mod，不额外推荐", "fixture-key", "fixture-session", new PackSessionState(), Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
        assertTrue(result.contains("<mods>core-mod,library</mods>"), result);
        assertTrue(result.contains("数量要求尚未满足"), result);
        verify(api, never()).search(anyString(), anyInt(), anyString());
        verifyNoInteractions(agents);
    }
    private ChatController controller;
    private DependencyEngine engine;
    private AiAgentService agents;
    private ModrinthApiClient api;

    @BeforeEach void setup() {
        engine = mock(DependencyEngine.class);
        agents = mock(AiAgentService.class);
        api = mock(ModrinthApiClient.class);
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) facts.put(slug,
                    new tools.jackson.databind.ObjectMapper().createObjectNode().put("slug", slug).put("description", "Adds ordinary content"));
            return facts;
        });
        var expansion = mock(ExpansionTracker.class);
        when(expansion.factor(anyString(), anyString())).thenReturn(2.0);
        controller = new ChatController(agents, mock(ModrinthCacheService.class), null, engine, api,
                null, null, null, null, null, null, null, expansion, null, mock(LoaderVersionService.class));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0));
            var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
    }

    private BuildIntent intent(BuildIntent.Mode mode, List<String> add, List<String> remove, BuildIntent.CountTarget count) {
        return new BuildIntent(1, mode, "计划说明", "", new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false),
                count, add, add, remove, List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
    }

    private String assemble(BuildIntent intent, String current) {
        return ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint",
                new AgentContractCodec().write(intent), current, "需求", "fixture-key", "fixture-session", new PackSessionState(),
                Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
    }

    @Test void inferredExtremeRootIsExcludedButExplicitSkyblockRequestCanKeepIt() {
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectsBatch(anyCollection())).thenReturn(Map.of("world-preset",
                json.createObjectNode().put("slug", "world-preset").put("description", "Classic Skyblock with infinite void generation")));
        var plan = intent(BuildIntent.Mode.NEW, List.of("world-preset"), List.of(),
                new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT));
        String result = assemble(plan, "");
        assertFalse(result.contains("<mods>world-preset</mods>"), result);
        var state = new PackSessionState();
        String explicit = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint", new AgentContractCodec().write(plan), "",
                "构建一个空岛整合包", "fixture-key", "fixture-session", state, Set.<String>of(), KnowledgeRule.SourceType.ADMIN,
                new AiAgentService.AgentCallResult("", 1, 1, 10, 10), "1.21.1", "neoforge", null);
        assertTrue(explicit.contains("<mods>world-preset</mods>"), explicit);
    }

    @Test void dependencyIntroducedExtremePresetStopsPublicationWithoutRemovingItsRequiredEdge() {
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) facts.put(slug, json.createObjectNode().put("slug", slug)
                    .put("description", slug.equals("hidden-preset") ? "Creates worlds with missing terrain" : "Adds an adventure system"));
            return facts;
        });
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            var graph = new DependencyGraph(); graph.allSlugs.addAll(Set.of("adventure-core", "hidden-preset"));
            graph.addEdge("adventure-core", "hidden-preset");
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("adventure-core"), List.of(), List.of(), 500, false);
        });
        var plan = intent(BuildIntent.Mode.NEW, List.of("adventure-core"), List.of(),
                new BuildIntent.CountTarget(2, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE));
        String result = assemble(plan, "");
        assertFalse(result.contains("<mods>"), result);
        assertTrue(result.contains("hidden-preset"), result);
        assertTrue(result.contains("本轮未修改清单"), result);
    }

    @Test void bodyOnlySubtractiveAddonCannotReachCriticOrBackfill() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"), List.of(), true)));
        var json = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new LinkedHashMap<String, tools.jackson.databind.JsonNode>();
            for (String slug : (Collection<String>) call.getArgument(0)) facts.put(slug, json.createObjectNode().put("slug", slug)
                    .put("description", "Adds content").put("body", slug.equals("create-vetoed") ? "Removes structures, mobs and bosses from a core mod" : "Adds machines"));
            return facts;
        });
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        verify(agents).criticPools(anyString(), anyString(), eq(Set.of("create-good")), anyInt());
    }

    @Test void editAddsOnlyDeltaAndDoesNotGrowToDefaultHundred() {
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of(), null), "jei,create");
        assertTrue(result.contains("<mods>create,jei,journeymap</mods>"), result);
        assertTrue(result.contains("\"target\":3"), result);
        verifyNoInteractions(agents);
        verify(api, never()).search(anyString(), anyInt(), anyString());
        verify(api).getProjectsBatch(List.of("journeymap"));
    }

    @Test void newBuildDoesNotCarryOldPackOrSilentlyCapNamedModsAtThirty() {
        List<String> named = java.util.stream.IntStream.range(0, 40).mapToObj(i -> "mod-" + i).toList();
        var count = new BuildIntent.CountTarget(40, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT);
        String result = assemble(intent(BuildIntent.Mode.NEW, named, List.of(), count), "old-pack-mod");
        assertFalse(result.contains("old-pack-mod"), result);
        assertTrue(result.contains("mod-39"), result);
        assertFalse(result.contains("严格数量尚未满足"), result);
    }

    @Test void emptyClearsExistingPackAndDoesNotMisclassifyAsFormatError() {
        var count = new BuildIntent.CountTarget(0, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.EXACT);
        String result = assemble(intent(BuildIntent.Mode.EMPTY, List.of(), List.of(), count), "create,jei");
        assertTrue(result.contains("<mods></mods>"), result);
        assertFalse(result.contains("未输出构筑标签"), result);
        verifyNoInteractions(engine, agents, api);
    }
    @Test void totalUpstreamFailureCannotPublishAnEmptyPackOrOverwriteAnEdit() {
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> new ResolutionResult(
                new DependencyGraph(), ResolutionResult.Status.INCOMPLETE, call.getArgument(0),
                List.of(new ResolutionResult.Unresolved("candidate", "", ResolutionResult.Reason.UPSTREAM_UNAVAILABLE, "fixture network failure")),
                List.of(), 500, false));
        for (var mode : List.of(BuildIntent.Mode.NEW, BuildIntent.Mode.EDIT)) {
            var count = mode == BuildIntent.Mode.NEW
                    ? new BuildIntent.CountTarget(1, BuildIntent.CountScope.ROOT_MODS, BuildIntent.Precision.EXACT) : null;
            String result = assemble(intent(mode, List.of("candidate"), List.of(), count), "old-mod");
            assertFalse(result.contains("<mods>"), result);
            assertTrue(result.contains("本轮未修改整合包清单"), result);
            assertTrue(result.contains("\"status\":\"INCOMPLETE\""), result);
            assertFalse(result.contains("<constraints>"), result);
        }
    }

    @Test void removedModCannotReturnAsDependencyAndMissingRequiredModIsReported() {
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of("create"), null), "create,jei");
        assertTrue(result.contains("<mods>jei,journeymap</mods>"), result);
        assertTrue(result.contains("\"removed\":[\"create\"]"), result);
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenReturn(
                new ResolutionResult(new DependencyGraph(), ResolutionResult.Status.INCOMPLETE,
                        Set.of("journeymap"), List.of(), List.of(), 500, false));
        assertTrue(assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of(), null), "")
                .contains("未满足必选模组：journeymap"));
    }

    private BuildIntent addonIntent() {
        var original = intent(BuildIntent.Mode.EDIT, List.of(), List.of(), null);
        return new BuildIntent(1, original.mode(), original.reply(), "", original.environment(), null,
                List.of("create"), List.of(), List.of(), List.of(), List.of("create"), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
    }

    private void prepareAddonPool(String decision) {
        var mapper = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectInfo(anyString())).thenAnswer(call -> mapper.createObjectNode()
                .put("id", "fixture-id").put("slug", (String) call.getArgument(0)));
        var version = mapper.createObjectNode().put("id", "fixture-version");
        version.putArray("dependencies").addObject().put("project_id", "fixture-id").put("dependency_type", "required");
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenReturn(version);
        when(api.search(anyString(), anyInt(), anyString())).thenReturn(mapper.readTree("""
                {"hits":[{"slug":"create-good","title":"Good addon","downloads":10000},
                         {"slug":"create-vetoed","title":"Rejected addon","downloads":100000}]}
                """));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt()))
                .thenReturn(new AiAgentService.AgentCallResult(decision, 1, 1, 10, 10));
    }

    @Test void versionTwoLocalMismatchDoesNotRemoveApprovalForOtherGoals() {
        var json = new tools.jackson.databind.ObjectMapper();
        String id = "r-0000000000000001";
        var base = addonIntent();
        var requirement = new BuildRequirement(id, "扩充机械动力附属", BuildRequirement.Kind.ADDONS,
                List.of("create"), List.of(), List.of("create"));
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(),
                base.add(), base.remove(), base.exclude(), base.expandAddons(), base.replacementPolicy(), base.searchIntents(),
                base.excludedCategories(), base.maxDownloads(), base.constraintChanges(), List.of(requirement));
        var local = new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "局部落选",
                new ReviewDecision.Authority(ReviewDecision.Scope.GOAL, id, "Rejected addon", "", ""));
        prepareAddonPool(json.writeValueAsString(new ReviewResponse(2, List.of("create-good", "create-vetoed"), List.of(local), true,
                List.of(new RequirementReview(id, List.of(), "")), new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.ALLOW, ""))));
        String result = assemble(plan, "create");
        String mods = result.split("<mods>")[1].split("</mods>")[0];
        assertTrue(mods.contains("create-good"), result); assertTrue(mods.contains("create-vetoed"), result);
        var trace = json.readTree(result.split("<trace>")[1].split("</trace>")[0]);
        assertFalse(trace.path("criticDegraded").asBoolean(true));
        assertEquals("create-vetoed", trace.path("reviewEvidenceRecovery").path("authority").path("localVetoes").path(id).get(0).asText());
        assertEquals(0, trace.path("reviewEvidenceRecovery").path("authority").path("confirmedGlobalVetoes").size());
    }

    @Test void invalidPoolExhaustionDoesNotPoisonAValidatedVersionTwoRepair() {
        var json = new tools.jackson.databind.ObjectMapper();
        String first = json.writeValueAsString(new ReviewResponse(2, List.of("create-good"), List.of(), false, List.of(),
                new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.POOL_EXHAUSTED, "")));
        String repair = json.writeValueAsString(new ReviewResponse(2, List.of("create-good", "create-vetoed"), List.of(), true, List.of(),
                new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.ALLOW, "")));
        prepareAddonPool(first);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(first, 1, 1, 10, 10).forReview(2), new AiAgentService.AgentCallResult(repair, 1, 1, 10, 10).forReview(2));
        String result = assemble(addonIntent(), "create");
        String mods = result.split("<mods>")[1].split("</mods>")[0];
        assertTrue(mods.contains("create-good"), result); assertTrue(mods.contains("create-vetoed"), result);
        assertTrue(result.contains("BACKFILL_POOL_NOT_EXHAUSTED"), result);
        var trace = json.readTree(result.split("<trace>")[1].split("</trace>")[0]);
        assertTrue(trace.path("review").path("allowBackfill").asBoolean());
        assertFalse(trace.path("reviewEvidenceRecovery").path("authority").path("backfillForbidden").asBoolean(true));
        assertEquals(2, trace.path("reviewEvidenceRecovery").path("authority").path("expectedVersion").asInt());
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void versionTwoVerifiedConflictRemainsBlockedDuringBackfill() {
        var json = new tools.jackson.databind.ObjectMapper();
        var veto = new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.CONFLICT, "声明与核心冲突",
                new ReviewDecision.Authority(ReviewDecision.Scope.PACK, "", "Incompatible with create", "", "create"));
        prepareAddonPool(json.writeValueAsString(new ReviewResponse(2, List.of("create-good"), List.of(veto), true, List.of(),
                new ReviewDecision.Backfill(ReviewDecision.BackfillBasis.ALLOW, ""))));
        when(api.search(anyString(), anyInt(), anyString())).thenReturn(json.readTree("""
                {"hits":[{"slug":"create-good","title":"Good addon","downloads":10000},
                  {"slug":"create-vetoed","title":"Rejected addon","description":"Incompatible with create","downloads":100000}]}
                """));
        String result = assemble(addonIntent(), "create");
        String mods = result.split("<mods>")[1].split("</mods>")[0];
        assertTrue(mods.contains("create-good"), result); assertFalse(mods.contains("create-vetoed"), result);
        var trace = json.readTree(result.split("<trace>")[1].split("</trace>")[0]);
        assertFalse(trace.path("criticDegraded").asBoolean(true));
        assertEquals("create-vetoed", trace.path("reviewEvidenceRecovery").path("authority").path("confirmedGlobalVetoes").get(0).asText());
    }

    @Test void explicitlyNamedBridgeWithMissingPartnerPausesBeforeDependencyAssembly() {
        var json = new tools.jackson.databind.ObjectMapper();
        var core = json.createObjectNode().put("id", "CORE").put("slug", "cooking-core").put("title", "Cooking Core");
        core.putArray("categories").add("food");
        var peer = json.createObjectNode().put("id", "PEER").put("slug", "magic-core").put("title", "Magic Core");
        peer.putArray("categories").add("magic");
        var bridge = json.createObjectNode().put("id", "BRIDGE").put("slug", "bridge")
                .put("description", "Integration between cooking-core and magic-core");
        bridge.putArray("categories").add("food");
        when(api.getProjectsBatch(anyCollection())).thenAnswer(call -> {
            var facts = new HashMap<String, tools.jackson.databind.JsonNode>();
            var all = Map.of("cooking-core", core, "bridge", bridge, "PEER", peer, "CORE", core);
            for (String key : (Collection<String>) call.getArgument(0)) if (all.containsKey(key)) facts.put(key, all.get(key));
            return facts;
        });
        var version = json.createObjectNode().put("id", "bridge-v").put("project_id", "BRIDGE");
        var deps = version.putArray("dependencies");
        deps.addObject().put("project_id", "CORE").put("dependency_type", "required");
        deps.addObject().put("project_id", "PEER").put("dependency_type", "optional");
        when(api.getLatestCompatibleVersionBySlug(eq("bridge"), anyString(), anyString())).thenReturn(version);
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("bridge"), List.of(), null), "cooking-core");
        assertTrue(result.contains("magic-core"), result);
        assertTrue(result.contains("本轮未修改清单"), result);
        assertFalse(result.contains("<mods>"), result);
        verifyNoInteractions(engine);
    }
    @Test void explicitVetoCannotBeReintroducedByBackfill() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "主题不符")), true)));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertFalse(result.contains("<mods>create,create-good,create-vetoed</mods>"), result);
    }

    @Test void contradictoryVetoAndPurposeEvidenceAreRepairedOnceBeforeAssembly() {
        String contradictory = """
                {"schemaVersion":1,"approved":[],"rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"不相关"}],
                 "allowBackfill":true,"requirements":[{"id":"r-0000000000000001","evidence":[{"slug":"create-vetoed","quote":"Adds automation","purpose":"自动化"}],"gap":""}]}
                """;
        var corrected = new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"),
                List.of(new ReviewDecision.Rejection("create-vetoed", ReviewDecision.Reason.OFF_TOPIC, "不相关")), false));
        prepareAddonPool(contradictory);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(contradictory, 1, 1, 10, 10), new AiAgentService.AgentCallResult(corrected, 1, 1, 10, 10));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void failedRepairKeepsParsedVetoAndBackfillOptOutWithoutSalvagingBrokenJson() {
        for (boolean allowBackfill : List.of(false, true)) {
            String contradictory = """
                    {"schemaVersion":1,"approved":[],"rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"不相关"}],
                     "allowBackfill":%s,"requirements":[{"id":"r-0000000000000001","evidence":[{"slug":"create-vetoed","quote":"Adds automation","purpose":"自动化"}],"gap":""}]}
                    """.formatted(allowBackfill);
            prepareAddonPool(contradictory);
            when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                    new AiAgentService.AgentCallResult(contradictory, 1, 1, 10, 10), new AiAgentService.AgentCallResult("{broken", 1, 1, 10, 10));
            String result = assemble(addonIntent(), "create");
            String mods = result.split("<mods>")[1].split("</mods>")[0];
            assertFalse(mods.contains("create-vetoed"), result);
            if (!allowBackfill) assertEquals("create", mods);
            assertTrue(result.contains("\"criticDegraded\":false"), result);
            assertTrue(result.contains("\"criticCoverageDegraded\":true"), result);
            assertTrue(result.contains("\"allowBackfill\":" + allowBackfill), result);
        }
    }

    @Test void invalidCoverageCannotDiscardLegalSelectionOrReenableBackfillAfterBrokenRepair() {
        String invalidReport = """
                {"schemaVersion":1,"approved":["create-good","create-vetoed"],
                 "rejected":[{"slug":"create-vetoed","reason":"OFF_TOPIC","explanation":"主题不符"}],
                 "allowBackfill":false,"requirements":[{"id":"r-0000000000000001","evidence":"wrong-type"}]}
                """;
        prepareAddonPool(invalidReport);
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(invalidReport, 1, 1, 10, 10),
                new AiAgentService.AgentCallResult("{broken", 1, 1, 10, 10));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        assertTrue(result.contains("\"criticCoverageDegraded\":true"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void invalidCoverageDoesNotPermitForeignCandidatesOrUnknownRootFields() {
        for (String invalid : List.of(
                "{\"schemaVersion\":1,\"approved\":[\"foreign-mod\"],\"rejected\":[],\"allowBackfill\":false,\"requirements\":\"bad\"}",
                "{\"schemaVersion\":1,\"approved\":[\"create-good\"],\"rejected\":[],\"allowBackfill\":false,\"unknown\":true,\"requirements\":\"bad\"}")) {
            prepareAddonPool(invalid);
            String result = assemble(addonIntent(), "create");
            assertTrue(result.contains("\"criticDegraded\":true"), result);
            assertTrue(result.contains("\"criticCoverageDegraded\":false"), result);
            assertFalse(result.contains("foreign-mod"), result);
        }
    }

    @Test void explicitCoreAddonSurvivesBroadGoalsExhaustingTheSharedSearchBudget() {
        var mapper = new tools.jackson.databind.ObjectMapper();
        when(api.getProjectInfo(anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            return mapper.createObjectNode().put("id", slug.equals("core-mod") ? "core-project" : slug + "-project")
                    .put("slug", slug).put("title", slug.equals("core-mod") ? "Core Mod" : slug).put("body", "Provides automation gameplay");
        });
        when(api.getLatestCompatibleVersionBySlug(anyString(), anyString(), anyString())).thenAnswer(call -> {
            String slug = call.getArgument(0);
            var version = mapper.createObjectNode().put("id", "version-" + slug).put("project_id", slug + "-project");
            var deps = version.putArray("dependencies");
            if (slug.equals("linked-extension")) deps.addObject().put("project_id", "core-project").put("dependency_type", "required");
            return version;
        });
        var addonSearch = mapper.createObjectNode();
        addonSearch.putArray("hits").addObject().put("slug", "linked-extension").put("title", "Core Mod Extension")
                .put("description", "Adds automation features to Core Mod").put("downloads", 5000);
        when(api.search(eq("Core Mod"), anyInt(), anyString())).thenReturn(addonSearch);
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        when(api.searchPage(anyString(), anyInt(), anyInt(), nullable(String.class), anyString())).thenAnswer(call -> {
            var result = mapper.createObjectNode(); var hits = result.putArray("hits");
            int group = sequence.incrementAndGet();
            hits.addObject().put("slug", "linked-extension").put("title", "Core Mod Automation Extension")
                    .put("description", "Provides automation gameplay").put("downloads", 5000).putArray("versions").add("1.21.1");
            for (int i = 0; i < 14; i++) hits.addObject().put("slug", "automation-" + group + "-" + i)
                    .put("title", "Automation gameplay").put("description", "Provides automation gameplay")
                    .put("downloads", 10000).putArray("versions").add("1.21.1");
            return result;
        });
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenAnswer(call -> {
            Set<String> candidates = call.getArgument(2);
            return new AiAgentService.AgentCallResult(new AgentContractCodec().write(new ReviewDecision(1,
                    candidates.stream().sorted().toList(), List.of(), false)), 1, 1, 10, 10);
        });
        var requirements = new ArrayList<BuildRequirement>();
        requirements.add(new BuildRequirement("r-0000000000000001", "扩展核心模组附属", BuildRequirement.Kind.ADDONS,
                List.of("core-mod"), List.of(), List.of("core-mod")));
        for (int i = 0; i < 6; i++) {
            int goal = i;
            var keywords = java.util.stream.IntStream.range(0, 7).mapToObj(k -> "automation " + goal + " " + k).toList();
            requirements.add(new BuildRequirement(String.format("r-%016x", i + 2), "自动化玩法 " + i,
                    BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(),
                    List.of(new BuildIntent.SearchIntent("technology", 100, keywords))));
        }
        var base = intent(BuildIntent.Mode.NEW, List.of("core-mod"), List.of(),
                new BuildIntent.CountTarget(150, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE));
        var plan = new BuildIntent(1, base.mode(), base.reply(), base.name(), base.environment(), base.count(), base.mustHave(),
                base.add(), base.remove(), base.exclude(), List.of("core-mod"), base.replacementPolicy(), List.of(),
                List.of(), base.maxDownloads(), base.constraintChanges(), requirements);
        String result = assemble(plan, "");
        String mods = result.split("<mods>")[1].split("</mods>")[0];
        assertTrue(Arrays.asList(mods.split(",")).contains("linked-extension"), result);
        assertEquals(1, Arrays.stream(mods.split(",")).filter("linked-extension"::equals).count());
        assertTrue(sequence.get() >= 30, "Broad goals must exercise the shared query budget");
        var trace = mapper.readTree(result.split("<trace>")[1].split("</trace>")[0]);
        assertEquals(RetrievalBudget.MAX_SEARCHES, trace.path("retrieval").path("searchQueries").asInt());
        assertTrue(trace.path("retrieval").path("candidateVersions").asInt()
                <= trace.path("retrieval").path("versionLimit").asInt());
        assertTrue(trace.has("candidatePool"));
        assertTrue(trace.path("candidatePool").asInt() <= 150);
        verify(api).search(eq("Core Mod"), anyInt(), anyString());
    }

    @Test void explicitWholePoolVetoDoesNotTriggerFallback() {
        prepareAddonPool(new AgentContractCodec().write(new ReviewDecision(1, List.of(), List.of(), false)));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create</mods>"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        assertTrue(result.contains("\"backfilled\":0"), result);
    }

    @Test void conflictingApprovalCannotOverrideVetoEvenDuringBackfill() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["create-good","create-vetoed"],
                 "rejected":[{"slug":"create-vetoed","reason":"CONSTRAINT","explanation":"明确排除"}],"allowBackfill":true}
                """);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"overlapRemoved\":1"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void wholePoolConflictAndVetoDoesNotRefillTheRejectedCandidates() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["create-good","create-vetoed"],"rejected":[
                 {"slug":"create-good","reason":"CONSTRAINT","explanation":"明确排除"},
                 {"slug":"create-vetoed","reason":"CONSTRAINT","explanation":"明确排除"}],"allowBackfill":true}
                """);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create</mods>"), result);
        assertTrue(result.contains("\"backfilled\":0"), result);
        assertTrue(result.contains("\"overlapRemoved\":2"), result);
    }

    @Test void excessiveApprovalsAreRankedAndBudgetedWithoutLosingVetoOrBackfillOptOut() {
        prepareAddonPool("""
                {"schemaVersion":1,"approved":["addon-0","addon-1","addon-2","addon-3","addon-4","addon-5"],
                 "rejected":[{"slug":"addon-vetoed","reason":"CONSTRAINT","explanation":"明确排除"}],"allowBackfill":false}
                """);
        var mapper = new tools.jackson.databind.ObjectMapper();
        var hits = mapper.createObjectNode();
        var array = hits.putArray("hits");
        for (int i = 0; i < 6; i++) array.addObject().put("slug", "addon-" + i).put("title", "Create addon")
                .put("downloads", (i + 1) * 10000);
        array.addObject().put("slug", "addon-vetoed").put("title", "Create veto").put("downloads", 900000);
        array.addObject().put("slug", "addon-unselected").put("title", "Create unselected").put("downloads", 800000);
        when(api.search(anyString(), anyInt(), anyString())).thenReturn(hits);
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>addon-1,addon-2,addon-3,addon-4,addon-5,create</mods>"), result);
        assertTrue(result.contains("\"excessApproved\":1"), result);
        assertTrue(result.contains("\"allowBackfill\":false"), result);
        assertTrue(result.contains("\"backfilled\":0"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(1)).criticPools(anyString(), anyString(), anySet(), anyInt());
    }

    @Test void invalidDecisionIsRepairedBeforeBackfillAndBothCallsAreCounted() {
        var codec = new AgentContractCodec();
        String invalid = codec.write(new ReviewDecision(1, List.of("outside-pool"), List.of(), false));
        prepareAddonPool(invalid);
        String corrected = codec.write(new ReviewDecision(1, List.of("create-good"), List.of(), false));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(invalid, 1, 1, 10, 10),
                new AiAgentService.AgentCallResult(corrected, 2, 1, 20, 20));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"critic\":{\"ms\":3,\"calls\":2,\"in\":30,\"out\":30,\"truncatedCalls\":0}"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents).criticPools(contains("修正以下上一响应，返回完整 JSON 对象：\n" + invalid),
                anyString(), anySet(), anyInt());
    }

    @Test void truncatedReviewIsRepairedOnceWithoutExecutingPartialPermissions() {
        String truncated = "{\"schemaVersion\":1,\"approved\":[\"create-vetoed\"],\"rejected\":[";
        prepareAddonPool(truncated);
        String corrected = new AgentContractCodec().write(new ReviewDecision(1, List.of("create-good"), List.of(), false));
        when(agents.criticPools(anyString(), anyString(), anySet(), anyInt())).thenReturn(
                new AiAgentService.AgentCallResult(truncated, 1, 1, 10, 32768, 1),
                new AiAgentService.AgentCallResult(corrected, 2, 1, 20, 20));
        String result = assemble(addonIntent(), "create");
        assertTrue(result.contains("<mods>create,create-good</mods>"), result);
        assertTrue(result.contains("\"out\":32788,\"truncatedCalls\":1"), result);
        assertTrue(result.contains("\"criticDegraded\":false"), result);
        verify(agents, times(2)).criticPools(anyString(), anyString(), anySet(), anyInt());
        verify(agents).criticPools(contains("finish_reason=length"), anyString(), anySet(), anyInt());
    }

    @Test void legacyXmlStillProducesReadablePackReply() {
        String result = ReflectionTestUtils.invokeMethod(controller, "processAndAssembleBlueprint",
                "旧计划<name>旧包</name><mc>1.21.1</mc><loader>neoforge</loader><target_count>1</target_count>"
                        + "<core_mods>jei</core_mods><expand_addons></expand_addons><search_intents></search_intents>",
                "", "需求", "fixture-key", "fixture-session", new PackSessionState(), Set.<String>of(),
                KnowledgeRule.SourceType.ADMIN, new AiAgentService.AgentCallResult("", 1, 1, 10, 10),
                "1.21.1", "neoforge", null);
        assertTrue(result.contains("<mods>jei</mods>"), result);
        assertTrue(result.contains("<name>旧包</name>"), result);
    }

    @Test void upstreamFailureIsNotEvidenceThatNamedModDoesNotExist() {
        when(api.getProjectInfo("create")).thenThrow(new ModrinthApiClient.UnavailableException("fixture unavailable"));
        Set<String> unverified = new LinkedHashSet<>();
        Set<String> sanitized = ReflectionTestUtils.invokeMethod(controller, "sanitizePackInitialMods", Set.of("create"),
                "neoforge", "1.21.1", unverified, new ArrayList<>(), Set.of(), BuildIntent.ReplacementPolicy.NONE);
        assertEquals(Set.of("create"), sanitized);
        assertEquals(Set.of("create"), unverified);
        verify(api, never()).search(anyString(), anyInt(), anyString());
    }

    @Test void unspecifiedEditQuantityDoesNotSuggestDeletingRequiredLibrariesToMeetInventedGoal() {
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any())).thenAnswer(call -> {
            Set<String> roots = new LinkedHashSet<>(call.getArgument(0));
            var graph = new DependencyGraph();
            graph.allSlugs.addAll(roots);
            graph.allSlugs.addAll(Set.of("library-one", "library-two", "library-three"));
            return new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, roots, List.of(), List.of(), 500, false);
        });
        String result = assemble(intent(BuildIntent.Mode.EDIT, List.of("journeymap"), List.of("create"), null), "create,jei");
        assertFalse(result.contains("超过你期望的"), result);
        assertFalse(result.contains("建议优先移除"), result);
        assertTrue(result.contains("\"quantity\":null"), result);
    }

    @Test void relativeSearchWeightsAreNormalizedAndKeywordsAreNotSplitAgain() {
        List<ChatController.SearchIntent> intents = ChatController.structuredIntents(List.of(
                new BuildIntent.SearchIntent("magic", 100, List.of("spell", "mana:ritual")),
                new BuildIntent.SearchIntent("adventure", 100, List.of("boss, dungeon"))));
        assertEquals(50, intents.get(0).ratio());
        assertEquals(50, intents.get(1).ratio());
        assertEquals(List.of("mana:ritual"), intents.get(0).keywords().subList(1, 2));
        assertEquals(List.of("boss, dungeon"), intents.get(1).keywords());
        assertTrue(ChatController.structuredIntents(List.of()).isEmpty());
    }
}
