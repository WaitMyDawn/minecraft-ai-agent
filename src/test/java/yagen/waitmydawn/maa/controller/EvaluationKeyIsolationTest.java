package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EvaluationKeyIsolationTest {
    @Test
    void productionCannotUseSystemOrHeaderTestKeys() {
        var users = mock(UserController.class);
        ChatController controller = mock(ChatController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "userController", users);
        ReflectionTestUtils.setField(controller, "systemDefaultApiKey", "fixture-system");
        ReflectionTestUtils.setField(controller, "allowKeyOverride", true);
        var environment = new org.springframework.mock.env.MockEnvironment();
        ReflectionTestUtils.setField(controller, "environment", environment);
        environment.setActiveProfiles("prod");
        assertNull(ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        when(users.getUserApiKey("fixture-token")).thenReturn("fixture-personal");
        assertEquals("fixture-personal", ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        when(users.getUserApiKey("fixture-token")).thenReturn(null);
        environment.setActiveProfiles("prod", "eval");
        assertNull(ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        environment.setActiveProfiles("eval");
        assertEquals("fixture-header", ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        assertEquals("fixture-system", ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", ""));
        ReflectionTestUtils.setField(controller, "allowKeyOverride", false);
        assertNull(ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
    }

    @Test void resolvedProfilesOverrideMisleadingPropertyStringsAndMissingEnvironmentDeniesEvaluation() {
        var controller = mock(ChatController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "allowKeyOverride", true);
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "evaluationKeysAllowed"));
        var environment = new org.springframework.mock.env.MockEnvironment().withProperty("spring.profiles.active", "eval");
        environment.setActiveProfiles("eval", "production");
        ReflectionTestUtils.setField(controller, "environment", environment);
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "evaluationKeysAllowed"));
        environment.setActiveProfiles("eval", "PROD");
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "evaluationKeysAllowed"));
        environment.setActiveProfiles("test");
        assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "evaluationKeysAllowed"));
    }

    @Test void criticFaultInjectionRequiresEvaluationAndCannotRunInProduction() {
        var controller = mock(ChatController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "forceCriticDegraded", true);
        var environment = new org.springframework.mock.env.MockEnvironment();
        ReflectionTestUtils.setField(controller, "environment", environment);
        for (String[] profiles : java.util.List.of(new String[]{}, new String[]{"prod", "eval"}, new String[]{"production", "eval"})) {
            environment.setActiveProfiles(profiles);
            assertFalse((boolean) ReflectionTestUtils.invokeMethod(controller, "criticDegradationForced"));
        }
        environment.setActiveProfiles("eval");
        assertTrue((boolean) ReflectionTestUtils.invokeMethod(controller, "criticDegradationForced"));
    }
}
