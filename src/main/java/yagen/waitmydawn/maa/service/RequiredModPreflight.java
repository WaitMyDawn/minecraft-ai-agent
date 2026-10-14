package yagen.waitmydawn.maa.service;

import yagen.waitmydawn.maa.model.*;
import yagen.waitmydawn.maa.model.agent.BuildIntent;
import yagen.waitmydawn.maa.runtime.ScopedExecutors;
import java.util.*;
import java.util.concurrent.*;

/** 必选项目由当前任务实时校验，不用可能陈旧的本地负面索引否决用户要求。 */
public final class RequiredModPreflight {
    private RequiredModPreflight() {}
    public static RequiredModsReport check(ModrinthApiClient api, BuildIntent intent, ImportedPack imported) {
        return check(api, intent, imported, intent.mustHave());
    }
    public static RequiredModsReport check(ModrinthApiClient api, BuildIntent intent, ImportedPack imported,
                                          Collection<String> requestedProjects) {
        String loader = intent.environment().loader().name().toLowerCase(Locale.ROOT), mc = intent.environment().mcVersion();
        Map<String, ImportedPack.File> pins = new HashMap<>();
        if (imported != null && intent.mode() == BuildIntent.Mode.EDIT && imported.loader().equals(loader) && imported.mcVersion().equals(mc))
            imported.files().forEach(file -> pins.put(file.slug(), file));
        Map<String, RequiredModsReport.Availability> found = new ConcurrentHashMap<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<Void>> tasks = new ArrayList<>();
            for (String slug : requestedProjects) tasks.add(ScopedExecutors.runAsync(() -> {
                var availability = RequiredModsReport.Availability.UNVERIFIED;
                try {
                    var pin = pins.get(slug);
                    var version = pin == null ? api.getLatestCompatibleVersionBySlug(slug, mc, loader) : api.getVersionById(pin.versionId());
                    if (version == null) availability = RequiredModsReport.Availability.NO_COMPATIBLE_VERSION;
                    else if (pin == null) availability = RequiredModsReport.Availability.AVAILABLE;
                    else if (version.path("game_versions").isArray() && version.path("loaders").isArray()
                            && version.path("project_id").asText().equals(pin.projectId())) {
                        boolean game = false, platform = false;
                        for (var v : version.path("game_versions")) if (v.asText().equals(mc)) game = true;
                        for (var v : version.path("loaders")) if (v.asText().equals(loader)) platform = true;
                        availability = game && platform ? RequiredModsReport.Availability.AVAILABLE : RequiredModsReport.Availability.NO_COMPATIBLE_VERSION;
                    }
                } catch (Exception unavailable) { /* 故障不证明项目不存在，保留 UNVERIFIED。 */ }
                found.put(slug, availability);
            }, executor));
            CompletableFuture.allOf(tasks.toArray(new CompletableFuture[0])).join();
        }
        return new RequiredModsReport(loader, mc, requestedProjects.stream().map(slug -> new RequiredModsReport.Entry(slug, found.get(slug))).toList());
    }
}
