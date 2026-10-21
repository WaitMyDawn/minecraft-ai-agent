package yagen.waitmydawn.maa;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;
import yagen.waitmydawn.maa.logging.MaaLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Properties;

@SpringBootApplication
@EnableCaching
@EnableScheduling
public class MinecraftAiAgentApplication {

    public static void main(String[] args) {
        loadEnvFile();
        MaaLog.install();
        SpringApplication.run(MinecraftAiAgentApplication.class, args);
    }

    /**
     * 将项目根目录的 .env 作为系统属性的补充，不覆盖已有JVM参数或环境变量。
     * Spring Boot 不会自动读取 .env, 必须在 ApplicationContext 创建前手动加载。
     * 支持: KEY=VALUE, KEY="VALUE", KEY='VALUE', 忽略 # 注释和空行
     */
    private static void loadEnvFile() {
        loadEnvFile(Paths.get(System.getProperty("user.dir"), ".env"), System.getProperties(), System.getenv());
    }

    static void loadEnvFile(Path envPath, Properties properties, Map<String, String> environment) {
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
                // 空环境值也代表显式部署配置，不能被文件中的旧值重新启用。
                if (properties.getProperty(key) == null && !environment.containsKey(key)) {
                    properties.setProperty(key, value);
                }
            }
            System.out.println("已加载 .env 文件: " + envPath.toAbsolutePath());
        } catch (IOException e) {
            System.err.println("读取 .env 文件失败: " + e.getMessage());
        }
    }
}
