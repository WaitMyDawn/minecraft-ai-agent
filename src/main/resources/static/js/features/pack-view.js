// 装配台打包（buildPack）+ 知识库视图（openDbView / groupedDbData）。
//
// 从 index.html 的 setup 里整体搬出来的（原 1356-1413），逻辑原样保留。
// ⚠️ buildPack 的偏好回填在 finally 里：失败或未选中任何模组时也会打 update-on-build ——
//    这是原有行为（会把失败的一次也算成构建次数），本轮按"零行为变化"保留，要改请单独说。
import {authHeader} from '../api.js';
import {authUser, currentView, dbFilterLoader, dbFilterModA, dbFilterVersion, dbRules, isBuilding, packData, previewData, selectedMods} from '../store.js';
import {loadPrefs, refreshDbRules} from './prefs.js';
import {computed, watch} from 'vue';

export const buildPack = async () => {
    try {
        if (selectedMods.value.size === 0) return alert("请至少选中一个模组！");
        isBuilding.value = true;
        // P6-C：只回传 manifestId + 勾选的节点 id，文件由服务端按快照取
        // （这样导出的一定是预览时那一份，且客户端无法塞入任意文件对象）
        const selectedIds = previewData.value.nodes
            .filter(n => selectedMods.value.has(n.id)).map(n => n.id);
        try {
            const res = await fetch('/api/modpack/build', {
                method: 'POST', headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({...packData, manifestId: previewData.value.manifestId, selectedIds})
            });
                    if (!res.ok) {
                        // 服务端现在会把原因放在响应体里（快照过期 / 这个 MC 版本没有维护加载器版本），
                        // 优先显示它 —— 否则用户只看到"打包失败，请重试"，无从下手。
                        let detail = '';
                        try { detail = ((await res.text()) || '').trim(); } catch (e) { /* 读不到就走兜底 */ }
                        alert(detail || (res.status === 400
                            ? "清单快照已过期，请重新打开构筑台再打包。"
                            : "打包失败（HTTP " + res.status + "），请重试。"));
                        return;
                    }
            const blob = await res.blob();
            const a = document.createElement('a');
            a.href = window.URL.createObjectURL(blob);
            a.download = `${packData.name}.mrpack`;
            a.click();
        } finally {
            isBuilding.value = false;
        }
    } finally {
        // 构建后回填偏好（原 buildPackWithPref 包装体内联）
        if (authUser.value && packData.modSlugs.length > 0) {
            fetch('/api/prefs/update-on-build', {
                method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
                body: JSON.stringify({slugs: packData.modSlugs})
            }).finally(() => loadPrefs());
        }
    }
};

export const openDbView = async () => {
    currentView.value = 'db';
    try {
        // 带上 token：后端要用它算 myVote（我在这条规则上投的是认可还是不认可）
        await refreshDbRules();
    } catch (e) {
        alert("获取知识库失败");
    }
};

// ==================== 筛选 + 排序 + 分组 ====================
//
// 排序放在读层（后端 getAllRules 也排了一遍）：这里再排一次是为了"显示顺序 = 数据顺序"，
// 不依赖接口返回的顺序 —— 筛选之后还要保持同样的顺序，不能靠运气。

const cmp = (a, b) => String(a || '').localeCompare(String(b || ''), 'en', {sensitivity: 'base'});

/** 环境串 → 加载器 / MC 版本（加载器名里不含 '-'，所以按第一个 '-' 切就够） */
export const splitEnv = (env) => {
    const s = String(env || '');
    const i = s.indexOf('-');
    return i < 0 ? {loader: s, version: ''} : {loader: s.slice(0, i), version: s.slice(i + 1)};
};

/** 筛选下拉：只列"当前规则里真实存在"的加载器（选了就一定出结果） */
export const dbLoaderOptions = computed(() => {
    const set = new Set();
    dbRules.value.forEach(r => {
        const {loader} = splitEnv(r.environment);
        if (loader) set.add(loader);
    });
    return [...set].sort(cmp);
});

/** 筛选下拉：版本选项随所选加载器联动（没选加载器时列全部版本） */
export const dbVersionOptions = computed(() => {
    const set = new Set();
    dbRules.value.forEach(r => {
        const {loader, version} = splitEnv(r.environment);
        if (!version) return;
        if (dbFilterLoader.value && loader !== dbFilterLoader.value) return;
        set.add(version);
    });
    return [...set].sort(cmp);
});

// 换了加载器，原来选的版本多半就不在选项里了 → 直接清掉，避免"筛选条件看着还在、其实一条都不匹配"
watch(dbFilterLoader, () => { dbFilterVersion.value = ''; });

export const resetDbFilters = () => {
    dbFilterLoader.value = '';
    dbFilterVersion.value = '';
    dbFilterModA.value = '';
};

export const groupedDbData = computed(() => {
    const loaderFilter = dbFilterLoader.value;
    const versionFilter = dbFilterVersion.value;
    const modAFilter = (dbFilterModA.value || '').trim().toLowerCase();

    // 先按 环境 → 关系类型 → 主模组 → 指向模组 排好（字典序），再按这个顺序建分组对象
    const sorted = [...dbRules.value].sort((a, b) =>
        cmp(a.environment, b.environment) || cmp(a.relationType, b.relationType)
        || cmp(a.modA, b.modA) || cmp(a.modB, b.modB));

    const groups = {};
    sorted.forEach(rule => {
        const {loader, version} = splitEnv(rule.environment);
        if (loaderFilter && loader !== loaderFilter) return;
        if (versionFilter && version !== versionFilter) return;
        if (modAFilter && !String(rule.modA || '').toLowerCase().includes(modAFilter)) return;

        if (!groups[rule.environment]) groups[rule.environment] = {};
        if (!groups[rule.environment][rule.relationType]) groups[rule.environment][rule.relationType] = {};
        if (!groups[rule.environment][rule.relationType][rule.modA]) groups[rule.environment][rule.relationType][rule.modA] = [];
        groups[rule.environment][rule.relationType][rule.modA].push(rule);
    });
    return groups;
});

// ==================== 用户来源的配色 ====================

/** 生效 / 未生效的用户来源配色（用户指定，别随手改） */
export const DB_COLORS = {
    effective: '#9C1A15',    // 用户来源，且已经生效（净值 ≥ 3）
    pending: '#EA6560',      // 含未生效的用户来源
    pendingText: '#3F0A08'   // 浅红上的字：白字对比度只有 3.2，用深色才看得清
};

/**
 * 主模组（分组标题）的状态 —— 不展开是看不到指向模组的，所以颜色要先落在主模组上：
 * 全组用户来源都生效 → 'effective'；只要有一条用户来源没生效 → 'pending'；没有用户来源 → ''。
 */
export const groupState = (rules) => {
    const userRules = (rules || []).filter(r => r.sourceType === 'USER_FEEDBACK');
    if (userRules.length === 0) return '';
    return userRules.every(r => r.effective) ? 'effective' : 'pending';
};

export const groupModAStyle = (rules) => {
    const state = groupState(rules);
    if (state === 'effective') return {background: DB_COLORS.effective, color: '#fff'};
    if (state === 'pending') return {background: DB_COLORS.pending, color: DB_COLORS.pendingText};
    return {};
};

/** 单条规则（"指向 ➡️ xxx" 那行）的配色：展开后按各自的来源着色 */
export const ruleStyle = (rule) => {
    if (!rule || rule.sourceType !== 'USER_FEEDBACK') return {};
    return rule.effective
        ? {background: DB_COLORS.effective, color: '#fff'}
        : {background: DB_COLORS.pending, color: DB_COLORS.pendingText};
};
