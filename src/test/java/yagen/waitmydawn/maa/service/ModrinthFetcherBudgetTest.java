package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.http.*;
import org.springframework.web.client.*;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ModrinthFetcherBudgetTest {
    private final ModrinthHttpTransport transport = mock(ModrinthHttpTransport.class);
    private final ModrinthThrottle throttle = mock(ModrinthThrottle.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private ModrinthFetcher fetcher(long millis) {
        when(throttle.acquire(anyLong())).thenReturn(true);
        return new ModrinthFetcher(transport, throttle, new ConcurrentMapCacheManager(), false, millis, 2);
    }
    @Test void failuresRetryOnlyOnceAndNeverBecomeAbsent() {
        when(transport.get(any(), anyLong(), anyBoolean())).thenThrow(new ResourceAccessException("fixture network failure"));
        assertThrows(ModrinthApiClient.UnavailableException.class, () -> fetcher(2500).getProjectInfo("jade"));
        verify(transport, times(2)).get(any(), anyLong(), anyBoolean());
        verify(transport).get(any(), anyLong(), eq(true));
    }
    @Test void long429WindowIsPublishedButCannotConsumeMoreThanRemainingBudget() {
        var headers = new HttpHeaders(); headers.set("X-Ratelimit-Reset", "60");
        when(transport.get(any(), anyLong(), anyBoolean())).thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS,
                "limited", headers, new byte[0], StandardCharsets.UTF_8));
        long started = System.nanoTime();
        assertThrows(ModrinthApiClient.UnavailableException.class, () -> fetcher(300).getProjectInfo("jade"));
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1000);
        verify(throttle).deferSeconds(60);
        verify(transport, times(1)).get(any(), anyLong(), anyBoolean());
    }
    @Test void explicit404StillReturnsAbsentWithoutRetry() {
        when(transport.get(any(), anyLong(), anyBoolean())).thenThrow(HttpClientErrorException.create(HttpStatus.NOT_FOUND,
                "missing", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
        assertNull(fetcher(500).getProjectInfo("ghost"));
        verify(transport, times(1)).get(any(), anyLong(), anyBoolean());
    }
    @Test void batchFallbackPreservesParenthesesInsteadOfQueryingDifferentProject() {
        when(transport.get(any(), anyLong(), anyBoolean())).thenAnswer(call -> {
            URI uri = call.getArgument(0);
            assertTrue(uri.getQuery().contains("alexs-caves-(unofficial-port)"), uri.toString());
            assertFalse(uri.toString().contains("%255B"));
            return mapper.createArrayNode();
        });
        when(throttle.acquire(anyLong())).thenReturn(true);
        new ModrinthFetcher(transport, throttle, new ConcurrentMapCacheManager(), true, 1000, 2)
                .prefetchProjects(java.util.List.of("alexs-caves-(unofficial-port)"));
        verify(transport, times(1)).get(any(), anyLong(), anyBoolean());
    }
    @Test void wrongArrayShapeForProjectIsNotEvidenceOfAbsence() {
        when(transport.get(any(), anyLong(), anyBoolean())).thenReturn(mapper.createArrayNode());
        assertThrows(ModrinthApiClient.UnavailableException.class, () -> fetcher(2500).getProjectInfo("jade"));
        verify(transport, times(2)).get(any(), anyLong(), anyBoolean());
    }
    @Test void slugAndProjectIdFallbackShareDeadlineAndPreserveUrlEncoding() {
        when(transport.get(any(), anyLong(), anyBoolean())).thenAnswer(call -> {
            URI uri = call.getArgument(0);
            if (uri.getPath().endsWith("/version")) {
                assertFalse(uri.toString().contains("%255B"));
                assertTrue(uri.toString().contains("%5B"));
                Thread.sleep(180);
                return mapper.createArrayNode();
            }
            assertTrue((long)call.getArgument(1) < 200);
            Thread.sleep(100);
            return mapper.createObjectNode().put("id", "project-id").put("slug", "jade");
        });
        assertThrows(ModrinthApiClient.UnavailableException.class,
                () -> fetcher(250).getLatestCompatibleVersionBySlug("jade", "1.21.1", "neoforge"));
        verify(transport, times(2)).get(any(), anyLong(), anyBoolean());
    }
}
