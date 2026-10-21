package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RequirementExecutionTest {
    private BuildRequirement goal(String id, BuildRequirement.Kind kind, List<String> projects, List<String> cores) {
        return new BuildRequirement("r-"+id,"自定义玩法目标 "+id,kind,projects,List.of(),cores);
    }
    @Test void scheduleUsesTargetRelationsInsteadOfKnownCategoriesOrCoreNames() {
        var identity=goal("0000000000000001",BuildRequirement.Kind.PROJECTS,List.of("arbitrary-core"),List.of());
        var content=goal("0000000000000002",BuildRequirement.Kind.CAPABILITY,List.of(),List.of());
        var addons=goal("0000000000000003",BuildRequirement.Kind.ADDONS,List.of(),List.of("arbitrary-core"));
        var integrations=goal("0000000000000004",BuildRequirement.Kind.PACK_INTEGRATIONS,List.of(),List.of("arbitrary-core"));
        var plan=new RequirementExecution(List.of(identity,content,addons,integrations));
        assertEquals(List.of(identity.id()),plan.targets().get(2).dependsOn());
        assertEquals(Set.of(identity.id(),content.id(),addons.id()),Set.copyOf(plan.targets().get(3).dependsOn()));
        assertEquals("RETAINED_SUBJECT_VERSIONS",plan.targets().get(3).scope());
        assertTrue(plan.earlyCore("arbitrary-core"));assertFalse(plan.deferred(Set.of("arbitrary-core"),false));
        assertTrue(plan.deferred(Set.of("arbitrary-core"),true));
        var onlyRelations=new RequirementExecution(List.of(integrations));
        assertFalse(onlyRelations.earlyCore("arbitrary-core"));assertTrue(onlyRelations.deferred(Set.of("arbitrary-core"),false));
        assertEquals(0,plan.reserve(1,true));assertTrue(plan.reserve(30,true)>0);
    }
    @Test void compoundRequirementsKeepIndependentAddonsEarlyAndPackRelationsLate() {
        var r=new BuildRequirement("r-0000000000000001","普通附属和包内联动",BuildRequirement.Kind.ADDONS,List.of(),List.of(),List.of("custom-core"),List.of(),
                SelectionPreferences.DEFAULT,List.of(new RequirementCriterion("普通附属",BuildRequirement.Kind.ADDONS,List.of(),List.of("custom-core"),List.of()),
                new RequirementCriterion("包内联动",BuildRequirement.Kind.PACK_INTEGRATIONS,List.of(),List.of("custom-core"),List.of())));
        var plan=new RequirementExecution(List.of(r));
        assertEquals(RequirementExecution.Phase.CORE_ADDONS,plan.targets().get(0).phase());
        assertEquals(RequirementExecution.Phase.PACK_RELATIONS,plan.targets().get(1).phase());
        assertTrue(plan.targets().get(1).dependsOn().contains(plan.targets().get(0).id()));
    }
    @Test void confirmationWithdrawsRelationsOnlyForAffectedPartnersOrVersions() {
        var plan=new RequirementExecution(List.of(goal("0000000000000001",BuildRequirement.Kind.PACK_INTEGRATIONS,List.of(),List.of("custom-core"))));
        var report=plan.report(Map.of("custom-core","V1","partner","V2","unrelated","V3"),
                List.of(new RequirementExecution.Relation("bridge","V4","custom-core",List.of("partner"),"VERIFIED")),"ASSEMBLED_ONCE");
        var selected=new AssemblySnapshot("neoforge","1.21.1",List.of("custom-core","partner","bridge"),Map.of("custom-core","V1","partner","V2","bridge","V4"),Map.of());
        assertEquals("VERIFIED",RequirementExecution.afterSelection(report,selected).relations().getFirst().status());
        var removed=new AssemblySnapshot("neoforge","1.21.1",List.of("custom-core","bridge"),Map.of("custom-core","V1","bridge","V4"),Map.of());
        assertEquals("PARTNER_NOT_RETAINED",RequirementExecution.afterSelection(report,removed).relations().getFirst().status());
        var changed=new AssemblySnapshot("neoforge","1.21.1",selected.roots(),Map.of("custom-core","V1","partner","V2","bridge","V5"),Map.of());
        assertEquals("VERSION_RECHECK_REQUIRED",RequirementExecution.afterSelection(report,changed).relations().getFirst().status());
    }
    @Test void repairAndLateDiscoveryShareTheOriginalGlobalBudgetWithoutStarvingEachOther() {
        var budget=new RetrievalBudget();budget.reserveForRepair();budget.reserveForRelations();
        int earlySearch=0;for(int i=0;i<42;i++)if(budget.search("early-"+i))earlySearch++;
        int earlyVersion=0;for(int i=0;i<300;i++)if(budget.version("early-"+i))earlyVersion++;
        assertEquals(32,earlySearch);assertEquals(264,earlyVersion);
        budget.beginRepair();for(int i=0;i<4;i++)assertTrue(budget.search("repair-"+i));
        for(int i=0;i<12;i++)assertTrue(budget.version("repair-"+i));
        budget.beginRelations();for(int i=0;i<6;i++)assertTrue(budget.search("relation-"+i));
        for(int i=0;i<24;i++)assertTrue(budget.version("relation-"+i));
        assertFalse(budget.search("excess"));assertFalse(budget.version("excess"));
        assertEquals(42,budget.snapshot().get("searchQueries"));assertEquals(300,budget.snapshot().get("candidateVersions"));
    }
}
