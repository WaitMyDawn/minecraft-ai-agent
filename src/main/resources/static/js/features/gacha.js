// 智能抽卡器（Modrinth 盲盒）：类别清单、筛选条件、翻页/跳页、直接添加 slug、选中确认入库。
//
// 从 index.html 的 setup 里整体搬出来的（原 1414-1545），逻辑原样保留。
// ⚠️ loadCategories() 原本在 setup 里被立即调用一次，搬来后仍是模块顶层立即调用 ——
//    它只是异步拉 /api/meta/categories 写 store，不依赖挂载，时序上更早一点点（无害）。
//
// 单向依赖：本模块 -> store / api / prefs（loadPrefs、loadBlacklist）/ delete-flow（openLab）。
// 反方向没有 import，所以不存在循环。

import {authHeader} from '../api.js';
import {availableCategories, categoryGloss, isSuggesting, manualAddSlug, nodesMap, packData, pageJumpInput, selectedMods, selectedSuggests, showSuggestBoard, suggestForm, suggestMode, suggestedMods} from '../store.js';
import {openLab} from './delete-flow.js';
import {loadBlacklist, loadPrefs} from './prefs.js';
// == 新增智能衍生变量 ==

// 🏷️ 类别清单以 /api/meta/categories 为权威源（后端 CategoryRegistry）；
// 这里只保留一份兜底副本，接口不可用时界面仍能正常工作。
const loadCategories = async () => {
    try {
        const res = await fetch('/api/meta/categories');
        const data = await res.json();
        if (data && Array.isArray(data.categories) && data.categories.length > 0) {
            availableCategories.value = data.categories;
            categoryGloss.value = data.gloss || {};
        }
    } catch (e) {
        console.warn('获取类别清单失败，使用内置兜底列表', e);
    }
};
loadCategories();

// 🔥 新增快速跳转相关状态

export const toggleSuggestCategory = (cat) => {
    const idx = suggestForm.categories.indexOf(cat);
    if (idx === -1) suggestForm.categories.push(cat);
    else suggestForm.categories.splice(idx, 1);
};

export const suggestPageUp = () => { suggestForm.page++; fetchSuggestions(); };
export const suggestPageDown = () => { if (suggestForm.page > 0) { suggestForm.page--; fetchSuggestions(); } };
export const resetSuggestPage = () => { suggestForm.page = 0; };

// 🔥 新增：页码跳转逻辑
export const jumpToPage = () => {
    const target = parseInt(pageJumpInput.value);
    if (!isNaN(target) && target > 0) {
        suggestForm.page = target - 1; // 内部页码从 0 开始
        fetchSuggestions();
    } else {
        alert("请输入有效的页码！");
    }
};

// 1. 手动添加逻辑
export const directAddMod = () => {
    const slug = manualAddSlug.value.trim().toLowerCase();
    if (!slug) return;
    // 用户手动加回 → 撤销该 slug 的删除排除
    packData.excludedSlugs = (packData.excludedSlugs || []).filter(s => s !== slug);
    if (!packData.modSlugs.includes(slug)) {
        packData.modSlugs.push(slug);
        openLab(); // 重新触发全量图谱绘制
    }
    manualAddSlug.value = '';
};

// 2. 呼出衍生面板
export const openSuggestBoard = () => {
    showSuggestBoard.value = true;
    suggestMode.value = 'pack'; // 默认: 添加到整合包
    suggestedMods.value = [];
    selectedSuggests.value = new Set();
};

// 从偏好页打开: 选中的模组添加到偏好
export const openSuggestBoardForPref = () => {
    showSuggestBoard.value = true;
    suggestMode.value = 'prefs';
    suggestedMods.value = [];
    selectedSuggests.value = new Set();
};

// 3. 点击卡片选择
export const toggleSuggestSelection = (slug) => {
    const newSet = new Set(selectedSuggests.value);
    if (newSet.has(slug)) newSet.delete(slug); else newSet.add(slug);
    selectedSuggests.value = newSet;
};

// 4. 调用后端搜索
export const fetchSuggestions = async () => {
    isSuggesting.value = true;
    try {
        const req = {
            loader: packData.loader,
            mcVersion: packData.mcVersion,
            categories: suggestForm.categories,
            minDownloads: suggestForm.minDownloads,
            maxDownloads: suggestForm.maxDownloads,
            sortMethod: suggestForm.sortType,
            page: suggestForm.page,
            currentSlugs: Array.from(selectedMods.value).map(id => nodesMap.value[id]?.slug).filter(Boolean)
        };
        const res = await fetch('/api/modpack/suggest', {
            method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(req)
        });
        suggestedMods.value = await res.json();
    } catch(e) {
        alert("衍生请求失败！");
    } finally { isSuggesting.value = false; }
};

// 5. 确认添加
export const confirmAddSuggestedMods = async () => {
    if (selectedSuggests.value.size === 0) return;
    if (suggestMode.value === 'blacklist') {
        for (const slug of selectedSuggests.value) {
            await fetch('/api/prefs/blacklist', {
                method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
                body: JSON.stringify({slug, source: 'agent'})
            });
        }
        await loadBlacklist();
    } else if (suggestMode.value === 'prefs') {
        // 添加到模组偏好
        for (const slug of selectedSuggests.value) {
            await fetch('/api/prefs/mods', {
                method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
                body: JSON.stringify({slug, category: ''})
            });
        }
        await loadPrefs();
    } else {
        // 添加到整合包
        selectedSuggests.value.forEach(slug => {
            if (!packData.modSlugs.includes(slug)) packData.modSlugs.push(slug);
        });
        // 用户主动加回 → 撤销这些 slug 的删除排除
        const added = new Set(selectedSuggests.value);
        packData.excludedSlugs = (packData.excludedSlugs || []).filter(s => !added.has(s));
        openLab();
    }
    showSuggestBoard.value = false;
};
