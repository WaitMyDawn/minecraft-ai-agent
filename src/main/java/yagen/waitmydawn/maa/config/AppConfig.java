package yagen.waitmydawn.maa.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching // 🔥 开启 Spring Cache 缓存机制
public class AppConfig {

    // 配置高性能本地缓存，防止短时间内被 Modrinth API 封锁
    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();
        // 设置缓存的过期时间和最大容量
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .expireAfterWrite(60, TimeUnit.MINUTES) // 缓存有效期 10 分钟
                .maximumSize(10000)); // 最多存储 5000 条结果
        return cacheManager;
    }

    // 引入 Spring Boot 3.2+ 现代 HTTP 客户端 RestClient
    @Bean
    public RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("User-Agent", "MAA-Pro-Agent/5.0 (contact@example.com)")
                .build();
    }
}