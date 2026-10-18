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

    @Test void wrongServerSecretAndMalformedCiphertextFailWithoutReturningPlaintext() {
        UserController first = controller();
        String encrypted = ReflectionTestUtils.invokeMethod(first, "encrypt", "sk-offline-fixture-key");
        UserController wrong = controller(); ReflectionTestUtils.setField(wrong, "encryptionSecret", "another-offline-fixture-secret");
        assertNull(ReflectionTestUtils.invokeMethod(wrong, "decrypt", encrypted));
        for (String invalid : new String[]{"gcm1:!invalid", "gcm1:YWJj", "!legacy", "", " "})
            assertNull(ReflectionTestUtils.invokeMethod(first, "decrypt", invalid));
    }

    @Test void invalidEncryptionConfigurationPreventsSpringBeanStartupWithoutEchoingTheValue() {
        for (String secret : new String[]{"", " ", "replace-with-openssl-rand-base64-32"}) {
            try (var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
                context.registerBean(UserController.class, () -> new UserController(mock(yagen.waitmydawn.maa.model.UserRepository.class),
                        mock(yagen.waitmydawn.maa.model.ConversationRepository.class), mock(yagen.waitmydawn.maa.model.ChatMessageRepository.class),
                        secret, false, 30, mock(yagen.waitmydawn.maa.service.PasswordHasher.class),
                        new yagen.waitmydawn.maa.service.PasswordPolicy(), mock(yagen.waitmydawn.maa.service.EmailCodeService.class),
                        mock(yagen.waitmydawn.maa.service.MailService.class)));
                var failure = assertThrows(org.springframework.beans.factory.BeanCreationException.class, context::refresh);
                assertTrue(failure.getMostSpecificCause().getMessage().contains("MAA_ENCRYPTION_SECRET"));
                assertFalse(failure.getMostSpecificCause().getMessage().contains("replace-with-openssl"));
            }
        }
    }

    @Test void readingLegacyCiphertextUpgradesOnlyTheAuthenticatedAccountsExpectedValue() throws Exception {
        var controller = controller();
        var sessions = new yagen.waitmydawn.maa.runtime.SessionRegistry(60000);
        String token = sessions.issue(7L);
        var repository = mock(yagen.waitmydawn.maa.model.UserRepository.class);
        ReflectionTestUtils.setField(controller, "sessions", sessions);
        ReflectionTestUtils.setField(controller, "userRepo", repository);
        Cipher legacy = Cipher.getInstance("AES");
        byte[] key = MessageDigest.getInstance("SHA-256").digest("offline-fixture-secret".getBytes(StandardCharsets.UTF_8));
        legacy.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        String old = Base64.getEncoder().encodeToString(legacy.doFinal("sk-offline-fixture-key".getBytes(StandardCharsets.UTF_8)));
        var user = new yagen.waitmydawn.maa.model.User(); user.setEncryptedApiKey(old);
        when(repository.findById(7L)).thenReturn(java.util.Optional.of(user));
        when(repository.upgradeApiKeyCiphertext(eq(7L), eq(old), anyString())).thenAnswer(call -> {
            String replacement = call.getArgument(2); assertTrue(replacement.startsWith("gcm1:"));
            assertEquals("sk-offline-fixture-key", ReflectionTestUtils.invokeMethod(controller, "decrypt", replacement));
            return 1;
        });
        assertEquals("sk-offline-fixture-key", controller.getUserApiKey(token));
        verify(repository).upgradeApiKeyCiphertext(eq(7L), eq(old), anyString());
    }
}
