package yagen.waitmydawn.maa.model;

import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.agent.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PreferenceRankingTest {
    private final Instant now = Instant.parse("2026-10-14T00:00:00Z");
    private SelectionPreferences preference(SelectionPreferences.Popularity popularity, SelectionPreferences.Freshness freshness) {
        return new SelectionPreferences(popularity, freshness);
    }
    @Test void defaultRetainsExistingRankingAndChannels() {
        assertEquals(RetrievalScorer.popularityNorm(100, 1000000), PreferenceRanking.factor(SelectionPreferences.DEFAULT, 100, 1000000, null, now));
        assertEquals(List.of(new PreferenceRanking.Channel("relevance", false)), PreferenceRanking.channels(SelectionPreferences.DEFAULT));
    }
    @Test void popularityAndPublicationAreIndependentAxes() {
        var less = preference(SelectionPreferences.Popularity.LESS_POPULAR, SelectionPreferences.Freshness.DEFAULT);
        assertTrue(PreferenceRanking.factor(less, 100, 1000000, null, now) > PreferenceRanking.factor(less, 1000000, 1000000, null, now));
        var recent = preference(SelectionPreferences.Popularity.DEFAULT, SelectionPreferences.Freshness.RECENT);
        assertTrue(PreferenceRanking.factor(recent, 1000000, 1000000, now.minusSeconds(86400), now)
                > PreferenceRanking.factor(recent, 100, 1000000, now.minusSeconds(86400L * 1000), now));
    }
    @Test void malformedMissingAndFutureDatesAreUnknown() {
        assertNull(PreferenceRanking.date("", now));
        assertNull(PreferenceRanking.date("tomorrow", now));
        assertNull(PreferenceRanking.date(now.plusSeconds(1).toString(), now));
        var recent = preference(SelectionPreferences.Popularity.DEFAULT, SelectionPreferences.Freshness.RECENT);
        assertEquals(.85, PreferenceRanking.factor(recent, 100, 1000, null, now));
    }
    @Test void longTailFilterOnlyAppliesToAdditionalChannel() {
        var less = preference(SelectionPreferences.Popularity.LESS_POPULAR, SelectionPreferences.Freshness.RECENT);
        var channels = PreferenceRanking.channels(less);
        assertEquals(2, channels.size());
        assertEquals("[[\"project_type:mod\"]]", PreferenceRanking.facets("[[\"project_type:mod\"]]", channels.getFirst()));
        assertEquals("newest", channels.getLast().index());
        assertEquals("[[\"project_type:mod\"],[\"downloads <= 500000\"]]", PreferenceRanking.facets("[[\"project_type:mod\"]]", channels.getLast()));
    }
    @Test void softPreferencesCannotRescueIrrelevantOrOverwhelmStrongRelevance() {
        var less = preference(SelectionPreferences.Popularity.LESS_POPULAR, SelectionPreferences.Freshness.RECENT);
        double popularRelevant = 5 * PreferenceRanking.factor(less, 1000000, 1000000, now.minusSeconds(86400L * 1000), now);
        double weakNew = PreferenceRanking.factor(less, 1, 1000000, now, now);
        assertTrue(popularRelevant > weakNew);
        assertEquals(0, 0 * PreferenceRanking.factor(less, 1, 1000000, now, now));
    }
    @Test void compoundScopeDoesNotInheritAdjacentColdPreferenceAndRemappingPreservesExplicitAxes() {
        var cold = preference(SelectionPreferences.Popularity.LESS_POPULAR, SelectionPreferences.Freshness.RECENT);
        var child = new RequirementCriterion("热门装饰", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of(),
                preference(SelectionPreferences.Popularity.POPULAR, SelectionPreferences.Freshness.DEFAULT));
        var parent = new BuildRequirement("r-0000000000000001", "较新冷门附属和热门装饰", BuildRequirement.Kind.CAPABILITY,
                List.of(), List.of(), List.of(), List.of(), cold, List.of(child));
        assertEquals(SelectionPreferences.Popularity.POPULAR, child.target(parent).preferences().popularity());
        assertEquals(SelectionPreferences.Freshness.DEFAULT, child.target(parent).preferences().freshness());
        assertEquals(child.preferences(), child.replace(Map.of()).preferences());
        var legacy = new RequirementCriterion("常用实用模组", BuildRequirement.Kind.CAPABILITY, List.of(), List.of(), List.of());
        assertEquals(SelectionPreferences.DEFAULT, legacy.target(parent).preferences());
    }
    @Test void negativePreferenceDoesNotInvertAndDoesNotEraseNextPositiveAxis() {
        assertEquals(SelectionPreferences.DEFAULT, SelectionPreferences.fromSource("不要冷门模组，不需要较新模组"));
        assertEquals(SelectionPreferences.Popularity.DEFAULT, SelectionPreferences.fromSource("不要冷门，但要较新模组").popularity());
        assertEquals(SelectionPreferences.Freshness.RECENT, SelectionPreferences.fromSource("不要冷门，但要较新模组").freshness());
        assertEquals(SelectionPreferences.DEFAULT, SelectionPreferences.fromSource("not recent"));
    }

}
