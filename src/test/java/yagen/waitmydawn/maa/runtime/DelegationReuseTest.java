package yagen.waitmydawn.maa.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class DelegationReuseTest {
    @Test void explicitAbsenceIsNotOverwrittenByASuccessfulAlias() throws Exception {
        var ctx = new DelegationContext();
        deliver(ctx, DelegationContext.Kind.ENV_VERSION, "P1" + ENV, null);
        deliver(ctx, DelegationContext.Kind.PROJECT, "test-mod", project());
        deliver(ctx, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, version("V1", "P1"));
        assertEquals(DelegationContext.Outcome.ABSENT, ctx.await(DelegationContext.Kind.ENV_VERSION, "P1" + ENV, 200).outcome());
    }
    private final ObjectMapper json = new ObjectMapper();
    private static final String ENV = "|1.21.1|[\"neoforge\"]";
    private JsonNode project() { return json.createObjectNode().put("id", "P1").put("slug", "test-mod"); }
    private JsonNode version(String id, String project) {
        return json.createObjectNode().put("id", id).put("project_id", project);
    }
    private DelegationContext.Answer deliver(DelegationContext ctx, DelegationContext.Kind kind, String key, JsonNode data) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> ctx.await(kind, key, 2000));
            for (int i = 0; i < 400 && !ctx.isPending(kind, key) && !future.isDone(); i++) Thread.sleep(2);
            assertTrue(ctx.isPending(kind, key), "必须登记新需求，不能误用其它事实");
            ctx.deliver(kind, key, data);
            return future.get(2, TimeUnit.SECONDS);
        }
    }

    @Test void projectAliasesAndLatestVersionReuseStayInsideOneTask() throws Exception {
        var ctx = new DelegationContext();
        deliver(ctx, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, version("V1", "P1"));
        deliver(ctx, DelegationContext.Kind.PROJECT, "test-mod", project());
        assertEquals("P1", ctx.await(DelegationContext.Kind.PROJECT, "P1", 200).data().path("id").asText());
        assertEquals("V1", ctx.await(DelegationContext.Kind.ENV_VERSION, "P1" + ENV, 200).data().path("id").asText());
        assertEquals(2, ctx.factReuseCount());
        assertFalse(ctx.hasPending());
    }

    @Test void successfulVersionListCanAnswerLatestAcrossVerifiedAliasesWithoutLosingOtherCandidates() throws Exception {
        var ctx = new DelegationContext();
        deliver(ctx, DelegationContext.Kind.PROJECT, "P1", project());
        var versions = json.createArrayNode().add(version("V2", "P1")).add(version("V1", "P1"));
        deliver(ctx, DelegationContext.Kind.ENV_VERSIONS, "P1" + ENV, versions);
        assertEquals("V2", ctx.await(DelegationContext.Kind.ENV_VERSION, "P1" + ENV, 200).data().path("id").asText());
        assertEquals("V2", ctx.await(DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, 200).data().path("id").asText());
        assertEquals(versions, ctx.await(DelegationContext.Kind.ENV_VERSIONS, "test-mod" + ENV, 200).data());
        assertEquals(3, ctx.factReuseCount());
    }

    @Test void latestCannotInventACompatibleVersionListAndEnvironmentsStaySeparate() throws Exception {
        var ctx = new DelegationContext();
        deliver(ctx, DelegationContext.Kind.PROJECT, "P1", project());
        deliver(ctx, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, version("V2", "P1"));
        var versions = json.createArrayNode().add(version("V2", "P1")).add(version("V1", "P1"));
        assertEquals(versions, deliver(ctx, DelegationContext.Kind.ENV_VERSIONS, "P1" + ENV, versions).data());
        assertEquals(DelegationContext.Outcome.ABSENT, deliver(ctx, DelegationContext.Kind.ENV_VERSION,
                "P1|1.20.1|[\"neoforge\"]", null).outcome());
        assertEquals(DelegationContext.Outcome.ABSENT, deliver(ctx, DelegationContext.Kind.ENV_VERSION,
                "P1|1.21.1|[\"forge\"]", null).outcome());
        assertEquals(0, ctx.factReuseCount());
    }

    @Test void absentEmptyAndUnknownOrConflictingProjectIdentitiesDoNotBecomeAliasFacts() throws Exception {
        var ctx = new DelegationContext();
        deliver(ctx, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, version("Wrong", "P2"));
        deliver(ctx, DelegationContext.Kind.PROJECT, "P1", project());
        assertEquals("Right", deliver(ctx, DelegationContext.Kind.ENV_VERSION, "P1" + ENV, version("Right", "P1")).data().path("id").asText());
        var empty = new DelegationContext();
        deliver(empty, DelegationContext.Kind.PROJECT, "test-mod", project());
        deliver(empty, DelegationContext.Kind.ENV_VERSIONS, "test-mod" + ENV, json.createArrayNode());
        assertEquals(DelegationContext.Outcome.ABSENT, deliver(empty, DelegationContext.Kind.ENV_VERSION, "P1" + ENV, null).outcome());
        assertEquals(DelegationContext.Outcome.ABSENT, deliver(empty, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, null).outcome());
        var unknown = new DelegationContext();
        deliver(unknown, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, version("V1", "P1"));
        deliver(unknown, DelegationContext.Kind.ENV_VERSION, "P1" + ENV, version("V1", "P1"));
        assertEquals(0, unknown.factReuseCount());
    }

    @Test void lateUnsolicitedFactsCancellationAndOtherTasksCannotUseDerivedAliases() throws Exception {
        var ctx = new DelegationContext();
        ctx.deliver(DelegationContext.Kind.PROJECT, "P1", project());
        deliver(ctx, DelegationContext.Kind.PROJECT, "test-mod", project());
        deliver(ctx, DelegationContext.Kind.ENV_VERSION, "test-mod" + ENV, version("V1", "P1"));
        var other = new DelegationContext();
        deliver(other, DelegationContext.Kind.ENV_VERSION, "P1" + ENV, version("V2", "P1"));
        assertEquals(0, other.factReuseCount());
        ctx.releaseFacts();
        assertEquals(DelegationContext.Outcome.CANCELLED, ctx.await(DelegationContext.Kind.ENV_VERSION, "P1" + ENV, 200).outcome());
        assertFalse(ctx.hasPending());
    }
}
