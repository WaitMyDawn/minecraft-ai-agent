package yagen.waitmydawn.maa;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "spring.config.location=classpath:application-test.properties")
class MinecraftAiAgentApplicationTests {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private yagen.waitmydawn.maa.service.LoaderVersionService loaderVersions;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private yagen.waitmydawn.maa.service.KnowledgeDb knowledgeDb;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private yagen.waitmydawn.maa.cache.ModrinthCacheService modrinthCache;

    @Test
    void contextLoads() {
    }

}
