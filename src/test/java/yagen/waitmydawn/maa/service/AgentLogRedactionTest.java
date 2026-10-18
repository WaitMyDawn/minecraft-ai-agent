package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentLogRedactionTest {
    @Test void captureNeedsItsOwnFlagAndResolvedNonProductionEvaluationProfile() {
        var service = new AiAgentService();
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active", "eval");
        org.springframework.test.util.ReflectionTestUtils.setField(service, "environment", environment);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "captureAgentIo", true);
        for (String[] profiles : java.util.List.of(new String[]{"test"}, new String[]{"eval", "prod"}, new String[]{"eval", "production"})) {
            environment.setActiveProfiles(profiles);
            assertFalse((boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "capturingAgentIo"));
        }
        environment.setActiveProfiles("eval");
        assertTrue((boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "capturingAgentIo"));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "captureAgentIo", false);
        assertFalse((boolean) org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "capturingAgentIo"));
    }
    @Test void modelIoRedactsCredentialPatternsWithoutRemovingProjectReferences() {
        String result = AiAgentService.redact("key=sk-fixture-12345678 Bearer fixture.token.value irons-spells-n-spellbooks");
        assertFalse(result.contains("sk-fixture"));
        assertFalse(result.contains("fixture.token.value"));
        assertTrue(result.contains("irons-spells-n-spellbooks"));
        assertTrue(result.contains("REDACTED"));
    }
}
