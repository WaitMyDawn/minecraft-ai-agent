// 智能抽卡器（Modrinth 盲盒）：类别清单、筛选条件、翻页/跳页、直接添加 slug、选中确认入库。
//
// 状态在 store.js；取数走 /api/modpack/suggest；加入整合包后由 delete-flow.openLab 重画图谱。
// 登录态建立后加载类别；账号切换时重新获取。
//
// 单向依赖：本模块 -> store / api / prefs（loadPrefs、loadBlacklist）/ delete-flow（openLab）。
// 反方向没有 import，所以不存在循环。

import {apiFetch, authHeader} from '../api.js';
import {watch} from 'vue';
import {authUser, availableCategories, categoryGloss, isSuggesting, manualAddSlug, nodesMap, packData, pageJumpInput, selectedMods, selectedSuggests, sessionUuid, showSuggestBoard, suggestForm, suggestMode, suggestedMods} from '../store.js';
import {openLab} from './delete-flow.js';
import {markExplicitRoots} from '../utils/pack-state.js';
import {loadBlacklist, loadPrefs} from './prefs.js';
// == 新增智能衍生变量 ==

// 🏷️ 类别清单以 /api/meta/categories 为权威源（后端 CategoryRegistry）；
// 这里只保留一份兜底副本，接口不可用时界面仍能正常工作。
const loadCategories = async () => {
    try {
        const res = await apiFetch('/api/meta/categories', {headers: authHeader()});
        const data = await res.json();
        if (data && Array.isArray(data.categories) && data.categories.length > 0) {
            availableCategories.value = data.categories;
            categoryGloss.value = data.gloss || {};
        }
    } catch (e) {
        console.warn('获取类别清单失败，使用内置兜底列表', e);
    }
};
watch(authUser, user => { if (user) loadCategories(); }, {immediate: true});

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
    if (packData.constraints) packData.constraints.exclude = packData.constraints.exclude.filter(s => s !== slug);
    const promoted = markExplicitRoots(packData, [slug]);
    if (!packData.modSlugs.includes(slug)) {
        packData.modSlugs.push(slug);
        openLab(); // 重新触发全量图谱绘制
    } else if (promoted) openLab();
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
//
// 建池是重活（顺序模式要往上挖几十页 Modrinth），所以两层防重复：
// 前端 isSuggesting 守卫挡住同一次点击风暴，服务端的 in-flight 登记挡住多标签页/刷新重发。
// 运行期间这个按钮会变成"取消"（见模板），取消请求把服务端的取消标志置位，
// 建池循环在下一页前收工并把这半结果返回 —— 所以下面的逻辑要能区分 cancelled。
let suggestionRequest;
export const fetchSuggestions = async () => {
    if (isSuggesting.value) return;          // 已在跑：再点不该再发一次建池
    isSuggesting.value = true;
    const request = new AbortController();
    suggestionRequest = request;
    try {
        const req = {
            loader: packData.loader,
            mcVersion: packData.mcVersion,
            categories: suggestForm.categories,
            minDownloads: suggestForm.minDownloads,
            maxDownloads: suggestForm.maxDownloads,
            sortMethod: suggestForm.sortType,
            page: suggestForm.page,
            currentSlugs: Array.from(selectedMods.value).map(id => nodesMap.value[id]?.slug).filter(Boolean),
            clientId: sessionUuid.value
        };
        const res = await apiFetch('/api/modpack/suggest', {
            method: 'POST', signal: request.signal, headers: {'Content-Type': 'application/json', ...authHeader()}, body: JSON.stringify(req)
        });
        const d = await res.json().catch(() => ({}));
        if (res.status === 409) {
            // 别的标签页（或上一次请求还在）正在建池：不报错，只把按钮切回取消态等它结束
            alert(d.error || '已有一个侦测任务在进行中');
            return;
        }
        if (!res.ok || d.error) { alert(d.error || '侦测失败（HTTP ' + res.status + '）'); return; }
        suggestedMods.value = Array.isArray(d.mods) ? d.mods : [];
        if (d.cancelled) console.log('侦测已取消，以下为已建成的部分结果');
    } catch(e) {
        if (!request.signal.aborted) alert(e.message || "衍生请求失败！");
    } finally { isSuggesting.value = false; if (suggestionRequest === request) suggestionRequest = null; }
};

/** 取消正在进行的建池：置位服务端标志，跑着的那次请求会带着已建成的部分结果返回 */
export const cancelSuggestions = async () => {
    if (!isSuggesting.value) return;
    suggestionRequest?.abort();
    try {
        await apiFetch('/api/modpack/suggest/cancel', {
            method: 'POST',
            headers: {'Content-Type': 'application/json', ...authHeader()},
            body: JSON.stringify({clientId: sessionUuid.value})
        });
    } catch (e) { /* 取消失败也没关系：前端等原请求结束即可 */ }
};

// 5. 确认添加
export const confirmAddSuggestedMods = async () => {
    if (selectedSuggests.value.size === 0) return;
    if (suggestMode.value === 'blacklist') {
        for (const slug of selectedSuggests.value) {
            await apiFetch('/api/prefs/blacklist', {
                method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
                body: JSON.stringify({slug, source: 'agent'})
            });
        }
        await loadBlacklist();
    } else if (suggestMode.value === 'prefs') {
        // 添加到模组偏好
        for (const slug of selectedSuggests.value) {
            await apiFetch('/api/prefs/mods', {
                method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
                body: JSON.stringify({slug, category: ''})
            });
        }
        await loadPrefs();
    } else {
        // 添加到整合包
        markExplicitRoots(packData, [...selectedSuggests.value]);
        selectedSuggests.value.forEach(slug => {
            if (!packData.modSlugs.includes(slug)) packData.modSlugs.push(slug);
        });
        // 用户主动加回 → 撤销这些 slug 的删除排除
        const added = new Set(selectedSuggests.value);
        packData.excludedSlugs = (packData.excludedSlugs || []).filter(s => !added.has(s));
        if (packData.constraints) packData.constraints.exclude = packData.constraints.exclude.filter(s => !added.has(s));
        openLab();
    }
    showSuggestBoard.value = false;
};
