package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class FinalQuantityCoordinatorTest {
    final ObjectMapper json=new ObjectMapper();
    final String a="r-0000000000000001", b="r-0000000000000002", c="r-0000000000000003";
    final Map<String,PackAddonPolicy.Project> metadata=new LinkedHashMap<>();
    final Map<String,List<String>> dependencies=new LinkedHashMap<>();
    final Map<String,String> facts=new LinkedHashMap<>();
    final List<BuildRequirement> requirements=new ArrayList<>();
    final List<RequirementReview> reviews=new ArrayList<>();
    void project(String slug,String... required) {
        metadata.put(slug,new PackAddonPolicy.Project("project-"+slug,slug,1000L,Set.of("adventure"),slug,"","Content"));
        dependencies.put(slug,List.of(required)); facts.put(slug,"Adds unusual structures to the overworld");
    }
    ResolutionResult resolve(Set<String> roots) {
        var graph=new DependencyGraph(); var pending=new ArrayDeque<>(roots);
        while(!pending.isEmpty()) {
            String slug=pending.removeFirst(); if(!graph.allSlugs.add(slug)) continue;
            var version=json.createObjectNode().put("id","version-"+slug).put("project_id","project-"+slug);
            var deps=version.putArray("dependencies");
            for(String dep : dependencies.getOrDefault(slug,List.of())) {
                deps.addObject().put("dependency_type","required").put("project_id","project-"+dep);
                graph.addEdge(slug,dep);pending.addLast(dep);
            }
            graph.selectedVersions.put(slug,version);
        }
        return new ResolutionResult(graph,ResolutionResult.Status.COMPLETE,roots,List.of(),List.of(),500,false);
    }
    BuildRequirement quantity(String id,int min,int max) {
        return new BuildRequirement(id,"补充内容",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of(),List.of(),
                SelectionPreferences.DEFAULT,List.of(),new RequirementQuantity(min,max,RequirementQuantity.Scope.ADDED_ROOTS));
    }
    void evidence(String id,String... slugs) {
        reviews.add(new RequirementReview(id,Arrays.stream(slugs).map(s ->
                new RequirementReview.Contribution(s,facts.get(s),"提供结构探索内容")).toList(),""));
    }
    RequirementCoverage.Report assess(ResolutionResult result,Set<String> roots) {
        return RequirementCoverage.assess(requirements,reviews,result.graph(),result.graph().allSlugs,Map.of(),metadata,"NONE",List.of(),Map.of(),facts,
                new RequirementCoverage.CountContext(roots,Set.of()));
    }
    BuildIntent.CountTarget range(int min,int max,BuildIntent.CountScope scope) {
        return new BuildIntent.CountTarget(min,scope,BuildIntent.Precision.EXACT,min,max);
    }
    FinalQuantityCoordinator.Result coordinate(Set<String> roots,Set<String> protectedRoots,BuildIntent.CountTarget count,List<String> order,List<String> candidates,
            Map<String,Set<String>> hints,FinalQuantityCoordinator.Resolver solver) {
        return FinalQuantityCoordinator.coordinate(resolve(roots),roots,protectedRoots,Set.of(),count,Set.of(),true,order,candidates,hints,this::assess,(r,s)->true,solver);
    }
    @Test void q01RangesUseFinalRelationsThenRepairStructuresAndFillGlobalMinimum() {
        project("iron-core");project("cooking-core");
        var roots=new LinkedHashSet<>(List.of("iron-core","cooking-core"));
        var remove=new ArrayList<String>();
        for(int i=1;i<=9;i++) {String s="iron-extra-"+i;project(s,"iron-core");roots.add(s);}
        for(int i=1;i<=7;i++) {String s="cooking-extra-"+i;project(s,"cooking-core");roots.add(s);remove.add(s);}
        project("structure-one");project("structure-two");roots.add("structure-one");
        for(int i=1;i<=19;i++) {project("content-"+i);roots.add("content-"+i);}
        for(int i=20;i<=24;i++) project("content-"+i);
        requirements.add(new BuildRequirement(a,"补充铁魔法附属5-10个",BuildRequirement.Kind.ADDONS,List.of(),List.of(),List.of("iron-core"),List.of(),SelectionPreferences.DEFAULT,List.of(),new RequirementQuantity(5,10,RequirementQuantity.Scope.ADDED_ROOTS)));
        requirements.add(new BuildRequirement(b,"补充农夫乐事附属3-5个",BuildRequirement.Kind.ADDONS,List.of(),List.of(),List.of("cooking-core"),List.of(),SelectionPreferences.DEFAULT,List.of(),new RequirementQuantity(3,5,RequirementQuantity.Scope.ADDED_ROOTS)));
        requirements.add(quantity(c,2,4));evidence(c,"structure-one","structure-two");
        var candidates=List.of("content-20","content-21","content-22","content-23","content-24","structure-two");
        var result=coordinate(roots,Set.of("iron-core","cooking-core"),range(40,70,BuildIntent.CountScope.TOTAL_FILES),remove,candidates,
                Map.of("structure-two",Set.of(c)),(base,next)->resolve(next));
        assertEquals(38,resolve(roots).graph().allSlugs.size());
        assertEquals(40,result.report().actual()); assertEquals("WITHIN_POLICY",result.report().status());
        assertEquals(9,result.report().targets().get(a).actual());assertEquals(5,result.report().targets().get(b).actual());
        assertEquals(2,result.report().targets().get(c).actual());assertTrue(result.roots().contains("structure-two"));
        assertTrue(result.report().gaps().isEmpty());assertEquals(38,resolve(roots).graph().allSlugs.size());
    }
    @Test void sharedEvidenceCountsTwiceButGlobalFilesAreUniqueAndLibrariesDoNotFillLocalCounts() {
        project("shared");project("first");project("second");project("helper-api");
        requirements.add(quantity(a,1,1));requirements.add(quantity(b,1,1));
        evidence(a,"shared","first","helper-api");evidence(b,"shared","second","helper-api");
        var result=coordinate(Set.of("shared","first","second","helper-api"),Set.of("shared"),null,List.of("first","second"),List.of(),Map.of(),(base,next)->resolve(next));
        assertEquals(Set.of("shared","helper-api"),result.roots());assertEquals(2,result.report().actual());
        assertEquals(1,result.report().targets().get(a).actual());assertEquals(1,result.report().targets().get(b).actual());
    }
    @Test void globalTrimmingProtectsOtherLowerBoundsAndCleansOnlyOrphanDependencies() {
        project("keep","shared-library");project("optional","shared-library","orphan-library");project("shared-library");project("orphan-library");
        requirements.add(quantity(a,1,2));evidence(a,"keep");
        var baseline=resolve(Set.of("keep","optional"));
        var result=coordinate(baseline.roots(),Set.of(),range(2,2,BuildIntent.CountScope.TOTAL_FILES),List.of("keep","optional"),List.of(),Map.of(),(base,next)->resolve(next));
        assertEquals(Set.of("keep","shared-library"),result.resolution().graph().allSlugs);
        assertEquals(4,baseline.graph().allSlugs.size()); assertEquals(1,result.report().targets().get(a).actual());
    }
    @Test void protectedOverageIsHonestAndDoesNotLoopOrDropExplicitProjects() {
        project("first");project("second");requirements.add(quantity(a,1,1));evidence(a,"first","second");
        var result=coordinate(Set.of("first","second"),Set.of("first","second"),range(1,1,BuildIntent.CountScope.ROOT_MODS),List.of(),List.of(),Map.of(),(base,next)->fail("No additions allowed"));
        assertEquals("PARTIAL",result.report().status());assertEquals(2,result.roots().size());assertEquals(0,result.report().trials());
        assertTrue(result.report().gaps().contains(a+":PROTECTED_OVERAGE"));
    }
    @Test void trialCannotChangeRetainedVersionsEvenWhenItWouldFillARequirement() {
        project("first");project("second");requirements.add(quantity(a,2,2));evidence(a,"first","second");
        var result=coordinate(Set.of("first"),Set.of("first"),range(2,2,BuildIntent.CountScope.ROOT_MODS),List.of(),List.of("second"),Map.of(),(base,next)-> {
            var trial=resolve(next);((tools.jackson.databind.node.ObjectNode)trial.graph().selectedVersions.get("first")).put("id","changed");return trial;
        });
        assertEquals(Set.of("first"),result.roots());assertEquals(1,result.report().trials());assertEquals("PARTIAL",result.report().status());
    }
    @Test void retrievalHintsCannotProveAMissingPurposeOrOverrideAnotherTargetsMaximum() {
        project("first");project("hint-only");requirements.add(quantity(a,2,2));evidence(a,"first");
        var result=coordinate(Set.of("first"),Set.of(),range(1,2,BuildIntent.CountScope.ROOT_MODS),List.of(),List.of("hint-only"),Map.of("hint-only",Set.of(a)),(base,next)->resolve(next));
        assertEquals(Set.of("first"),result.roots());assertTrue(result.report().gaps().contains(a+":BELOW_MINIMUM"));
        project("other");requirements.add(quantity(b,1,1));evidence(b,"first","other");evidence(a,"other");
        var blocked=coordinate(Set.of("first"),Set.of("first"),null,List.of(),List.of("other"),Map.of("other",Set.of(a,b)),(base,next)->resolve(next));
        assertFalse(blocked.roots().contains("other"));assertEquals(1,blocked.report().targets().get(b).actual());
    }
    @Test void unknownRootsIncompleteGraphAndCancellationPreserveOriginalState() {
        project("first");project("second");var baseline=resolve(Set.of("first"));
        var unknown=FinalQuantityCoordinator.coordinate(baseline,baseline.roots(),Set.of(),Set.of(),range(2,2,BuildIntent.CountScope.ROOT_MODS),Set.of(),false,
                List.of(),List.of("second"),Map.of(),this::assess,(r,s)->true,(base,next)->fail("Unknown roots must not be rewritten"));
        assertEquals("UNKNOWN_ROOT_IDENTITY",unknown.report().status());
        var incomplete=new ResolutionResult(baseline.graph(),ResolutionResult.Status.INCOMPLETE,baseline.roots(),List.of(),List.of(),500,false);
        var unchanged=FinalQuantityCoordinator.coordinate(incomplete,baseline.roots(),Set.of(),Set.of(),range(2,2,BuildIntent.CountScope.ROOT_MODS),Set.of(),true,
                List.of(),List.of("second"),Map.of(),this::assess,(r,s)->true,(base,next)->fail("Incomplete facts must not be rewritten"));
        assertSame(incomplete,unchanged.resolution());
        assertThrows(java.util.concurrent.CancellationException.class,()->coordinate(Set.of("first"),Set.of(),range(2,2,BuildIntent.CountScope.ROOT_MODS),List.of(),List.of("second"),Map.of(),(base,next)->{throw new java.util.concurrent.CancellationException();}));
    }
    @Test void addedRootScopeExcludesOriginalRootsAndBlockedProjectIdsRejectWholeTrial() {
        project("existing");project("new-one");project("new-two");
        var baseline=resolve(Set.of("existing","new-one"));
        var result=FinalQuantityCoordinator.coordinate(baseline,baseline.roots(),Set.of("existing"),Set.of(),range(2,2,BuildIntent.CountScope.ADDED_ROOTS),Set.of("existing"),true,
                List.of(),List.of("new-two"),Map.of(),this::assess,(r,s)->true,(base,next)->resolve(next));
        assertEquals(3,result.resolution().graph().allSlugs.size());assertEquals(2,result.report().actual());
        var denied=FinalQuantityCoordinator.coordinate(baseline,baseline.roots(),Set.of(),Set.of("project-new-two"),range(2,2,BuildIntent.CountScope.ADDED_ROOTS),Set.of("existing"),true,
                List.of(),List.of("new-two"),Map.of(),this::assess,(r,s)->true,(base,next)->resolve(next));
        assertEquals(baseline.roots(),denied.roots());assertEquals(1,denied.report().trials());
    }
    @Test void relationSettlementTrimsAfterDiscoveryWithoutNewSubjectTrials() {
        project("core");project("filler");project("integration","core");
        requirements.add(new BuildRequirement(a,"核心附属",BuildRequirement.Kind.ADDONS,List.of(),List.of(),List.of("core"),List.of(),SelectionPreferences.DEFAULT,List.of(),
                new RequirementQuantity(1,1,RequirementQuantity.Scope.ADDED_ROOTS)));
        var result=coordinate(Set.of("core","filler","integration"),Set.of("core"),range(2,2,BuildIntent.CountScope.TOTAL_FILES),List.of("integration","filler"),List.of(),Map.of(),
                (base,next)->fail("Settlement must not introduce new content"));
        assertEquals(Set.of("core","integration"),result.roots());assertEquals(0,result.report().trials());assertEquals("WITHIN_POLICY",result.report().status());
    }
    @Test void onlyMissingExplicitQuantitiesTriggerDirectTargetRetrieval() {
        project("one");project("two");project("three");evidence(a,"one","two","three");
        var search=List.of(new BuildIntent.SearchIntent("adventure",100,List.of("structures")));
        requirements.add(new BuildRequirement(a,"补充结构2-3个",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of(),search,
                SelectionPreferences.DEFAULT,List.of(),new RequirementQuantity(2,3,RequirementQuantity.Scope.ADDED_ROOTS)));
        var lacking=resolve(Set.of("one"));assertEquals(List.of(a),RequirementCoverage.missingTargets(requirements,assess(lacking,lacking.roots())).stream().map(BuildRequirement::id).toList());
        var sufficient=resolve(Set.of("one","two"));assertTrue(RequirementCoverage.missingTargets(requirements,assess(sufficient,sufficient.roots())).isEmpty());
        requirements.set(0,new BuildRequirement(a,"补充结构1-2个",BuildRequirement.Kind.CAPABILITY,List.of(),List.of(),List.of(),search,
                SelectionPreferences.DEFAULT,List.of(),new RequirementQuantity(1,2,RequirementQuantity.Scope.ADDED_ROOTS)));
        var over=resolve(Set.of("one","two","three"));assertTrue(RequirementCoverage.missingTargets(requirements,assess(over,over.roots())).isEmpty());
    }
    @Test void optionalLocalOverageCanBeReplacedByDiverseContentAtTheGlobalMinimum() {
        project("first");project("second");project("third");project("diverse");requirements.add(quantity(a,1,2));evidence(a,"first","second","third");
        var result=coordinate(Set.of("first","second","third"),Set.of(),range(3,4,BuildIntent.CountScope.TOTAL_FILES),List.of("third"),List.of("diverse"),Map.of(),(base,next)->resolve(next));
        assertEquals(Set.of("first","second","diverse"),result.roots());assertEquals(2,result.report().targets().get(a).actual());assertEquals(3,result.report().actual());
    }
    @Test void trialBudgetAndRejectionDoNotChangeExistingSnapshot() {
        project("first");var candidates=new ArrayList<String>();for(int i=0;i<96;i++){project("candidate-"+i);candidates.add("candidate-"+i);}
        var result=coordinate(Set.of("first"),Set.of(),range(100,120,BuildIntent.CountScope.TOTAL_FILES),List.of(),candidates,Map.of(),(base,next)->null);
        assertEquals(FinalQuantityCoordinator.MAX_TRIALS,result.report().trials());assertEquals("BUDGET_EXHAUSTED",result.report().status());assertEquals(Set.of("first"),result.roots());
    }
}
