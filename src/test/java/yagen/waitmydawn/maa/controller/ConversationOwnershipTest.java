package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.CategoryPreference;
import yagen.waitmydawn.maa.model.Conversation;
import yagen.waitmydawn.maa.model.ModBlacklist;
import yagen.waitmydawn.maa.model.ModPreference;
import yagen.waitmydawn.maa.model.*;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 「这条数据是不是你的」的测试。
 *
 * <p>为什么专门测它：对话 id / 偏好 id 都是**自增数字**，能枚举。少了归属校验，
 * 任何一个登录用户换个 id 就能读别人的对话历史、往别人的对话里写消息、删别人的偏好 ——
 * 这属于"多用户上线前必须钉死"的一类问题，必须由测试而不是靠人眼 review 来保证。
 *
 * <p>这里用 mock 的 UserController（只桩掉 validateToken）：被测的是归属判断这段逻辑本身，
 * 没必要牵出真正的注册/登录流程。
 */
class ConversationOwnershipTest {

    private static final String TOKEN_B = "token-of-user-2";
    private static final Long USER_A = 1L;
    private static final Long USER_B = 2L;

    private UserController userController;
    private CategoryPreferenceRepository catRepo;
    private ModPreferenceRepository modRepo;
    private ModBlacklistRepository blacklistRepo;
    private ConversationRepository convRepo;
    private ChatMessageRepository msgRepo;
    private PreferencesController controller;

    @BeforeEach
    void setUp() {
        userController = mock(UserController.class);
        catRepo = mock(CategoryPreferenceRepository.class);
        modRepo = mock(ModPreferenceRepository.class);
        blacklistRepo = mock(ModBlacklistRepository.class);
        convRepo = mock(ConversationRepository.class);
        msgRepo = mock(ChatMessageRepository.class);

        // 2 号用户登录着
        when(userController.validateToken(TOKEN_B)).thenReturn(USER_B);

        controller = new PreferencesController(mock(UserRepository.class), userController,
                catRepo, modRepo, blacklistRepo, convRepo, msgRepo, null);
    }

    /** 一个属于 1 号用户的对话 */
    private Conversation conversationOfUserA() {
        Conversation c = new Conversation(USER_A, "别人的对话");
        return c;
    }

    @Test
    @DisplayName("读别人的对话消息：404 且一条消息都不返回")
    void cannotReadOthersMessages() {
        when(convRepo.findById(99L)).thenReturn(Optional.of(conversationOfUserA()));

        var resp = controller.getMessages(TOKEN_B, 99L);

        assertEquals(404, resp.getStatusCode().value());
        assertEquals(List.of(), resp.getBody());
        verify(msgRepo, never()).findByConversationIdOrderByCreatedAtAsc(anyLong());
    }

    @Test
    @DisplayName("自己的对话还能正常读（别把功能修成谁都读不了）")
    void canReadOwnMessages() {
        Conversation mine = new Conversation(USER_B, "我的对话");
        when(convRepo.findById(7L)).thenReturn(Optional.of(mine));
        when(msgRepo.findByConversationIdOrderByCreatedAtAsc(7L)).thenReturn(List.of());

        var resp = controller.getMessages(TOKEN_B, 7L);

        assertEquals(200, resp.getStatusCode().value());
    }

    @Test
    @DisplayName("对话不存在时也是 404（不泄露「这个 id 存在但不是你的」）")
    void missingConversationIs404() {
        when(convRepo.findById(1L)).thenReturn(Optional.empty());
        assertEquals(404, controller.getMessages(TOKEN_B, 1L).getStatusCode().value());
    }

    @Test
    @DisplayName("往别人的对话里写消息：404 且不落库")
    void cannotWriteIntoOthersConversation() {
        when(convRepo.findById(99L)).thenReturn(Optional.of(conversationOfUserA()));

        var resp = controller.saveMessage(TOKEN_B, 99L, java.util.Map.of("role", "user", "content", "hi"));

        assertEquals(404, resp.getStatusCode().value());
        verify(msgRepo, never()).save(any());
        verify(convRepo, never()).save(any());
    }

    @Test
    @DisplayName("删别人的类别偏好 / 模组偏好 / 黑名单：全部 404 且不删")
    void cannotDeleteOthersPreferenceRows() {
        CategoryPreference cat = new CategoryPreference();
        cat.setUserId(USER_A);
        when(catRepo.findById(11L)).thenReturn(Optional.of(cat));

        ModPreference mod = new ModPreference();
        mod.setUserId(USER_A);
        when(modRepo.findById(22L)).thenReturn(Optional.of(mod));

        ModBlacklist bl = new ModBlacklist();
        bl.setUserId(USER_A);
        when(blacklistRepo.findById(33L)).thenReturn(Optional.of(bl));

        assertEquals(404, controller.removeCategoryPref(TOKEN_B, 11L).getStatusCode().value());
        assertEquals(404, controller.removeModPref(TOKEN_B, 22L).getStatusCode().value());
        assertEquals(404, controller.removeBlacklist(TOKEN_B, 33L).getStatusCode().value());

        verify(catRepo, never()).deleteById(anyLong());
        verify(modRepo, never()).deleteById(anyLong());
        verify(blacklistRepo, never()).deleteById(anyLong());
    }
}
