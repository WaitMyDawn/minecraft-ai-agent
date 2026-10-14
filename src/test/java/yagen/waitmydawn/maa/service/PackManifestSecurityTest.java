package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class PackManifestSecurityTest {
    @Test
    void snapshotsAreAccountAndEnvironmentBoundAndCannotBeMutatedByCallers() {
        PackManifestService service = new PackManifestService();
        ObjectNode file = new ObjectMapper().createObjectNode().put("filename", "fixture.jar");
        String id = service.register(List.of("project"), List.of(file), "fabric", "1.21.1", 1L);
        file.put("filename", "changed.jar");
        assertNull(service.filesFor(id, null, "fabric", "1.21.1", 2L));
        assertThrows(IllegalArgumentException.class, () -> service.filesFor(id, null, "forge", "1.21.1", 1L));
        assertThrows(IllegalArgumentException.class, () -> service.filesFor(id, List.of("unknown"), "fabric", "1.21.1", 1L));
        var result = service.filesFor(id, List.of("project"), "fabric", "1.21.1", 1L);
        assertEquals("fixture.jar", result.getFirst().path("filename").asText());
        ((ObjectNode) result.getFirst()).put("filename", "mutated.jar");
        assertEquals("fixture.jar", service.filesFor(id, null, "fabric", "1.21.1", 1L).getFirst().path("filename").asText());
    }
}
