package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class PlanningClarificationsTest {
    private final PlanningClarifications pending = new PlanningClarifications();
    private final Map<String, String> original = Map.of("prompt", "我想玩一个冒险整合包", "uuid", "fixture",
            "loader", "neoforge", "mcVersion", "1.21.1", "assembly", "snapshot-a");
    private Map<String, String> reply(String text) { var fields = new HashMap<>(original); fields.put("prompt", text); return fields; }
    private String ticket() { return pending.put(1L, 2L, original, "建议 neoforge 1.21.1，80～120 个模组，是否确认？"); }

    @Test void confirmationPreservesOriginalThemeAndConsumesOnlyOnce() {
        String ticket = ticket();
        var merged = pending.admit(ticket, 1L, 2L, reply("好的"), fields -> fields);
        assertTrue(merged.get("prompt").contains(original.get("prompt")));
        assertFalse(merged.get("prompt").contains("80～120"));
        assertTrue(merged.get("clarificationQuestion").contains("80～120"));
        assertTrue(merged.get("prompt").endsWith("用户答复：好的"));
        assertEquals(original.get("assembly"), merged.get("assembly"));
        assertThrows(IllegalArgumentException.class, () -> pending.admit(ticket, 1L, 2L, reply("好的"), fields -> fields));
    }
    @Test void queueRejectionPreservesTicketWithoutExecutingOrLosingContext() {
        String ticket = ticket();
        assertThrows(IllegalStateException.class, () -> pending.admit(ticket, 1L, 2L, reply("好的"), fields -> { throw new IllegalStateException("queue full"); }));
        assertTrue(pending.admit(ticket, 1L, 2L, reply("好的"), fields -> fields.get("prompt")).contains("冒险"));
    }
    @Test void ownerConversationSessionAndVersionSnapshotAreBound() {
        String ticket = ticket();
        assertThrows(IllegalArgumentException.class, () -> pending.admit(ticket, 3L, 2L, reply("好的"), fields -> fields));
        assertThrows(IllegalArgumentException.class, () -> pending.admit(ticket, 1L, 3L, reply("好的"), fields -> fields));
        for (String field : List.of("uuid", "assembly", "mcVersion", "loader", "currentMods", "constraints", "excludedSlugs")) {
            var changed = reply("好的"); changed.put(field, "changed");
            assertThrows(IllegalArgumentException.class, () -> pending.admit(ticket, 1L, 2L, changed, fields -> fields), field);
        }
        assertNotNull(pending.admit(ticket, 1L, 2L, reply("好的"), fields -> fields));
    }
    @Test void expirationDoesNotSubmitAndCancelDoesNotInheritOldTheme() {
        var clock = new AtomicLong();
        var expiring = new PlanningClarifications(clock::get);
        String ticket = expiring.put(1L, null, original, "question");
        clock.set(Duration.ofMinutes(31).toNanos());
        assertThrows(IllegalArgumentException.class, () -> expiring.admit(ticket, 1L, null, reply("好的"), fields -> fail("expired must not submit")));
        for (String text : List.of("取消构建", "重新开始：做一个科技包")) {
            String active = ticket();
            assertEquals(text, pending.admit(active, 1L, 2L, reply(text), fields -> fields.get("prompt")));
        }
    }
    @Test void pendingSlotsAreBoundedAndNewQuestionReplacesPriorOwnerTicket() {
        String old = ticket();
        String next = ticket();
        assertThrows(IllegalArgumentException.class, () -> pending.admit(old, 1L, 2L, reply("好的"), fields -> fields));
        for (long owner = 2; owner <= 256; owner++) pending.put(owner, 2L, original, "question");
        assertThrows(IllegalArgumentException.class, () -> pending.put(257L, 2L, original, "question"));
        assertNotNull(pending.admit(next, 1L, 2L, reply("好的"), fields -> fields));
    }
}
