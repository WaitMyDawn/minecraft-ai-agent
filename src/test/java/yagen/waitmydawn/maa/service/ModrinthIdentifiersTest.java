package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ModrinthIdentifiersTest {
    @Test
    void officialPortSlugsAreAcceptedWithoutAllowingPathOrQueryInjection() {
        assertTrue(ModrinthIdentifiers.isProjectReference("alexs-mobs(1.21.1)"));
        assertTrue(ModrinthIdentifiers.isProjectReference("alexs-caves-(unofficial-port)"));
        assertTrue(ModrinthIdentifiers.isProjectReference("yagens-attributes"));
        assertTrue(ModrinthIdentifiers.isProjectReference("P1"));
        for (String value : new String[]{"../project", "a/b", "a?token=b", "a#b", "a b", "a\nb", "", "a-".repeat(33)})
            assertFalse(ModrinthIdentifiers.isProjectReference(value), value);
        assertFalse(ModrinthIdentifiers.isProjectReference(null));
    }
}
