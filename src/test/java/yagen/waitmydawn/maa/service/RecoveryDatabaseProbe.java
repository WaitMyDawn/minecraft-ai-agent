package yagen.waitmydawn.maa.service;

import org.springframework.context.annotation.*;
import org.springframework.core.env.MapPropertySource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import yagen.waitmydawn.maa.config.MainDataSourceConfig;
import yagen.waitmydawn.maa.controller.*;
import yagen.waitmydawn.maa.model.*;
import tools.jackson.databind.ObjectMapper;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** 独立JVM的文件库恢复探针；只注册数据库配置，不运行应用main或读取.env。 */
public final class RecoveryDatabaseProbe {
    @Configuration @EnableTransactionManagement
    static class Transactions {}

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(root.resolve("db"));
        var failSave = new AtomicBoolean();
        try (var context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("isolated-recovery", Map.of(
                    "spring.datasource.url", "jdbc:h2:file:" + root.resolve("db/knowledge").toString().replace('\\', '/') + ";AUTO_SERVER=TRUE;WRITE_DELAY=0",
                    "spring.datasource.username", "sa", "spring.datasource.password", "", "maa.db.schema-mode", "update",
                    "maa.db.pool-size", "2")));
            context.register(MainDataSourceConfig.class, Transactions.class);
            context.registerBean(PreferencesController.class, () -> {
                var conversations = context.getBean(ConversationRepository.class);
                var guarded = (ConversationRepository) java.lang.reflect.Proxy.newProxyInstance(ConversationRepository.class.getClassLoader(),
                        new Class<?>[]{ConversationRepository.class}, (proxy, method, arguments) -> {
                            if (method.getName().equals("save") && failSave.get()) throw new IllegalStateException("fixture write failure");
                            try { return method.invoke(conversations, arguments); }
                            catch (java.lang.reflect.InvocationTargetException failed) { throw failed.getCause(); }
                        });
                var users = context.getBean(UserRepository.class);
                var messages = context.getBean(ChatMessageRepository.class);
                var identity = new UserController(users, conversations, messages, "offline-recovery-secret", false, 30,
                        new PasswordHasher(1), new PasswordPolicy(), new EmailCodeService(3, 60000), null) {
                    @Override public Long validateToken(String token) { return 1L; }
                };
                return new PreferencesController(users, identity, context.getBean(CategoryPreferenceRepository.class),
                        context.getBean(ModPreferenceRepository.class), context.getBean(ModBlacklistRepository.class), guarded, messages, null, new ObjectMapper());
            });
            context.refresh();
            var conversations = context.getBean(ConversationRepository.class);
            var messages = context.getBean(ChatMessageRepository.class);
            if (args[1].equals("verify")) {
                if (messages.count() != 1 || conversations.count() != 1) throw new AssertionError("recovery row counts");
                var conversation = conversations.findAll().getFirst();
                var saved = messages.findByConversationIdOrderByCreatedAtAsc(conversation.getId()).getFirst();
                if (!conversation.getTitle().equals("stable") || !saved.getContent().equals("committed-" + "x".repeat(8800))
                        || saved.getSnapshotId() == null || !saved.getSnapshotId().equals(PackSnapshotHash.id("neoforge", "1.21.1", "fixture-core", ""))
                        || !saved.getPackState().contains("fixture-core")) throw new AssertionError("recovery snapshot");
                System.out.println("RECOVERY_VERIFIED");
                return;
            }
            var conversation = conversations.saveAndFlush(new Conversation(1L, "stable"));
            var prefs = context.getBean(PreferencesController.class);
            var body = new HashMap<String, Object>(Map.of("role", "ai", "content", "committed-" + "x".repeat(8800),
                    "hasData", true, "modSlugs", "fixture-core", "packName", "fixture-pack", "mcVersion", "1.21.1", "loader", "neoforge"));
            if (!prefs.saveMessage("fixture", conversation.getId(), body).getStatusCode().is2xxSuccessful()) throw new AssertionError("seed write");
            failSave.set(true);
            body.put("content", "rolled-back"); body.put("title", "should-not-survive"); body.put("modSlugs", "uncommitted-core");
            try { prefs.saveMessage("fixture", conversation.getId(), body); throw new AssertionError("expected rollback"); }
            catch (IllegalStateException expected) { }
            failSave.set(false);
            if (messages.count() != 1 || !conversations.findById(conversation.getId()).orElseThrow().getTitle().equals("stable"))
                throw new AssertionError("transaction was not atomic");
            if (args[1].equals("halt")) {
                var manager = context.getBean(jakarta.persistence.EntityManagerFactory.class).createEntityManager();
                manager.getTransaction().begin();
                manager.persist(new ChatMessage(conversation.getId(), "ai", "uncommitted-at-halt", true, "", "bad-core"));
                manager.flush();
                Runtime.getRuntime().halt(23);
            }
            new BackupService(context.getBean(javax.sql.DataSource.class), root.resolve("backups").toString()).backup("snapshot");
        }
        System.out.println("RECOVERY_SEEDED");
    }
}
