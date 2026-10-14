package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.service.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BrowserOperationsControllerTest {
    @Test
    void previewRequestsBrowserFactsValidatesOwnershipAndReturnsOriginalJson() {
        var mapper = new ObjectMapper();
        var users = mock(UserController.class);
        when(users.validateToken("one")).thenReturn(1L);
        when(users.validateToken("two")).thenReturn(2L);
        var fetcher = mock(ModrinthFetcher.class);
        var config = new DelegationTasks(true, 180000, 1, 128);
        var api = new ModrinthApiClient(fetcher, config);
        var packs = mock(ModpackController.class);
        var chat = mock(ChatController.class);
        doCallRealMethod().when(chat).acceptTaskFacts(any(), any());
        var request = new ModpackController.ModpackRequest();
        when(packs.previewPack("one", request)).thenAnswer(call -> ResponseEntity.ok(api.getProjectInfo("create")));
        var operations = new BrowserOperationsController(packs, mock(PreferencesController.class), chat, users,
                new MrpackParser(api), mapper, true, 180000, 1, 128);
        try {
            JsonNode envelope = mapper.valueToTree(operations.preview("one", request).getBody());
            assertEquals("need-data", envelope.path("stage").asText());
            String id = envelope.path("taskId").asText();
            assertEquals(404, assertThrows(ResponseStatusException.class, () -> operations.poll(id, "two")).getStatusCode().value());
            var wrong = mapper.readTree("{\"answered\":[{\"kind\":\"project\",\"key\":\"not-requested\",\"data\":{\"slug\":\"wrong\"}}]}");
            var inFlight = mapper.valueToTree(operations.facts(id, "one", wrong).getBody());
            assertEquals("running", inFlight.path("stage").asText());
            assertTrue(inFlight.path("wanted").isEmpty());
            var facts = mapper.readTree("{\"answered\":[{\"kind\":\"project\",\"key\":\"create\",\"data\":{\"id\":\"P1\",\"slug\":\"create\"}}]}");
            var result = operations.facts(id, "one", facts);
            assertEquals(200, result.getStatusCode().value());
            assertEquals("create", mapper.valueToTree(result.getBody()).path("slug").asText());
            assertEquals(200, operations.poll(id, "one").getStatusCode().value());
            verifyNoInteractions(fetcher);
        } finally { operations.close(); config.close(); }
    }
}
