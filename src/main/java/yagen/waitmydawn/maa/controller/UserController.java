package yagen.waitmydawn.maa.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.runtime.SessionRegistry;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

@RestController
@RequestMapping("/api/user")
@CrossOrigin(origins = "*")
public class UserController {

    private final UserRepository userRepo;
    private final ConversationRepository convRepo;
    private final ChatMessageRepository msgRepo;
    /** token → userId 的会话表，带空闲有效期（见 SessionRegistry 的取舍说明） */
    private final SessionRegistry sessions;
    private final String encryptionSecret;
    private final boolean allowRegistration;

    public UserController(UserRepository userRepo, ConversationRepository convRepo,
                          ChatMessageRepository msgRepo,
                          @Value("${maa.encryption.secret}") String encryptionSecret,
                          @Value("${maa.allow-registration:true}") boolean allowRegistration,
                          @Value("${maa.session.ttl-days:30}") long sessionTtlDays) {
        this.userRepo = userRepo;
        this.convRepo = convRepo;
        this.msgRepo = msgRepo;
        this.encryptionSecret = encryptionSecret;
        this.allowRegistration = allowRegistration;
        // 天数 → 毫秒。给下限 1 天：配成 0 或负数会让所有人都登不进来（400 起不来比"token 不过期"更难排查）
        this.sessions = new SessionRegistry(Math.max(1, sessionTtlDays) * 24L * 60 * 60 * 1000);
    }

