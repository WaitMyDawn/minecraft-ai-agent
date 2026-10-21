package yagen.waitmydawn.maa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;

class EnvironmentFilePrecedenceTest {
    @TempDir Path directory;

    @Test void externalConfigurationWinsAndFileStillSuppliesMissingDefaults() throws Exception {
        var fixture = directory.resolve("configuration-fixture.txt");
        Files.writeString(fixture, """
                # Synthetic configuration only
                MAA_FIXTURE_MODE=file-choice
                MAA_FIXTURE_LIMIT=12
                MAA_FIXTURE_EMPTY=file-choice
                MAA_FIXTURE_DEFAULT="quoted fallback"
                """);
        var properties = new Properties();
        properties.setProperty("MAA_FIXTURE_LIMIT", "25");
        var external = Map.of("MAA_FIXTURE_MODE", "external-choice", "MAA_FIXTURE_LIMIT", "20", "MAA_FIXTURE_EMPTY", "");
        MinecraftAiAgentApplication.loadEnvFile(fixture, properties, external);
        assertFalse(properties.containsKey("MAA_FIXTURE_MODE"));
        assertFalse(properties.containsKey("MAA_FIXTURE_EMPTY"));
        assertEquals("25", properties.getProperty("MAA_FIXTURE_LIMIT"));
        assertEquals("quoted fallback", properties.getProperty("MAA_FIXTURE_DEFAULT"));

        var spring = new StandardEnvironment();
        spring.getPropertySources().replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
                new PropertiesPropertySource("systemProperties", properties));
        spring.getPropertySources().replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource("systemEnvironment", Map.copyOf(external)));
        spring.getPropertySources().addLast(new MapPropertySource("fixture-bindings", Map.of(
                "fixture.mode", "${MAA_FIXTURE_MODE}", "fixture.limit", "${MAA_FIXTURE_LIMIT}",
                "fixture.empty", "${MAA_FIXTURE_EMPTY:default}", "fixture.fallback", "${MAA_FIXTURE_DEFAULT}")));
        assertEquals("external-choice", spring.getProperty("fixture.mode"));
        assertEquals("25", spring.getProperty("fixture.limit"));
        assertEquals("", spring.getProperty("fixture.empty"));
        assertEquals("quoted fallback", spring.getProperty("fixture.fallback"));
    }

    @Test void absentFileLeavesInjectedConfigurationUntouched() {
        var properties = new Properties();
        properties.setProperty("MAA_FIXTURE_MODE", "jvm-choice");
        MinecraftAiAgentApplication.loadEnvFile(directory.resolve("absent-fixture.txt"), properties, Map.of());
        assertEquals(Map.of("MAA_FIXTURE_MODE", "jvm-choice"), properties);
    }
}
