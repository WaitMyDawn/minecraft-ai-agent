package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ApiKeyEncryptionTest {
    private UserController controller() {
        UserController controller = mock(UserController.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(controller, "encryptionSecret", "offline-fixture-secret");
        return controller;
    }

    @Test
    void randomizedAuthenticatedCiphertextRejectsTampering() {
        UserController controller = controller();
        String first = ReflectionTestUtils.invokeMethod(controller, "encrypt", "fixture-key");
        String second = ReflectionTestUtils.invokeMethod(controller, "encrypt", "fixture-key");
        assertTrue(first.startsWith("gcm1:"));
        assertNotEquals(first, second);
        assertEquals("fixture-key", ReflectionTestUtils.invokeMethod(controller, "decrypt", first));
        byte[] bytes = Base64.getDecoder().decode(first.substring(5));
        bytes[bytes.length - 1] ^= 1;
        assertNull(ReflectionTestUtils.invokeMethod(controller, "decrypt", "gcm1:" + Base64.getEncoder().encodeToString(bytes)));
    }

    @Test
    void existingCiphertextRemainsReadable() throws Exception {
        Cipher legacy = Cipher.getInstance("AES");
        byte[] key = MessageDigest.getInstance("SHA-256").digest("offline-fixture-secret".getBytes(StandardCharsets.UTF_8));
        legacy.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        String encrypted = Base64.getEncoder().encodeToString(legacy.doFinal("fixture-key".getBytes(StandardCharsets.UTF_8)));
        assertEquals("fixture-key", ReflectionTestUtils.invokeMethod(controller(), "decrypt", encrypted));
    }
}
