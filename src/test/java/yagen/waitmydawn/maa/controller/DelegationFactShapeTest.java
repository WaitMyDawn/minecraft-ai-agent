package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.runtime.DelegationContext;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DelegationFactShapeTest {
    @Test void compatibleVersionArrayIsAcceptedByTheActualReceiptEntryPoint() throws Exception {
        var json = new ObjectMapper();
        var ctx = new DelegationContext();
        var controller = mock(ChatController.class, CALLS_REAL_METHODS);
        String key = "project|1.21.1|[\"neoforge\"]";
        var body = json.createObjectNode();
        var data = body.putArray("answered").addObject().put("kind", "env_versions").put("key", key).putArray("data");
        var version = data.addObject().put("id", "V1");
        version.putArray("game_versions").add("1.21.1"); version.putArray("loaders").add("neoforge");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> ctx.await(DelegationContext.Kind.ENV_VERSIONS, key, 10_000));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) Thread.sleep(5);
            assertTrue(ctx.hasPending());
            controller.acceptTaskFacts(ctx, body);
            assertEquals(data, pending.get(1, TimeUnit.SECONDS).data());
            assertEquals(1, ctx.servedCount()); assertEquals(0, ctx.handBackCount());
        }
    }
    @Test void compatibleCandidatesAreBoundedDistinctAndAllMatchTheRequestedEnvironment() {
        var json = new ObjectMapper();
        var v = json.createObjectNode().put("id", "V1"); v.putArray("game_versions").add("1.21.1"); v.putArray("loaders").add("neoforge");
        var array = json.createArrayNode().add(v);
        String key = "project|1.21.1|[\"neoforge\"]";
        assertTrue(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSIONS, key, array));
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSIONS, key, json.createArrayNode().add(v).add(v)));
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSIONS, "project|1.20.1|[\"neoforge\"]", array));
        var oversized = json.createArrayNode();
        for (int i = 0; i < 5; i++) oversized.add(v.deepCopy().put("id", "V" + i));
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSIONS, key, oversized));
    }
    @Test
    void projectHistoryMayExceedSingleVersionEnvironmentLimit() {
        var data = new ObjectMapper().createObjectNode().put("id", "P7dR8mSH");
        var versions = data.putArray("game_versions");
        for (int i = 0; i < 300; i++) versions.add("snapshot-" + i);
        assertTrue(ChatController.selfConsistent(DelegationContext.Kind.PROJECT, "P7dR8mSH", data));
        assertTrue(ChatController.selfConsistent(DelegationContext.Kind.VERSION, "P7dR8mSH", data));
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.PROJECT, "different", data));
    }

    @Test void broadVersionSupportStillRequiresTheRequestedEnvironment() {
        var data = new ObjectMapper().createObjectNode().put("id", "xFTfXEwk");
        var versions = data.putArray("game_versions").add("1.21.1");
        for (int i = 0; i < 400; i++) versions.add("snapshot-" + i);
        data.putArray("loaders").add("neoforge");
        assertTrue(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSION,
                "zrzYrlm0|1.21.1|[\"neoforge\"]", data));
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSION,
                "zrzYrlm0|1.20.1|[\"neoforge\"]", data));
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.ENV_VERSION,
                "zrzYrlm0|1.21.1|[\"forge\"]", data));
        for (int i = 401; i <= 2048; i++) versions.add("snapshot-" + i);
        assertFalse(ChatController.selfConsistent(DelegationContext.Kind.VERSION, "xFTfXEwk", data));
    }

    @Test void rejectedShapeEndsPendingFetchWithoutReportingAbsence() throws Exception {
        var mapper = new ObjectMapper();
        var ctx = new DelegationContext();
        var controller = mock(ChatController.class, CALLS_REAL_METHODS);
        var body = mapper.readTree("""
                {"answered":[{"kind":"version","key":"wanted","data":{"id":"wrong"}}]}
                """);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> ctx.await(DelegationContext.Kind.VERSION, "wanted", 10_000));
            for (int i = 0; i < 400 && !ctx.hasPending(); i++) Thread.sleep(5);
            assertTrue(ctx.hasPending());
            controller.acceptTaskFacts(ctx, body);
            assertEquals(DelegationContext.Outcome.TIMEOUT, pending.get(1, TimeUnit.SECONDS).outcome());
            assertFalse(ctx.hasPending());
            assertTrue(ctx.pendingWants().isEmpty());
            assertEquals(1, ctx.timedOutCount());
            controller.acceptTaskFacts(ctx, body);
            assertEquals(1, ctx.timedOutCount());
            assertEquals(0, ctx.servedCount());
        }
    }
}
