package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.service.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModpackExportClosureTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ModpackController controller;
    private ModrinthApiClient api;
    private DependencyEngine engine;
    private String manifestId;
    @BeforeEach void setup() {
        var graph = new DependencyGraph();
        graph.allSlugs.addAll(Set.of("root", "library"));
        graph.addEdge("root", "library");
        var root = mapper.createObjectNode().put("id", "va").put("project_id", "A");
        root.putArray("dependencies").addObject().put("dependency_type", "required").put("project_id", "B").put("version_id", "vb-old");
        var library = mapper.createObjectNode().put("id", "vb").put("project_id", "B");
        library.putArray("dependencies");
        graph.selectedVersions.put("root", root);
        graph.selectedVersions.put("library", library);
        var manifests = new PackManifestService();
        manifestId = manifests.register(List.of("A", "B"), List.of(file("a.jar"), file("b.jar")), "neoforge", "1.21.1", 1L,
                Map.of("A", "root", "B", "library"), DependencyClosure.capture(graph, List.of(), Map.of()));
        api = mock(ModrinthApiClient.class);
        engine = mock(DependencyEngine.class);
        var users = mock(UserController.class);
        when(users.validateToken("one")).thenReturn(1L);
        when(users.validateToken("two")).thenReturn(2L);
        var versions = mock(LoaderVersionService.class);
        when(versions.dependencyKey("neoforge")).thenReturn("neoforge");
        when(versions.resolve("neoforge", "1.21.1")).thenReturn("21.1.1");
        controller = new ModpackController(engine, api, mapper, mock(KnowledgeDb.class), null, null, versions, manifests, users);
    }
    private JsonNode file(String filename) {
        var file = mapper.createObjectNode().put("filename", filename).put("url", "https://cdn.modrinth.com/data/fixture/" + filename).put("size", 1);
        file.putObject("hashes").put("sha1", "0".repeat(40)).put("sha512", "0".repeat(128));
        return file;
    }
    private ModpackController.BuildRequest request(List<String> ids) {
        var request = new ModpackController.BuildRequest();
        request.name = "closure-fixture"; request.loader = "neoforge"; request.mcVersion = "1.21.1";
        request.manifestId = manifestId; request.selectedIds = ids;
        return request;
    }
    private Map<String, JsonNode> archive(byte[] bytes) throws Exception {
        Map<String, JsonNode> entries = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) entries.put(entry.getName(), mapper.readTree(zip.readAllBytes()));
        }
        return entries;
    }
    @Test void omittedRequiredFileReturnsStructuredConflictWithoutNetworkRequests() {
        var result = controller.buildPack(request(List.of("A")), "one");
        assertEquals(409, result.getStatusCode().value());
        var diagnostic = mapper.readTree(result.getBody());
        assertTrue(diagnostic.path("canExportIncomplete").asBoolean());
        assertEquals("INCOMPLETE", diagnostic.path("closure").path("status").asText());
        verifyNoInteractions(api, engine);
    }
    @Test void deliberateIncompleteExportPreservesSelectionAndIncludesDiagnostics() throws Exception {
        var request = request(List.of("A")); request.allowIncomplete = true;
        var result = controller.buildPack(request, "one");
        assertEquals(200, result.getStatusCode().value());
        var entries = archive(result.getBody());
        assertEquals(1, entries.get("modrinth.index.json").path("files").size());
        assertEquals("INCOMPLETE", entries.get("maa-diagnostics.json").path("closure").path("status").asText());
        assertFalse(entries.get("maa-diagnostics.json").path("runtimeChecked").asBoolean(true));
        verifyNoInteractions(api, engine);
    }
    @Test void closedSelectionExportsBothFilesAndOverrideCannotBypassSnapshotOwnership() throws Exception {
        var result = controller.buildPack(request(List.of("A", "B")), "one");
        assertEquals(200, result.getStatusCode().value());
        var entries = archive(result.getBody());
        assertEquals(2, entries.get("modrinth.index.json").path("files").size());
        assertEquals("COMPLETE", entries.get("maa-diagnostics.json").path("closure").path("status").asText());
        var issue = entries.get("maa-diagnostics.json").path("closure").path("issues").get(0);
        assertEquals("VERSION_MISMATCH", issue.path("code").asText());
        assertEquals("WARNING", issue.path("severity").asText());
        var request = request(List.of("A")); request.allowIncomplete = true;
        assertEquals(400, controller.buildPack(request, "two").getStatusCode().value());
        request.selectedIds = List.of("outside");
        assertEquals(400, controller.buildPack(request, "one").getStatusCode().value());
        verifyNoInteractions(api, engine);
    }
}
