package yagen.waitmydawn.maa.model.agent;

import java.util.Locale;

/** 将切换意图与当前环境合并；支持表校验和实际组装必须使用同一结果。 */
public final class BuildEnvironment {
    private BuildEnvironment() {}

    public static BuildIntent.Environment current(String mc, String loader) {
        return new BuildIntent.Environment(mc == null || mc.isBlank() ? "1.21.1" : mc.trim(),
                loader == null || loader.isBlank() ? BuildIntent.Loader.NEOFORGE
                        : BuildIntent.Loader.valueOf(loader.trim().toUpperCase(Locale.ROOT)), false);
    }

    public static BuildIntent resolve(BuildIntent intent, String mc, String loader) {
        if (intent.environment() == null) return intent;
        var inherited = current(mc, loader);
        var proposed = intent.environment();
        var effective = proposed.changeRequested() ? proposed : inherited;
        boolean changed = proposed.changeRequested()
                && (!effective.mcVersion().equals(inherited.mcVersion()) || effective.loader() != inherited.loader());
        return new BuildIntent(intent.schemaVersion(), intent.mode(), intent.reply(), intent.name(),
                new BuildIntent.Environment(effective.mcVersion(), effective.loader(), changed), intent.count(),
                intent.mustHave(), intent.add(), intent.remove(), intent.exclude(), intent.expandAddons(),
                intent.replacementPolicy(), intent.searchIntents(), intent.excludedCategories(), intent.maxDownloads(), intent.constraintChanges(), intent.requirements());
    }
}
