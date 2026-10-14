package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BuildConstraintsTest {
    private final BuildIntent.CountTarget total = new BuildIntent.CountTarget(60, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE);
    private BuildConstraints previous() {
        return new BuildConstraints(1, List.of("create"), List.of("jei"), List.of("technology"), total,
                BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS, 500_000);
    }
    private BuildIntent edit(List<String> add, List<String> remove, BuildIntent.CountTarget count, ConstraintChanges changes) {
        return new BuildIntent(1, BuildIntent.Mode.EDIT, "编辑", "", BuildEnvironment.current("1.21.1", "neoforge"), count,
                List.of(), add, remove, List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2_100_000_000L, changes);
    }
    @Test void incrementalEditInheritsAllDurableConstraintsAndAddedQuotaIsTransient() {
        var added = new BuildIntent.CountTarget(5, BuildIntent.CountScope.ADDED_ROOTS, BuildIntent.Precision.APPROXIMATE);
        var applied = previous().apply(edit(List.of("jade"), List.of(), added, ConstraintChanges.KEEP), Set.of());
        assertEquals(previous(), applied.constraints());
        assertEquals(List.of("create"), applied.intent().mustHave());
        assertEquals(added, applied.intent().count());
        assertEquals(500_000, applied.intent().maxDownloads());
        assertEquals(BuildIntent.ReplacementPolicy.COMPATIBLE_PORTS, applied.intent().replacementPolicy());
        assertEquals(total, applied.constraints().apply(edit(List.of(), List.of(), null, ConstraintChanges.KEEP), Set.of()).intent().count());
    }
    @Test void explicitRemovalReleasesCoreAndManualDeletionIsNotReintroduced() {
        var removed = previous().apply(edit(List.of(), List.of("create"), null, ConstraintChanges.KEEP), Set.of());
        assertTrue(removed.constraints().mustHave().isEmpty());
        assertTrue(removed.constraints().exclude().contains("create"));
        assertTrue(previous().apply(edit(List.of(), List.of(), null, ConstraintChanges.KEEP), Set.of("create")).intent().mustHave().isEmpty());
    }
    @Test void explicitReleaseCanRestoreBlockedModAndClearOtherConstraints() {
        var changes = new ConstraintChanges(List.of("jei"), List.of("technology"), true, true, true);
        var applied = previous().apply(edit(List.of("jei"), List.of(), null, changes), Set.of("jei"));
        assertTrue(applied.constraints().exclude().isEmpty());
        assertTrue(applied.constraints().excludedCategories().isEmpty());
        assertNull(applied.constraints().count());
        assertEquals(BuildIntent.ReplacementPolicy.NONE, applied.constraints().replacementPolicy());
        assertEquals(2_100_000_000L, applied.constraints().maxDownloads());
    }
    @Test void newPackResetsHistoryAndConflictingEditsCannotOverrideExclusions() {
        assertThrows(IllegalArgumentException.class, () -> previous().apply(edit(List.of("jei"), List.of(), null, ConstraintChanges.KEEP), Set.of()));
        var fresh = new BuildIntent(1, BuildIntent.Mode.NEW, "新建", "", BuildEnvironment.current("1.21.1", "neoforge"), total,
                List.of("jei"), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2_100_000_000L);
        var state = previous().apply(fresh, Set.of("jei")).constraints();
        assertEquals(List.of("jei"), state.mustHave());
        assertTrue(state.exclude().isEmpty());
        assertTrue(state.excludedCategories().isEmpty());
    }
    @Test void legacyPlansOnlyInheritAndMalformedExplicitChangesStillFail() {
        var codec = new AgentContractCodec();
        var mapper = new tools.jackson.databind.ObjectMapper();
        var json = (tools.jackson.databind.node.ObjectNode) mapper.readTree(codec.write(edit(List.of(), List.of(), null, ConstraintChanges.KEEP)));
        json.remove("constraintChanges");
        assertEquals(ConstraintChanges.KEEP, codec.readBuildIntent(json.toString()).constraintChanges());
        json.putNull("constraintChanges");
        assertThrows(IllegalArgumentException.class, () -> codec.readBuildIntent(json.toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.readConstraints("{\"schemaVersion\":1}"));
    }
    @Test void constraintHashIsStableButDistinguishesEditingSemantics() {
        String base = yagen.waitmydawn.maa.service.PackSnapshotHash.id("neoforge", "1.21.1", "create", "");
        assertEquals(base, yagen.waitmydawn.maa.service.PackSnapshotHash.id("neoforge", "1.21.1", "create", "", BuildConstraints.empty()));
        assertNotEquals(base, yagen.waitmydawn.maa.service.PackSnapshotHash.id("neoforge", "1.21.1", "create", "", previous()));
        assertEquals(previous(), new AgentContractCodec().readConstraints(new AgentContractCodec().write(previous())));
    }

    @Test void emptyGraphDoesNotPersistZeroAsAnEditingLimit() {
        var empty = new BuildIntent(1, BuildIntent.Mode.EMPTY, "空图谱", "", BuildEnvironment.current("1.21.1", "neoforge"),
                new BuildIntent.CountTarget(0, BuildIntent.CountScope.TOTAL_FILES, BuildIntent.Precision.APPROXIMATE),
                List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE, List.of(), List.of(), 2_100_000_000L);
        var applied = previous().apply(empty, Set.of("jade"));
        assertNull(applied.constraints().count());
        var next = applied.constraints().apply(edit(List.of("jade"), List.of(), null, ConstraintChanges.KEEP), Set.of());
        assertNull(next.intent().count());
        assertEquals(List.of("jade"), next.intent().add());
    }
}
