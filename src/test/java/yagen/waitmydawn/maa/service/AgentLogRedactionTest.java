package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentLogRedactionTest {
    @Test void modelIoRedactsCredentialPatternsWithoutRemovingProjectReferences() {
        String result = AiAgentService.redact("key=sk-fixture-12345678 Bearer fixture.token.value irons-spells-n-spellbooks");
        assertFalse(result.contains("sk-fixture"));
        assertFalse(result.contains("fixture.token.value"));
        assertTrue(result.contains("irons-spells-n-spellbooks"));
        assertTrue(result.contains("REDACTED"));
    }
}
