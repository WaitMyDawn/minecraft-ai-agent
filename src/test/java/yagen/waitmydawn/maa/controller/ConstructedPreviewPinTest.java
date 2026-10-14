package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.service.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ConstructedPreviewPinTest {
    @Test void previewRequestsSnapshotPinsAndRejectsAnUnexpectedUpgrade() {
        var engine = mock(DependencyEngine.class); var api = mock(ModrinthApiClient.class);
        var loaderVersions = mock(LoaderVersionService.class); var users = mock(UserController.class);
        when(loaderVersions.supports("neoforge", "1.21.1")).thenReturn(true);
        var controller = new ModpackController(engine, api, new ObjectMapper(), mock(KnowledgeDb.class), null, null,
                loaderVersions, new PackManifestService(), users);
        var request = new ModpackController.ModpackRequest();
        request.loader = "neoforge"; request.mcVersion = "1.21.1"; request.modSlugs = List.of("core");
        request.assembly = new AssemblySnapshot(request.loader, request.mcVersion, request.modSlugs, Map.of("core", "V1"), Map.of("core", Set.of("CORE")));
        var graph = new DependencyGraph(); graph.allSlugs.add("core");
        graph.selectedVersions.put("core", new ObjectMapper().createObjectNode().put("id", "V2"));
        when(engine.resolveCachedResult(anySet(), anyString(), anyString(), any()))
                .thenReturn(new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("core"), List.of(), List.of(), 500, false));
        var response = controller.previewPack("fixture", request);
        assertTrue(response.getStatusCode().is4xxClientError());
        assertTrue(response.getBody().path("error").asText().contains("选版未保留"));
        verify(engine).resolveCachedResult(eq(Set.of("vid:V1")), eq("neoforge"), eq("1.21.1"), any());
        verifyNoInteractions(api);
    }
}
