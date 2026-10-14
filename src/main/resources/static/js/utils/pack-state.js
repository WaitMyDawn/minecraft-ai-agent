// 打包输入状态（界面上说的"这一版"）的读写与比较。
//
// 状态形状包含清单、环境、显式排除及 constraints，与后端 packState 列的
// JSON 完全一致。放在 utils 而不是某个 feature 里：对话历史（auth）、装配台（delete-flow）、
// 版本流水（pack-snapshot）都要用它，而这几个模块之间不该互相 import。

/** 数组 → 后端要的逗号分隔串（去空、去空格） */
export const csv = (list) => (Array.isArray(list) ? list : [])
    .map(s => String(s).trim()).filter(Boolean).join(',');

/** 逗号分隔串 → 数组 */
export const parseCsv = (text) => String(text || '')
    .split(',').map(s => s.trim()).filter(Boolean);

/** 独立拷贝约束，防止修改当前版本时连带改写历史快照。旧版本没有约束时使用 null。 */
const copyConstraints = (value) => value ? JSON.parse(JSON.stringify(value)) : null;
const copyAssembly = value => {
    const copy = copyConstraints(value);
    if (!copy) return null;
    copy.roots = [...new Set(copy.roots || [])].sort();
    copy.versions = Object.fromEntries(Object.entries(copy.versions || {}).sort(([a], [b]) => a.localeCompare(b)));
    copy.origins = Object.fromEntries(Object.entries(copy.origins || {}).sort(([a], [b]) => a.localeCompare(b))
        .map(([slug, roles]) => [slug, [...new Set(roles)].sort()]));
    return copy;
};
/** 手动选择授予根身份；已有依赖被点名时提升为显式根，不改其选版。 */
export const markExplicitRoots = (work, slugs) => {
    if (!work.assembly) return false;
    const assembly = copyAssembly(work.assembly);
    let changed = false;
    for (const slug of slugs) {
        if (!assembly.roots.includes(slug)) { assembly.roots.push(slug); changed = true; }
        assembly.origins[slug] = [...new Set([...(assembly.origins[slug] || []).filter(r => r !== 'DEPENDENCY'), 'EXPLICIT_ADD'])].sort();
    }
    work.assembly = copyAssembly(assembly);
    return changed;
};
const copyImported = (value) => {
    const copy = copyConstraints(value);
    if (copy?.files) copy.files = copy.files.map(file => ({...file,
        env: Object.fromEntries(Object.entries(file.env || {}).sort(([a], [b]) => a.localeCompare(b)))}))
        .sort((a, b) => a.slug.localeCompare(b.slug));
    return copy;
};

/** 工作区对象 → 状态对象（拷贝，别把 reactive 引用存进历史） */
export const packStateOf = (work) => ({
    name: work.name || '',
    mcVersion: work.mcVersion || '',
    loader: work.loader || '',
    modSlugs: [...(work.modSlugs || [])],
    excludedSlugs: [...(work.excludedSlugs || [])],
    constraints: copyConstraints(work.constraints),
    importedPack: copyImported(work.importedPack),
    assembly: copyAssembly(work.assembly),
});

/** 后端 packState（JSON 串或对象）→ 状态对象；解析不了返回 null（调用方退回旧行为） */
export const readPackState = (json) => {
    if (!json) return null;
    try {
        const raw = typeof json === 'string' ? JSON.parse(json) : json;
        if (!raw || typeof raw !== 'object') return null;
        return {
            name: raw.name || '',
            mcVersion: raw.mcVersion || '',
            loader: raw.loader || '',
            modSlugs: Array.isArray(raw.modSlugs) ? raw.modSlugs.map(String) : parseCsv(raw.modSlugs),
            excludedSlugs: Array.isArray(raw.excludedSlugs) ? raw.excludedSlugs.map(String) : parseCsv(raw.excludedSlugs),
            constraints: copyConstraints(raw.constraints),
            importedPack: copyImported(raw.importedPack),
            assembly: copyAssembly(raw.assembly),
        };
    } catch (e) {
        return null;
    }
};

/**
 * 状态等价。清单按集合比较（顺序无关、去重），与后端 PackSnapshotHash 的规范化口径一致 ——
 * 否则会出现"前端认为变了、后端算出同一个哈希"的错位。
 */
export const stateEquals = (a, b) => {
    if (!a || !b) return false;
    return a.name === b.name
        && a.mcVersion === b.mcVersion
        && a.loader === b.loader
        && sameSet(a.modSlugs, b.modSlugs)
        && sameSet(a.excludedSlugs, b.excludedSlugs)
        && JSON.stringify(a.constraints || null) === JSON.stringify(b.constraints || null)
        && JSON.stringify(copyImported(a.importedPack)) === JSON.stringify(copyImported(b.importedPack))
        && JSON.stringify(copyAssembly(a.assembly)) === JSON.stringify(copyAssembly(b.assembly));
};

const sameSet = (x, y) => {
    const a = [...new Set((x || []).map(String))].sort();
    const b = [...new Set((y || []).map(String))].sort();
    return a.length === b.length && a.every((v, i) => v === b[i]);
};

/** 状态写回工作区（逐字段赋值，保持工作区对象的 reactive 引用） */
export const applyPackState = (work, state) => {
    if (!state) return;
    work.name = state.name || 'MAA-Pack';
    if (state.mcVersion) work.mcVersion = state.mcVersion;
    if (state.loader) work.loader = state.loader;
    work.modSlugs = [...(state.modSlugs || [])];
    work.excludedSlugs = [...(state.excludedSlugs || [])];
    work.constraints = copyConstraints(state.constraints);
    work.importedPack = copyImported(state.importedPack);
    work.assembly = copyAssembly(state.assembly);
};

/**
 * 把版本号与状态挂到消息上。
 *
 * <p>必须成对挂：只挂 snapshotId 的话，界面能显示出 #hash，但点击时读不到 packState，
 * 于是按钮会退化成"打开当前图谱" —— 表现就是"每个按钮都指向最新一版"。
 * 库里的消息由后端返回这两个字段，内存里新建的消息只能由这里补齐。
 */
export const attachVersion = (msg, snapshotId, state) => {
    if (!msg) return msg;
    if (snapshotId) msg.snapshotId = snapshotId;
    if (state) msg.packState = JSON.stringify(state);
    return msg;
};
