package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RemainingBudgetRankingTest {
    private RemainingBudgetRanking.Candidate c(String slug,double score,String category,String facet) {
        return new RemainingBudgetRanking.Candidate(slug,score,Set.of(category),Set.of(facet));
    }
    private RemainingBudgetRanking.Report rank(List<String> input,Map<String,RemainingBudgetRanking.Candidate> facts,
                                               Set<String> priority,Set<String> retained,Map<String,Integer> history,double influence,boolean backfill) {
        return RemainingBudgetRanking.rank(input,facts,priority,retained,history,influence,RemainingBudgetRanking.Weights.defaults(),backfill);
    }
    @Test void explicitTargetsStayAheadEvenWithWeakScoresAndRepeatedCategories() {
        var facts=Map.of("required",c("required",.01,"magic","spells"),"novel",c("novel",100,"adventure","ruins"));
        assertEquals(List.of("required","novel"),rank(List.of("novel","required"),facts,Set.of("required"),Set.of(),Map.of(),0,true).order());
    }
    @Test void diversityMeasuresIncrementalValueAgainstRetainedContent() {
        var facts=Map.of("core",c("core",0,"magic","spells"),"same",c("same",100,"magic","spells"),"new",c("new",95,"adventure","ruins"));
        assertEquals("new",rank(List.of("same","new"),facts,Set.of(),Set.of("core"),Map.of(),0,false).order().getFirst());
    }
    @Test void familiarityAffectsOnlyTheSoftTailAndCanBeDisabled() {
        var facts=Map.of("familiar",c("familiar",100,"magic","spells"),"unseen",c("unseen",100,"magic","spells"));
        assertEquals("unseen",rank(List.of("familiar","unseen"),facts,Set.of(),Set.of(),Map.of("familiar",100),1,false).order().getFirst());
        assertEquals("familiar",rank(List.of("familiar","unseen"),facts,Set.of(),Set.of(),Map.of("familiar",100),0,false).order().getFirst());
    }
    @Test void backfillDoesNotUseClearlyWeakerCandidatesJustToFillSpace() {
        var facts=Map.of("good",c("good",100,"magic","spells"),"weak",c("weak",1,"adventure","ruins"));
        var report=rank(List.of("good","weak"),facts,Set.of(),Set.of(),Map.of(),0,true);
        assertEquals(List.of("good"),report.order());assertEquals(List.of("weak"),report.deferred());
        assertEquals(2,rank(List.of("good","weak"),facts,Set.of(),Set.of(),Map.of(),0,false).order().size());
    }
    @Test void unknownScoresAreNeutralAndOrderingIsDeterministicWithoutRandomColdMods() {
        var unknown=new RemainingBudgetRanking.Candidate("unknown",Double.NaN,Set.of(),Set.of());
        var facts=Map.of("unknown",unknown,"other",new RemainingBudgetRanking.Candidate("other",0,Set.of(),Set.of()));
        var input=List.of("unknown","other","unknown");
        assertEquals(List.of("unknown","other"),rank(input,facts,Set.of(),Set.of(),Map.of(),0,true).order());
        assertEquals(rank(input,facts,Set.of(),Set.of(),Map.of(),0,true),rank(input,facts,Set.of(),Set.of(),Map.of(),0,true));
    }
    @Test void retainedCandidatesAreNotCountedAgainAndFixedTargetsConsumeDiversity() {
        var facts=Map.of("fixed",c("fixed",5,"magic","spells"),"same",c("same",100,"magic","spells"),"new",c("new",95,"worldgen","terrain"));
        assertEquals(List.of("fixed","new","same"),rank(List.of("fixed","same","new"),facts,Set.of("fixed"),Set.of(),Map.of(),0,false).order());
        assertFalse(rank(List.of("fixed","same"),facts,Set.of(),Set.of("fixed"),Map.of(),0,false).order().contains("fixed"));
    }
    @Test void ineligibleFactsOutsideTheInputCannotRaiseTheQualityFloor() {
        var facts=Map.of("vetoed",c("vetoed",10000,"magic","spells"),"allowed",c("allowed",10,"magic","spells"));
        assertEquals(List.of("allowed"),rank(List.of("allowed"),facts,Set.of(),Set.of(),Map.of(),0,true).order());
    }
    @Test void invalidConfigurationFailsAndWeightsAreNormalized() {
        assertThrows(IllegalArgumentException.class,()->new RemainingBudgetRanking.Weights(0,1,1));
        assertThrows(IllegalArgumentException.class,()->new RemainingBudgetRanking.Weights(1,-1,1));
        assertThrows(IllegalArgumentException.class,()->new RemainingBudgetRanking.Weights(Double.NaN,1,1));
        var facts=Map.of("a",c("a",100,"magic","spells"));
        var one=rank(List.of("a"),facts,Set.of(),Set.of(),Map.of(),0,false);
        var two=RemainingBudgetRanking.rank(List.of("a"),facts,Set.of(),Set.of(),Map.of(),0,new RemainingBudgetRanking.Weights(1.2,.5,.3),false);
        assertEquals(one.decisions(),two.decisions());
    }
}