    /** 注册: 账号从 1000 起自动分配 */
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> register(@RequestBody Map<String, String> body) {
        if (!allowRegistration) {
            return ResponseEntity.ok(Map.of("error", "当前不开放公开注册，请等待内测邀请"));
        }
        String password = body.get("password");
        String username = body.getOrDefault("username", "").trim();
        if (password == null || password.length() < 4) {
            return ResponseEntity.ok(Map.of("error", "密码至少 4 位"));
        }
        // 分配账号
        long count = userRepo.count();
        String accountNumber = String.valueOf(1000 + count);
        // 默认用户名 = 账号
        if (username.isEmpty()) username = accountNumber;

        User user = new User(accountNumber, username, hash(password));
        userRepo.save(user);

        String token = sessions.issue(user.getId());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty(),
                "enableBlacklist", user.isEnableBlacklist(),
                "enableUserFeedbackRules", user.isEnableUserFeedbackRules()
        ));
    }

    /** 登录: 通过账号 + 密码 */
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String account = body.get("account");
        String password = body.get("password");
        var userOpt = userRepo.findByAccountNumber(account);
        if (userOpt.isEmpty() || !userOpt.get().getPasswordHash().equals(hash(password))) {
            return ResponseEntity.ok(Map.of("error", "账号或密码错误"));
        }
        User user = userOpt.get();
        String token = sessions.issue(user.getId());

        return ResponseEntity.ok(Map.of(
                "token", token,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty(),
                "enableBlacklist", user.isEnableBlacklist(),
                "enableUserFeedbackRules", user.isEnableUserFeedbackRules()
        ));
    }

    /** 更新用户名 */
    @PutMapping("/profile")
    public ResponseEntity<Map<String, Object>> updateProfile(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long userId = sessions.touch(token);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "未登录，请重新登录"));
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) {
            sessions.revoke(token);
            return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        }
        User user = userOpt.get();
        if (body.containsKey("username")) user.setUsername(body.get("username"));
        if (body.containsKey("preferenceWeight")) {
            user.setPreferenceWeight(Double.parseDouble(body.get("preferenceWeight")));
        }
        if (body.containsKey("apiKey")) {
            user.setEncryptedApiKey(encrypt(body.get("apiKey")));
        }
        if (body.containsKey("enableBlacklist")) {
            user.setEnableBlacklist(Boolean.parseBoolean(body.get("enableBlacklist")));
        }
        if (body.containsKey("enableUserFeedbackRules")) {
            user.setEnableUserFeedbackRules(Boolean.parseBoolean(body.get("enableUserFeedbackRules")));
        }
        userRepo.save(user);
        return ResponseEntity.ok(Map.of(
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty()
        ));
    }

    /** 修改密码 */
    @PutMapping("/change-password")
    public ResponseEntity<Map<String, Object>> changePassword(
            @RequestHeader("X-Auth-Token") String token,
            @RequestBody Map<String, String> body) {
        Long userId = sessions.touch(token);
        if (userId == null) return ResponseEntity.status(401).body(Map.of("error", "未登录"));
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) return ResponseEntity.status(401).body(Map.of("error", "用户不存在"));
        User user = userOpt.get();

        String oldPw = body.get("oldPassword");
        String newPw = body.get("newPassword");
        if (oldPw == null || !user.getPasswordHash().equals(hash(oldPw))) {
            return ResponseEntity.ok(Map.of("error", "旧密码错误"));
        }
        if (newPw == null || newPw.length() < 4) {
            return ResponseEntity.ok(Map.of("error", "新密码至少 4 位"));
        }
        user.setPasswordHash(hash(newPw));
        userRepo.save(user);
        return ResponseEntity.ok(Map.of("ok", true, "message", "密码修改成功"));
    }

    /** 验证 token 并返回 userId, null 表示无效 */
    public Long validateToken(String token) {
        return sessions.touch(token);   // 内部已挡 null；顺便滑动续期
    }

    /** 根据 token 获取用户对象 (用于获取 accountNumber 等) */
    public Optional<User> getUserByToken(String token) {
        Long uid = sessions.touch(token);
        if (uid == null) return Optional.empty();
        return userRepo.findById(uid);
    }

    /**
     * 退出登录：服务端立刻作废这个 token。
     *
     * <p>为什么要有：前端 {@code logout} 只删了 localStorage，token 本身还是有效的 ——
     * 网络抓包、共用电脑、浏览器同步都会把它带出去。没有这个端点，"退出登录"是假的。
     *
     * <p>token 缺失也返回 ok：调用方唯一想听的就是"你已经退出了"，为此报错没有意义。
     */
    @PostMapping("/logout")
    public ResponseEntity<Map<String, Object>> logout(
            @RequestHeader(value = "X-Auth-Token", required = false) String token) {
        sessions.revoke(token);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 前端页面加载时校验 token 是否有效，同步登录状态 */
    @GetMapping("/check-token")
    public ResponseEntity<Map<String, Object>> checkToken(@RequestHeader("X-Auth-Token") String token) {
        Long userId = sessions.touch(token);
        if (userId == null) {
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "未登录"));
        }
        var userOpt = userRepo.findById(userId);
        if (userOpt.isEmpty()) {
            sessions.revoke(token);
            return ResponseEntity.status(401).body(Map.of("valid", false, "error", "用户不存在"));
        }
        User user = userOpt.get();
        return ResponseEntity.ok(Map.of(
                "valid", true,
                "accountNumber", user.getAccountNumber(),
                "username", user.getUsername(),
                "preferenceWeight", user.getPreferenceWeight(),
                "hasApiKey", user.getEncryptedApiKey() != null && !user.getEncryptedApiKey().isEmpty(),
                "enableBlacklist", user.isEnableBlacklist(),
                "enableUserFeedbackRules", user.isEnableUserFeedbackRules()
        ));
    }

    /** 根据 token 获取用户解密后的 API Key */
    public String getUserApiKey(String token) {
        Long uid = sessions.touch(token);
        if (uid == null) return null;
        return userRepo.findById(uid)
                .map(u -> decrypt(u.getEncryptedApiKey()))
                .orElse(null);
    }

    private static String hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) { return input; }
    }

    // AES 加密/解密 (密钥 = SHA-256 of server-side secret)
    private SecretKeySpec getKey() throws Exception {
        byte[] key = MessageDigest.getInstance("SHA-256").digest(encryptionSecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(key, "AES");
    }

    private String encrypt(String plain) {
        if (plain == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, getKey());
            String result = Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8)));
//            System.out.println("AES 加密成功 (前6位): " + result.substring(0, Math.min(6, result.length())) + "...");
            System.out.println("AES 加密成功！");
            return result;
        } catch (Exception e) {
            System.err.println("AES 加密失败: " + e.getMessage());
            return null;
        }
    }

    private String decrypt(String encrypted) {
        if (encrypted == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, getKey());
            return new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("AES 解密失败（可能是加密密钥已变更，请用户重新设置 API Key）: " + e.getMessage());
            return null;
        }
    }
}
