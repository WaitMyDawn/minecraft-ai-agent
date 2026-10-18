package yagen.waitmydawn.maa.service;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryDatabaseTest {
    @TempDir Path directory;

    private void run(Path root, String action, int expected) throws Exception {
        Path log = directory.resolve(action + "-" + root.getFileName() + ".log");
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = java.util.List.of(executable, "-Xmx256m", "-cp", System.getProperty("java.class.path"),
                RecoveryDatabaseProbe.class.getName(), root.toAbsolutePath().toString(), action);
        Process child = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        Files.writeString(directory.resolve("owned-" + child.pid() + ".txt"), "pid=" + child.pid() + "\naction=" + action + "\nroot=" + root.toAbsolutePath());
        try {
            assertTrue(child.waitFor(60, TimeUnit.SECONDS), "recovery probe timeout; log=" + log);
            assertEquals(expected, child.exitValue(), "recovery probe failed; log=" + log);
            if (action.equals("verify")) assertTrue(Files.readString(log).contains("RECOVERY_VERIFIED"));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS)); }
        }
    }

    @Test @Timeout(150) void committedSnapshotsSurviveNormalJvmRestart() throws Exception {
        Path root = directory.resolve("normal"); run(root, "seed", 0); run(root, "verify", 0);
    }

    @Test @Timeout(150) void abruptJvmExitPreservesCommittedButNotUncommittedHistory() throws Exception {
        Path root = directory.resolve("abrupt"); run(root, "halt", 23); run(root, "verify", 0);
    }

    @Test @Timeout(150) void onlineBackupRestoresHistoryAndSnapshotInANewDirectory() throws Exception {
        Path root = directory.resolve("source"); run(root, "seed", 0);
        Path restored = directory.resolve("restored"); Files.createDirectories(restored.resolve("db"));
        org.h2.tools.Restore.execute(root.resolve("backups/snapshot/knowledge.zip").toString(), restored.resolve("db").toString(), null);
        run(restored, "verify", 0);
    }
}
