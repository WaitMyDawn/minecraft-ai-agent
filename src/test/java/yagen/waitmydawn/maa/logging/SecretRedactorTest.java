package yagen.waitmydawn.maa.logging;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.io.Writer;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SecretRedactorTest {
    @Test void credentialPatternsAreRemovedWhileProjectAndErrorContextRemain() {
        for (String text : new String[]{"Authorization: Bearer opaque.fixture.token==", "X-Auth-Token=opaque-fixture-token",
                "{\"apiKey\":\"opaque-fixture-key\"}", "MAA_ENCRYPTION_SECRET=opaque-fixture-secret",
                "gcm1:YWJjZGVm", "sk-offline-fixture-key"}) {
            String redacted = SecretRedactor.redact(text);
            assertFalse(redacted.contains("opaque-fixture")); assertFalse(redacted.contains("opaque.fixture"));
            assertFalse(redacted.contains("YWJjZGVm")); assertFalse(redacted.contains("sk-offline"));
            assertTrue(redacted.contains("REDACTED"));
        }
        assertNull(SecretRedactor.redact(null));
        assertEquals("irons-spells-n-spellbooks HTTP 429", SecretRedactor.redact("irons-spells-n-spellbooks HTTP 429"));
    }

    @Test @SuppressWarnings("unchecked")
    void appUserAndExceptionFilesApplyTheSameRedaction(@TempDir Path directory) throws Exception {
        Class<?> type = Class.forName("yagen.waitmydawn.maa.logging.MaaLog$Core");
        var constructor = type.getDeclaredConstructor(Path.class, int.class); constructor.setAccessible(true);
        Object testCore = constructor.newInstance(directory, 14);
        Object previous = ReflectionTestUtils.getField(MaaLog.class, "core");
        String priorUser = MaaLog.userKey();
        try {
            ReflectionTestUtils.setField(MaaLog.class, "core", testCore);
            MaaLog.setUserKey("fixture-user");
            MaaLog.user("key sk-offline-fixture-key");
            MaaLog.app("X-Auth-Token=opaque-fixture-token");
            MaaLog.error("upstream failure", new IllegalStateException("Authorization: Bearer opaque.fixture.token=="));
            try (var paths = Files.walk(directory)) {
                var files = paths.filter(Files::isRegularFile).toList(); assertEquals(2, files.size());
                for (Path path : files) {
                    String text = Files.readString(path);
                    assertFalse(text.contains("sk-offline")); assertFalse(text.contains("opaque-fixture"));
                    assertFalse(text.contains("opaque.fixture")); assertTrue(text.contains("REDACTED"));
                }
            }
        } finally {
            var writers = (Map<String, Writer>) ReflectionTestUtils.getField(testCore, "openWriters");
            for (Writer writer : writers.values()) writer.close();
            ReflectionTestUtils.setField(MaaLog.class, "core", previous);
            if (priorUser == null) MaaLog.clearUserKey(); else MaaLog.setUserKey(priorUser);
        }
    }
}
