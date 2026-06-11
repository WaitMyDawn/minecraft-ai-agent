package yagen.waitmydawn.maa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class MinecraftAiAgentApplication {

    public static void main(String[] args) {
        loadEnvFile();
        SpringApplication.run(MinecraftAiAgentApplication.class, args);
    }

    /**
     * 加载项目根目录的 .env 文件到系统属性。
     * Spring Boot 不会自动读取 .env, 必须在 ApplicationContext 创建前手动加载。
     * 支持: KEY=VALUE, KEY="VALUE", KEY='VALUE', 忽略 # 注释和空行
     */
    private static void loadEnvFile() {
        Path envPath = Paths.get(System.getProperty("user.dir"), ".env");
        if (!Files.exists(envPath)) {
            System.out.println("未找到 .env 文件, 将使用 application.properties 默认值。");
            return;
        }
        try {
            for (String line : Files.readAllLines(envPath)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                int eq = line.indexOf('=');
                if (eq == -1) continue;
                String key = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();
                // 去除引号
                if ((value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'"))) {
                    value = value.substring(1, value.length() - 1);
                }
                // 只设置尚未设置的属性 (环境变量优先级更高)
                if (System.getProperty(key) == null) {
                    System.setProperty(key, value);
                }
            }
            System.out.println("已加载 .env 文件: " + envPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
    }
}
