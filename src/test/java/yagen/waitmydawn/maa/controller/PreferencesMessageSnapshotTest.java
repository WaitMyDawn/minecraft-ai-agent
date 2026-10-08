package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.ChatMessage;
import yagen.waitmydawn.maa.model.ChatMessageRepository;
import yagen.waitmydawn.maa.model.Conversation;
import yagen.waitmydawn.maa.model.ConversationRepository;
import yagen.waitmydawn.maa.model.ModBlacklistRepository;
import yagen.waitmydawn.maa.model.CategoryPreferenceRepository;
import yagen.waitmydawn.maa.model.ModPreferenceRepository;
import yagen.waitmydawn.maa.model.UserRepository;
import yagen.waitmydawn.maa.service.ModrinthApiClient;
import yagen.waitmydawn.maa.service.PackSnapshotHash;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 消息与"版本"的绑定：带打包状态的消息要存下完整状态并回传版本号，历史接口要能读回来。
 *
 * <p>这是"点历史按钮回到那一版"的地基：消息上没有版本号与状态，所有按钮就只能指向最新一版。
 */
class PreferencesMessageSnapshotTest {

    private static final String TOKEN = "tok";
    private static final long UID = 7L;
    private static final long CONV = 1L;

    private ConversationRepository convRepo;
    private ChatMessageRepository msgRepo;
    private PreferencesController controller;

    @BeforeEach
    void setUp() {
        convRepo = mock(ConversationRepository.class);
        msgRepo = mock(ChatMessageRepository.class);
        UserController userController = mock(UserController.class);
        when(userController.validateToken(TOKEN)).thenReturn(UID);

        Conversation conv = new Conversation(UID, "铁魔法冒险纪");
        when(convRepo.findById(anyLong())).thenReturn(Optional.of(conv));

        controller = new PreferencesController(mock(UserRepository.class), userController,
                mock(CategoryPreferenceRepository.class), mock(ModPreferenceRepository.class),
                mock(ModBlacklistRepository.class), convRepo, msgRepo,
                mock(ModrinthApiClient.class), new ObjectMapper());
    }

    private Map<String, Object> body(String modSlugs, boolean withState) {
        Map<String, Object> body = new HashMap<>();
        body.put("role", "ai");
        body.put("content", "已生成");
        body.put("hasData", true);
        body.put("modSlugs", modSlugs);
        if (withState) {
            body.put("packName", "铁魔法冒险纪");
            body.put("mcVersion", "1.21.1");
            body.put("loader", "neoforge");
            body.put("excludedSlugs", "");
        }
        return body;
    }

    private ChatMessage saved() {
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(msgRepo).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("带状态的消息：存 packState、算出并与响应一起回传 snapshotId")
    void packStateIsStoredAndReturned() {
        var res = controller.saveMessage(TOKEN, CONV, body("b,a", true));

        ChatMessage msg = saved();
        String expected = PackSnapshotHash.id("neoforge", "1.21.1", "b,a", "");
        assertEquals(expected, msg.getSnapshotId());
        assertEquals(expected, res.getBody().get("snapshotId"));
        assertNotNull(msg.getPackState(), "完整状态必须落库，否则恢复历史版本只能恢复清单");
        assertTrue(msg.getPackState().contains("铁魔法冒险纪"), msg.getPackState());
        assertTrue(msg.getPackState().contains("neoforge"), msg.getPackState());
    }

    @Test
    @DisplayName("版本号只看内容：清单顺序不同、两次保存，版本号仍是同一个")
    void snapshotIdIsContentBased() {
        controller.saveMessage(TOKEN, CONV, body("b,a", true));
        String first = saved().getSnapshotId();

        controller.saveMessage(TOKEN, CONV, body("a,b", true));
        // 第二次保存：verify(times(1)) 会因为"已经存过两次"而失败，这里显式取最后一次
        ArgumentCaptor<ChatMessage> captor = ArgumentCaptor.forClass(ChatMessage.class);
        verify(msgRepo, times(2)).save(captor.capture());
        String second = captor.getValue().getSnapshotId();

        assertEquals(first, second, "同一版必须永远是同一个版本号，否则界面会把重复保存当成新版本");
    }

    @Test
    @DisplayName("不带状态的消息（例如用户那句提问）不产生版本号")
    void messageWithoutStateHasNoSnapshot() {
        Map<String, Object> plain = new HashMap<>();
        plain.put("role", "user");
        plain.put("content", "我想玩铁魔法");
        plain.put("hasData", false);
        plain.put("modSlugs", "");

        var res = controller.saveMessage(TOKEN, CONV, plain);

        assertNull(saved().getSnapshotId());
        assertFalse(res.getBody().containsKey("snapshotId"));
    }

    @Test
    @DisplayName("历史接口回读版本号与状态（前端靠它渲染 #hash 并还原工作区）")
    void messagesExposeSnapshotFields() {
        ChatMessage msg = new ChatMessage(CONV, "ai", "已生成", true, "", "sodium");
        msg.setSnapshotId("a1b2c3d4e5");
        msg.setPackState("{\"name\":\"X\",\"mcVersion\":\"1.21.1\",\"loader\":\"neoforge\"}");
        when(msgRepo.findByConversationIdOrderByCreatedAtAsc(CONV)).thenReturn(List.of(msg));

        Map<String, Object> dto = controller.getMessages(TOKEN, CONV).getBody().get(0);

        assertEquals("a1b2c3d4e5", dto.get("snapshotId"));
        assertTrue(String.valueOf(dto.get("packState")).contains("\"X\""));
    }
}
