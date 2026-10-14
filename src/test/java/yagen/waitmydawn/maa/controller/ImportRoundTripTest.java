package yagen.waitmydawn.maa.controller;

import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.*;
import tools.jackson.databind.node.ObjectNode;
import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.service.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportRoundTripTest {
    ObjectMapper mapper = new ObjectMapper();
    ModrinthApiClient api;
    MrpackParser parser;
    ObjectNode index, metadata;
    @BeforeEach void setup() {
        api = mock(ModrinthApiClient.class); parser = new MrpackParser(api);
        metadata = mapper.createObjectNode().put("filename", "old-secondary.jar").put("url", "https://cdn.modrinth.com/data/P1/versions/V1/old-secondary.jar").put("size", 123);
        metadata.putObject("hashes").put("sha1", "a".repeat(40)).put("sha512", "b".repeat(128));
        var version = mapper.createObjectNode().put("id", "V1").put("project_id", "P1").put("version_number", "old");
        version.putArray("game_versions").add("1.21.1"); version.putArray("loaders").add("neoforge"); version.putArray("dependencies");
        var primary = metadata.deepCopy().put("filename", "different-primary.jar").put("primary", true);
        version.putArray("files").add(primary).add(metadata);
        when(api.getVersionById("V1")).thenReturn(version);
        var project = mapper.createObjectNode().put("id", "P1").put("slug", "fixture").put("title", "Fixture");
        when(api.getProjectInfo("P1")).thenReturn(project);
        when(api.getProjectInfo("fixture")).thenReturn(project);
        index = mapper.createObjectNode().put("game", "minecraft").put("formatVersion", 1).put("name", "索引包名").put("versionId", "1");
        index.putObject("dependencies").put("minecraft", "1.21.1").put("neoforge", "21.1.42");
        var file = index.putArray("files").addObject().put("path", "mods/old-secondary.jar").put("fileSize", 123);
        file.set("hashes", metadata.path("hashes").deepCopy());
        file.putArray("downloads").add(metadata.path("url").asText());
        file.putObject("env").put("client", "required").put("server", "unsupported");
    }
    byte[] zip(JsonNode root, String extra) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("modrinth.index.json")); zip.write(mapper.writeValueAsBytes(root)); zip.closeEntry();
            if (extra != null) { zip.putNextEntry(new ZipEntry(extra)); zip.write("{}".getBytes()); zip.closeEntry(); }
        }
        return out.toByteArray();
    }
    @Test void importPreviewAndExportKeepSecondaryFileHashesEnvironmentAndLoaderVersion() throws Exception {
        var imported = parser.parse(parser.readIndex(new MockMultipartFile("file", "other-name.mrpack", "application/zip", zip(index, "maa-diagnostics.json"))));
        assertEquals("索引包名", imported.name());
        var graph = new DependencyGraph(); graph.allSlugs.add("fixture"); graph.selectedVersions.put("fixture", api.getVersionById("V1"));
        var engine = mock(DependencyEngine.class);
        when(engine.resolveCachedResult(eq(Set.of("vid:V1")), eq("neoforge"), eq("1.21.1"), isNull()))
                .thenReturn(new ResolutionResult(graph, ResolutionResult.Status.COMPLETE, Set.of("fixture"), List.of(), List.of(), 500, false));
        var users = mock(UserController.class); when(users.validateToken("one")).thenReturn(1L);
        var loaders = mock(LoaderVersionService.class);
        when(loaders.supports("neoforge", "1.21.1")).thenReturn(true);
        var knowledge = mock(KnowledgeDb.class);
        when(knowledge.getActiveRules(anyString(), any())).thenReturn(List.of());
        var controller = new ModpackController(engine, api, mapper, knowledge, null, parser, loaders, new PackManifestService(), users);
        var request = new ModpackController.ModpackRequest(); request.name = imported.name(); request.mcVersion = "1.21.1";
        request.loader = "neoforge"; request.modSlugs = imported.slugs(); request.importedPack = imported.importedPack();
        var preview = controller.previewPack("one", request);
        assertEquals(200, preview.getStatusCode().value());
        assertEquals("V1", preview.getBody().path("nodes").get(0).path("versionId").asText());
        var build = new ModpackController.BuildRequest(); build.name = imported.name(); build.loader = request.loader; build.mcVersion = request.mcVersion;
        build.manifestId = preview.getBody().path("manifestId").asText(); build.selectedIds = List.of("P1");
        var exported = controller.buildPack(build, "one"); assertEquals(200, exported.getStatusCode().value());
        try (var archive = new ZipInputStream(new ByteArrayInputStream(exported.getBody()))) {
            archive.getNextEntry(); var actual = mapper.readTree(archive.readAllBytes());
            assertEquals(index.path("name"), actual.path("name"));
            assertEquals(index.path("dependencies"), actual.path("dependencies"));
            assertEquals(index.path("files"), actual.path("files"));
        }
        verify(api, never()).getLatestVersion(anyString(), anyString(), anyString());
        verify(loaders, never()).resolve(anyString(), anyString());
    }
    @Test void hashAndEnvironmentMismatchAreRejectedInsteadOfUpgraded() {
        ((ObjectNode) index.path("files").get(0).path("hashes")).put("sha1", "0".repeat(40));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(index));
        setup(); ((ObjectNode) index.path("dependencies")).put("minecraft", "1.20.1");
        assertThrows(IllegalArgumentException.class, () -> parser.parse(index));
    }
    @Test void externalFilesAndOverridesAfterIndexCannotBeSilentlyDropped() throws Exception {
        assertThrows(IOException.class, () -> parser.readIndex(new MockMultipartFile("file", zip(mapper.createArrayNode(), null))));
        var invalidFormat = index.deepCopy().put("formatVersion", "1");
        assertThrows(IOException.class, () -> parser.readIndex(new MockMultipartFile("file", zip(invalidFormat, null))));
        assertThrows(IOException.class, () -> parser.readIndex(new MockMultipartFile("file", zip(index, "overrides/config/settings.json"))));
        ((tools.jackson.databind.node.ArrayNode) index.path("files").get(0).path("downloads")).set(0, mapper.getNodeFactory().stringNode("https://example.org/fixture.jar"));
        assertThrows(IllegalArgumentException.class, () -> parser.parse(index));
    }
    @Test void versionPinsChangeSnapshotIdentityAndAreIndependentOfFileOrder() {
        var imported = parser.parse(index).importedPack();
        String pinned = PackSnapshotHash.id("neoforge", "1.21.1", "fixture", "", null, imported);
        assertNotEquals(PackSnapshotHash.id("neoforge", "1.21.1", "fixture", ""), pinned);
        var reloaded = mapper.readValue(mapper.writeValueAsString(imported), ImportedPack.class);
        assertEquals(pinned, PackSnapshotHash.id("neoforge", "1.21.1", "fixture", "", null, reloaded));
    }
}
