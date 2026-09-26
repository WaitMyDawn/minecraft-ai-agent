// 装配台打包（buildPack）+ 知识库视图（openDbView / groupedDbData）。
//
// 从 index.html 的 setup 里整体搬出来的（原 1356-1413），逻辑原样保留。
// ⚠️ buildPack 的偏好回填在 finally 里：失败或未选中任何模组时也会打 update-on-build ——
//    这是原有行为（会把失败的一次也算成构建次数），本轮按"零行为变化"保留，要改请单独说。
import {authHeader} from '../api.js';
import {authUser, currentView, dbRules, isBuilding, packData, previewData, selectedMods} from '../store.js';
import {loadPrefs} from './prefs.js';
import {computed} from 'vue';

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
        const res = await fetch('/api/knowledge');
        dbRules.value = await res.json();
    } catch (e) {
        alert("获取知识库失败");
    }
};

export const groupedDbData = computed(() => {
    const groups = {};
    dbRules.value.forEach(rule => {
        if (!groups[rule.environment]) groups[rule.environment] = {};
        if (!groups[rule.environment][rule.relationType]) groups[rule.environment][rule.relationType] = {};
        if (!groups[rule.environment][rule.relationType][rule.modA]) groups[rule.environment][rule.relationType][rule.modA] = [];
        groups[rule.environment][rule.relationType][rule.modA].push(rule);
    });
    return groups;
});
