package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.runtime.*;
import yagen.waitmydawn.maa.service.*;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实路由、鉴权过滤器与控制器组合回归；仓储及外部服务使用隔离替身。 */
class AccountIsolationHttpTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UserRepository userRepo = mock(UserRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final ChatMessageRepository messages = mock(ChatMessageRepository.class);
    private final ModPreferenceRepository modPrefs = mock(ModPreferenceRepository.class);
    private final EmailCodeService codes = new EmailCodeService(10, 0);
    private final PasswordHasher hasher = new PasswordHasher(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final DelegationTasks tasks = new DelegationTasks(true, 120000, 2, 8);
    private final AiAgentService agents = mock(AiAgentService.class);
    private UserController users;
    private ChatController chat;
    private BrowserOperationsController operations;
    private MockMvc mvc;
    private String a, anotherA, b;

    @BeforeEach void setUp() throws Exception {
        User first = new User("1001", "fixture-a", hasher.hash("fixture1234"));
        User second = new User("1002", "fixture-b", hasher.hash("fixture1234"));
        ReflectionTestUtils.setField(first, "id", 1L); ReflectionTestUtils.setField(second, "id", 2L);
        first.setEmail("a@example.com"); second.setEmail("b@example.com");
        when(userRepo.findById(1L)).thenReturn(Optional.of(first));
        when(userRepo.findById(2L)).thenReturn(Optional.of(second));
        when(userRepo.findByAccountNumber("1001")).thenReturn(Optional.of(first));
        when(userRepo.findByAccountNumber("1002")).thenReturn(Optional.of(second));
        when(userRepo.findByEmail("a@example.com")).thenReturn(Optional.of(first));
        when(userRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        users = new UserController(userRepo, conversations, messages, "offline-fixture", true, 30,
                hasher, new PasswordPolicy(), codes, mock(MailService.class));
        var prefs = new PreferencesController(userRepo, users, mock(CategoryPreferenceRepository.class), modPrefs,
                mock(ModBlacklistRepository.class), conversations, messages, mock(ModrinthApiClient.class), json);
        chat = new ChatController(agents, null, null, null, null, conversations, messages, users, userRepo,
                null, modPrefs, null, null, tasks, null);
        operations = new BrowserOperationsController(mock(ModpackController.class), prefs, chat, users,
                null, json, true, 120000, 1, 8);
        mvc = MockMvcBuilders.standaloneSetup(users, prefs, chat, operations)
                .addFilters(new AuthenticationFilter(users)).build();
        a = login("1001"); anotherA = login("1001"); b = login("1002");
    }

    @AfterEach void close() { release.countDown(); operations.close(); tasks.close(); }

    private String login(String account) throws Exception {
        String body = mvc.perform(post("/api/user/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("account", account, "password", "fixture1234"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = json.readTree(body).path("token").asText();
        assertFalse(token.isBlank()); return token;
    }

    @Test void logoutInvalidatesOnlyThatSessionAtTheBusinessFilter() throws Exception {
        mvc.perform(post("/api/user/logout").header("X-Auth-Token", a)).andExpect(status().isOk());
        mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", a)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", anotherA)).andExpect(status().isOk());
        mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", b)).andExpect(status().isOk());
        verify(conversations).findByUserIdOrderByUpdatedAtDesc(1L);
        verify(conversations).findByUserIdOrderByUpdatedAtDesc(2L);
    }

    @Test void successfulPasswordResetRevokesAllAccountSessionsButNotAnotherAccount() throws Exception {
        String resetCode = codes.issue(EmailCodeService.Purpose.RESET, "a@example.com");
        mvc.perform(post("/api/user/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "a@example.com", "code", "invalid", "newPassword", "changed1234"))))
                .andExpect(status().isOk());
        assertEquals(1L, users.validateToken(a));
        mvc.perform(post("/api/user/reset-password").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "a@example.com", "code", resetCode, "newPassword", "changed1234"))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));
        for (String token : List.of(a, anotherA))
            mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", b)).andExpect(status().isOk());
        assertTrue(hasher.verify("changed1234", userRepo.findById(1L).orElseThrow().getPasswordHash()));
    }

    @Test void authenticatedPasswordChangeKeepsCurrentSessionAndRevokesOtherDevices() throws Exception {
        String code = codes.issue(EmailCodeService.Purpose.CHANGE_PASSWORD, "a@example.com");
        mvc.perform(put("/api/user/change-password").header("X-Auth-Token", a).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code", code, "newPassword", "changed1234"))))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));
        mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", anotherA)).andExpect(status().isUnauthorized());
        for (String token : List.of(a, b))
            mvc.perform(get("/api/prefs/conversations").header("X-Auth-Token", token)).andExpect(status().isOk());
    }

    @Test void historyAndPackSnapshotsCannotBeReadWrittenOrDeletedAcrossAccounts() throws Exception {
        when(conversations.findById(7L)).thenReturn(Optional.of(new Conversation(1L, "private-fixture")));
        when(messages.findByConversationIdOrderByCreatedAtAsc(7L)).thenReturn(List.of(
                new ChatMessage(7L, "assistant", "private-pack-state", true, "1", "fixture-core")));
        mvc.perform(get("/api/prefs/conversations/7/messages").header("X-Auth-Token", b))
                .andExpect(status().isNotFound()).andExpect(content().string("[]"));
        mvc.perform(post("/api/prefs/conversations/7/messages").header("X-Auth-Token", b)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"assistant\",\"content\":\"forged\",\"packState\":\"forged\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/prefs/conversations/7").header("X-Auth-Token", b)).andExpect(status().isOk());
        verifyNoInteractions(messages); verify(conversations, never()).delete(any());
        mvc.perform(get("/api/prefs/conversations/7/messages").header("X-Auth-Token", a))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("private-pack-state")));
    }

    @Test void preferenceUpdatesHideExistenceAndDoNotMutateAnotherAccount() throws Exception {
        var pref = new ModPreference(); pref.setUserId(1L); pref.setBuildCount(4); pref.setSource("user");
        when(modPrefs.findById(7L)).thenReturn(Optional.of(pref));
        for (long id : new long[]{7, 8}) mvc.perform(put("/api/prefs/mods/" + id).header("X-Auth-Token", b)
                .contentType(MediaType.APPLICATION_JSON).content("{\"buildCount\":99}"))
                .andExpect(status().isNotFound()).andExpect(content().string("{\"error\":\"偏好不存在\"}"));
        assertEquals(4, pref.getBuildCount()); verify(modPrefs, never()).save(any());
        mvc.perform(put("/api/prefs/mods/7").header("X-Auth-Token", a).contentType(MediaType.APPLICATION_JSON)
                .content("{\"buildCount\":5}")).andExpect(status().isOk());
        assertEquals(5, pref.getBuildCount());
    }

    @Test void chatFactsCannotPollOrInjectIntoAnotherOwnersPendingOrCompletedTask() throws Exception {
        var task = tasks.submit("private-chat-task", 1L, "fixture-a", null, () -> { release.await(); return "private-chat-result"; });
        String endpoint = "/api/chat/task/" + task.taskId() + "/facts";
        for (boolean completed : new boolean[]{false, true}) {
            if (completed) { release.countDown(); task.result().get(2, TimeUnit.SECONDS); }
            mvc.perform(post(endpoint).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isUnauthorized());
            mvc.perform(post(endpoint).header("X-Auth-Token", b).contentType(MediaType.APPLICATION_JSON).content("{}"))
                    .andExpect(status().isNotFound());
        }
        mvc.perform(post(endpoint).header("X-Auth-Token", a).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("private-chat-result")));
    }

    @Test void internalOwnerlessTaskCannotBeClaimedThroughThePublicReceiptRoute() throws Exception {
        var task = tasks.submit("internal-only-task", "internal", null, () -> "internal-result");
        task.result().get(2, TimeUnit.SECONDS);
        for (String token : List.of(a, b)) mvc.perform(post("/api/chat/task/" + task.taskId() + "/facts")
                .header("X-Auth-Token", token).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test void shortOperationPollFactsAndCancelAreOwnerBound() throws Exception {
        var registry = (DelegationTasks) ReflectionTestUtils.getField(operations, "tasks");
        var task = registry.submit("private-operation", 1L, "fixture-a", null,
                () -> { release.await(); return "{\"status\":200,\"body\":{\"pack\":\"private-operation-result\"}}"; });
        String endpoint = "/api/operations/" + task.taskId();
        for (boolean completed : new boolean[]{false, true}) {
            if (completed) { release.countDown(); task.result().get(2, TimeUnit.SECONDS); }
            mvc.perform(get(endpoint).header("X-Auth-Token", b)).andExpect(status().isNotFound());
            for (String suffix : List.of("/facts", "/cancel"))
                mvc.perform(post(endpoint + suffix).header("X-Auth-Token", b).contentType(MediaType.APPLICATION_JSON).content("{}"))
                        .andExpect(status().isNotFound());
            assertFalse(task.result().isCancelled());
        }
        mvc.perform(get(endpoint).header("X-Auth-Token", a)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("private-operation-result")));
    }

    @Test void abortRequiresAuthenticationAndCannotCancelAnotherAccountByUuid() throws Exception {
        var first = tasks.submit("abort-a", 1L, "fixture-a", null, () -> { release.await(); return "a"; });
        var second = tasks.submit("abort-b", 2L, "fixture-b", null, () -> { release.await(); return "b"; });
        mvc.perform(post("/api/chat/abort").contentType(MediaType.APPLICATION_JSON).content("{\"uuid\":\"fixture-a\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/chat/abort").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("uuid", "fixture-a", "token", b)))).andExpect(status().isOk());
        assertFalse(first.isDone()); assertTrue(second.result().isCancelled());
    }

    @Test void forgedClarificationOwnerCannotConsumeTheOriginalTicket() throws Exception {
        var clarifications = (PlanningClarifications) ReflectionTestUtils.getField(chat, "planningClarifications");
        Map<String, String> original = Map.of("prompt", "fixture adventure", "uuid", "fixture-page");
        String ticket = clarifications.put(1L, null, original, "fixture question");
        var answer = new HashMap<>(original); answer.put("clarificationTicket", ticket); answer.put("prompt", "fixture answer");
        mvc.perform(post("/api/chat").header("X-Auth-Token", b).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(answer))).andExpect(status().isBadRequest());
        assertNotNull(clarifications.admit(ticket, 1L, null, answer, prepared -> prepared));
        verifyNoInteractions(agents);
    }

    private void saveKey(String token, String key) throws Exception {
        mvc.perform(put("/api/user/profile").header("X-Auth-Token", token).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("apiKey", key))))
                .andExpect(status().isOk());
    }

    @Test void personalKeysAreEncryptedAndNeverIncludedInAccountOrPreferenceResponses() throws Exception {
        saveKey(a, "sk-offline-fixture-key-a"); saveKey(b, "sk-offline-fixture-key-b");
        assertEquals("sk-offline-fixture-key-a", users.getUserApiKey(a));
        assertEquals("sk-offline-fixture-key-b", users.getUserApiKey(b));
        assertNull(users.getUserApiKey("invalid-fixture-token"));
        String encrypted = userRepo.findById(1L).orElseThrow().getEncryptedApiKey();
        assertTrue(encrypted.startsWith("gcm1:")); assertFalse(encrypted.contains("sk-offline"));
        for (String token : List.of(a, b)) for (String endpoint : List.of("/api/user/check-token", "/api/prefs/export")) {
            String result = mvc.perform(get(endpoint).header("X-Auth-Token", token)).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertFalse(result.contains("sk-offline")); assertFalse(result.contains("gcm1:"));
        }
        String loginResult = mvc.perform(post("/api/user/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"account\":\"1001\",\"password\":\"fixture1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(loginResult.contains("sk-offline")); assertFalse(loginResult.contains("gcm1:"));
        assertTrue(loginResult.contains("\"hasApiKey\":true"));
    }

    @Test void clearingAndInvalidKeyWritesDoNotCorruptAnotherAccountOrExistingProfile() throws Exception {
        saveKey(a, " sk-offline-fixture-key-a "); saveKey(b, "sk-offline-fixture-key-b");
        assertEquals("sk-offline-fixture-key-a", users.getUserApiKey(a));
        User first = userRepo.findById(1L).orElseThrow(); String previous = first.getEncryptedApiKey();
        for (String invalid : List.of("x".repeat(513), "sk-invalid\nfixture"))
            mvc.perform(put("/api/user/profile").header("X-Auth-Token", a).contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("apiKey", invalid, "username", "should-not-be-applied"))))
                    .andExpect(status().isBadRequest());
        assertEquals(previous, first.getEncryptedApiKey()); assertEquals("fixture-a", first.getUsername());
        saveKey(a, " "); assertNull(first.getEncryptedApiKey()); assertNull(users.getUserApiKey(a));
        mvc.perform(get("/api/user/check-token").header("X-Auth-Token", a)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"hasApiKey\":false")));
        assertEquals("sk-offline-fixture-key-b", users.getUserApiKey(b));
    }

    @Test void productionReadsOnlyTheAuthenticatedPersonalKeyEvenWhenTestOverridesAreEnabled() throws Exception {
        saveKey(a, "sk-offline-fixture-key-a");
        ReflectionTestUtils.setField(chat, "systemDefaultApiKey", "sk-offline-fixture-system");
        ReflectionTestUtils.setField(chat, "allowKeyOverride", true);
        var environment = new org.springframework.mock.env.MockEnvironment();
        ReflectionTestUtils.setField(chat, "environment", environment);
        for (String[] profiles : List.of(new String[]{}, new String[]{"prod"}, new String[]{"eval", "production"}, new String[]{"eval", "PROD"})) {
            environment.setActiveProfiles(profiles);
            assertEquals("sk-offline-fixture-key-a", ReflectionTestUtils.invokeMethod(chat, "effectiveKey", a, "sk-offline-fixture-header"));
            assertNull(ReflectionTestUtils.invokeMethod(chat, "effectiveKey", b, "sk-offline-fixture-header"));
        }
        User first = userRepo.findById(1L).orElseThrow(); first.setEncryptedApiKey("gcm1:invalid");
        assertNull(ReflectionTestUtils.invokeMethod(chat, "effectiveKey", a, "sk-offline-fixture-header"));
        assertEquals("gcm1:invalid", first.getEncryptedApiKey());
    }

    @Test void unexpectedTaskExceptionCannotBecomeASecretBearingConversationReply() throws Exception {
        var task = tasks.submit("secret-error-task", 1L, "fixture-a", null, () -> {
            throw new IllegalStateException("Authorization: Bearer opaque.fixture.token sk-offline-fixture-key");
        });
        assertThrows(ExecutionException.class, () -> task.result().get(2, TimeUnit.SECONDS));
        String reply = mvc.perform(post("/api/chat/task/" + task.taskId() + "/facts").header("X-Auth-Token", a)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(reply.contains("请稍后重试")); assertFalse(reply.contains("sk-offline")); assertFalse(reply.contains("opaque.fixture"));
        verifyNoInteractions(messages);
    }

    @Test void productionChatWithoutPersonalKeyDoesNotReachTheModelUsingAHeaderOrSystemKey() throws Exception {
        ReflectionTestUtils.setField(chat, "systemDefaultApiKey", "sk-offline-fixture-system");
        ReflectionTestUtils.setField(chat, "allowKeyOverride", true);
        var environment = new org.springframework.mock.env.MockEnvironment(); environment.setActiveProfiles("prod", "eval");
        ReflectionTestUtils.setField(chat, "environment", environment);
        String reply = mvc.perform(post("/api/chat").header("X-Auth-Token", b)
                .header("X-LLM-Api-Key", "sk-offline-fixture-header").contentType(MediaType.APPLICATION_JSON)
                .content("{\"prompt\":\"fixture adventure\",\"uuid\":\"fixture-no-key\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(reply.contains("配置你的 DeepSeek API Key")); assertFalse(reply.contains("sk-offline"));
        verifyNoInteractions(agents);
    }

    @Test void failedMailRefundsTheDailyQuotaButKeepsCooldownAndRevokesThatCode() throws Exception {
        var now = new java.util.concurrent.atomic.AtomicLong(1700000000000L);
        var service = new EmailCodeService(3, 60000, now::get);
        ReflectionTestUtils.setField(users, "emailCodeService", service);
        MailService mail = (MailService) ReflectionTestUtils.getField(users, "mailService");
        when(mail.isConfigured()).thenReturn(true);
        var code = new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(call -> { code.set(call.getArgument(2)); throw new IllegalStateException("offline mail failure code=" + code.get()); })
                .when(mail).sendCode(anyString(), anyString(), anyString(), anyInt());
        String body = "{\"email\":\"fresh@example.com\",\"purpose\":\"register\"}";
        String failed;
        try (var logs = mockStatic(yagen.waitmydawn.maa.logging.MaaLog.class)) {
            failed = mvc.perform(post("/api/user/email-code").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            logs.verify(() -> yagen.waitmydawn.maa.logging.MaaLog.app("验证码邮件发送失败 type=IllegalStateException"));
            logs.verifyNoMoreInteractions();
        }
        assertTrue(failed.contains("发送失败")); assertFalse(failed.contains("offline mail failure"));
        assertEquals(0, service.sentToday("fresh@example.com"));
        assertFalse(service.verify(EmailCodeService.Purpose.REGISTER, "fresh@example.com", code.get()));
        String cooling = mvc.perform(post("/api/user/email-code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(cooling.contains("秒后再试")); verify(mail, times(1)).sendCode(anyString(), anyString(), anyString(), anyInt());
        now.addAndGet(60000); doNothing().when(mail).sendCode(anyString(), anyString(), anyString(), anyInt());
        mvc.perform(post("/api/user/email-code").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));
        assertEquals(1, service.sentToday("fresh@example.com"));
    }

    @Test void authenticatedCodePurposesRejectAnonymousAndUseTheBoundRecipientForPasswordChanges() throws Exception {
        MailService mail = (MailService) ReflectionTestUtils.getField(users, "mailService"); when(mail.isConfigured()).thenReturn(true);
        String body = "{\"email\":\"victim@example.com\",\"purpose\":\"change_password\"}";
        mvc.perform(post("/api/user/email-code").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/user/email-code").header("X-Auth-Token", a).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("\"ok\":true")));
        verify(mail).sendCode(eq("a@example.com"), anyString(), anyString(), eq(5));
        verify(mail, never()).sendCode(eq("victim@example.com"), anyString(), anyString(), anyInt());
        for (String invalid : List.of("{\"purpose\":null}", "{\"purpose\":\"" + "x".repeat(33) + "\"}"))
            mvc.perform(post("/api/user/email-code").contentType(MediaType.APPLICATION_JSON).content(invalid)).andExpect(status().isBadRequest());
    }

    @Test @org.junit.jupiter.api.Timeout(10)
    void concurrentPasswordResetsCannotConsumeOneCodeTwice() throws Exception {
        String code = codes.issue(EmailCodeService.Purpose.RESET, "a@example.com");
        String body = json.writeValueAsString(Map.of("email", "a@example.com", "code", code, "newPassword", "changed1234"));
        clearInvocations(userRepo);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = new ArrayList<Future<String>>();
            for (int i = 0; i < 8; i++) results.add(executor.submit(() -> {
                start.await(); return mvc.perform(post("/api/user/reset-password").contentType(MediaType.APPLICATION_JSON).content(body))
                        .andReturn().getResponse().getContentAsString();
            }));
            start.countDown(); int successes = 0;
            for (var result : results) if (result.get(3, TimeUnit.SECONDS).contains("\"ok\":true")) successes++;
            assertEquals(1, successes); verify(userRepo, times(1)).save(any());
        }
    }
}
