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
        ReflectionTestUtils.setField(controller, "activeProfiles", "prod");
        assertNull(ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        when(users.getUserApiKey("fixture-token")).thenReturn("fixture-personal");
        assertEquals("fixture-personal", ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        when(users.getUserApiKey("fixture-token")).thenReturn(null);
        ReflectionTestUtils.setField(controller, "activeProfiles", "prod,eval");
        assertNull(ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        ReflectionTestUtils.setField(controller, "activeProfiles", "eval");
        assertEquals("fixture-header", ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
        assertEquals("fixture-system", ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", ""));
        ReflectionTestUtils.setField(controller, "allowKeyOverride", false);
        assertNull(ReflectionTestUtils.invokeMethod(controller, "effectiveKey", "fixture-token", "fixture-header"));
    }
}
