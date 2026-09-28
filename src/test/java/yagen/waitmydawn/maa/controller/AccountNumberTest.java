package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import yagen.waitmydawn.maa.model.ChatMessageRepository;
import yagen.waitmydawn.maa.model.ConversationRepository;
import yagen.waitmydawn.maa.model.UserRepository;
import yagen.waitmydawn.maa.service.PasswordHasher;
import yagen.waitmydawn.maa.service.EmailCodeService;
import yagen.waitmydawn.maa.service.MailService;
import yagen.waitmydawn.maa.service.PasswordPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 账号发号器的测试。
 *
 * <p>要钉死的是那个真实存在的并发缺陷：老实现是 {@code 1000 + userRepo.count()} ——
 * 两个注册请求同时读到 count=5 就都发 1005；而且删过账号之后 count 变小，还会发出<b>已存在</b>的号。
 * 前者在唯一约束下表现为"一个人注册时莫名 500"，约束一旦不在就成了"两个人同号、都登不上"。
 *
 * <p>这里用真实并发（20 个线程卡在同一个门闩上一起冲）而不是顺序调用 —— 顺序调用是测不出这个 bug 的。
 */
class AccountNumberTest {

    private UserRepository userRepo;
    private UserController controller;
    private EmailCodeService codes;

    @BeforeEach
    void setUp() {
        userRepo = mock(UserRepository.class);
        when(userRepo.findMaxAccountNumber()).thenReturn(1005L);   // 库里已有 1000..1005
        when(userRepo.existsByAccountNumber(anyString())).thenReturn(false);
        when(userRepo.existsByUsername(anyString())).thenReturn(false);
        when(userRepo.existsByEmail(anyString())).thenReturn(false);
        codes = new EmailCodeService(3, 60_000, System::currentTimeMillis);
        // 注册路径不碰邮件发送，这里给个"永远未配置"的空壳即可
        ObjectProvider<JavaMailSender> noMail = new ObjectProvider<>() {
            @Override public JavaMailSender getObject() { throw new IllegalStateException("no mail"); }
            @Override public JavaMailSender getIfAvailable() { return null; }
        };
        controller = new UserController(userRepo,
                mock(ConversationRepository.class), mock(ChatMessageRepository.class),
                "test-secret", true, 30, new PasswordHasher(1), new PasswordPolicy(), codes,
                new MailService(noMail, false, "", "MAA"));
    }

    private String registerAs(String username) {
        // 每个用户名配一个独立邮箱：验证码按邮箱限流（一天 3 个），共用邮箱会互相挡住
        String email = username + "@example.com";
        String code = codes.issue(EmailCodeService.Purpose.REGISTER, email);
        var resp = controller.register(Map.of(
                "password", "pw123456", "username", username, "email", email, "code", code));
        Object err = resp.getBody().get("error");
        assertTrue(err == null, "注册不该失败：" + err);
        return String.valueOf(resp.getBody().get("accountNumber"));
    }

    @Test
    @DisplayName("20 个线程同时注册：账号号互不相同，且从库里最大值往后接")
    void concurrentRegistrationNeverReusesNumber() throws Exception {
        int threads = 20;
        List<String> numbers = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                pool.submit(() -> {
                    try {
                        startGate.await();                 // 让所有线程尽可能同时进入
                        numbers.add(registerAs("user" + idx));
                    } catch (Exception ignored) {
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startGate.countDown();
            assertTrue(finished.await(20, TimeUnit.SECONDS), "并发注册没跑完");
        } finally {
            pool.shutdownNow();
        }

        assertEquals(threads, numbers.size(), "每次注册都该拿到一个号");
        assertEquals(threads, new HashSet<>(numbers).size(),
                "账号号必须互不相同（老实现这里会全是 1006）：" + numbers);
        assertTrue(numbers.contains("1006"), "第一个号应该是库里的最大值 +1：" + numbers);
    }

    @Test
    @DisplayName("不再用 count() 发号（删过账号之后 count 会偏小，会发出已存在的号）")
    void doesNotUseCount() {
        assertEquals("1006", registerAs("aaa"));
        assertEquals("1007", registerAs("bbb"));
        verify(userRepo, never()).count();
    }

    @Test
    @DisplayName("库里最大值之后有空洞（比如手工插过号）时，跳过已被占用的号")
    void skipsTakenNumbers() {
        when(userRepo.existsByAccountNumber("1006")).thenReturn(true);
        assertEquals("1007", registerAs("ccc"));
    }

    @Test
    @DisplayName("用户名重复时拒绝，不再丢给数据库报 500")
    void duplicateUsernameRejected() {
        when(userRepo.existsByUsername("taken")).thenReturn(true);
        String email = "taken@example.com";
        String code = codes.issue(EmailCodeService.Purpose.REGISTER, email);
        var resp = controller.register(Map.of(
                "password", "pw123456", "username", "taken", "email", email, "code", code));
        Object err = resp.getBody().get("error");
        assertTrue(err != null && String.valueOf(err).contains("用户名"), "应返回明确的用户名占用提示：" + err);
    }
}
