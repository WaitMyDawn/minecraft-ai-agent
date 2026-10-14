package yagen.waitmydawn.maa.model.agent;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuildEnvironmentTest {
    private BuildIntent intent(String mc, BuildIntent.Loader loader, boolean change) {
        return new BuildIntent(1, BuildIntent.Mode.EDIT, "调整环境", "", new BuildIntent.Environment(mc, loader, change),
                null, List.of(), List.of(), List.of(), List.of(), List.of(), BuildIntent.ReplacementPolicy.NONE,
                List.of(), List.of(), 2_100_000_000L);
    }

    @Test void missingFieldsUseConsistentDefaultsAndLoaderCaseIsNormalized() {
        assertEquals(new BuildIntent.Environment("1.21.1", BuildIntent.Loader.NEOFORGE, false), BuildEnvironment.current(null, ""));
        assertEquals(new BuildIntent.Environment("1.20.1", BuildIntent.Loader.FABRIC, false), BuildEnvironment.current(" 1.20.1 ", " fabric "));
        assertThrows(IllegalArgumentException.class, () -> BuildEnvironment.current("1.20.1", "arbitrary-loader"));
    }

    @Test void explicitLoaderOnlyOrVersionOnlySwitchesPreserveTheOtherField() {
        var loaderSwitch = BuildEnvironment.resolve(intent("1.20.1", BuildIntent.Loader.FORGE, true), "1.20.1", "fabric");
        assertEquals("1.20.1", loaderSwitch.environment().mcVersion());
        assertEquals(BuildIntent.Loader.FORGE, loaderSwitch.environment().loader());
        var versionSwitch = BuildEnvironment.resolve(intent("1.21.1", BuildIntent.Loader.FABRIC, true), "1.20.1", "fabric");
        assertEquals(BuildIntent.Loader.FABRIC, versionSwitch.environment().loader());
        assertTrue(versionSwitch.environment().changeRequested());
        assertEquals(versionSwitch, BuildEnvironment.resolve(versionSwitch, "1.20.1", "fabric"));
    }
}
