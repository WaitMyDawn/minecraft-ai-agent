import {computed, nextTick, watch} from 'vue';
import {addingModBySlug, authUser, blacklistItems, blacklistJump, blacklistMsg, blacklistPage, blacklistSlugInput, categoryPrefs, currentView, editingBuildCountId, editingBuildCountVal, enableBlacklist, enableUserFeedbackRules, hasApiKey, keEnv, keEnvs, keModA, keModAError, keModAInfo, keModB, keModBError, keModBInfo, keMsg, keRelation, manualSlugInput, modInfoCache, modPrefJump, modPrefPage, modPrefs, modPrefSearch, modSlugAddMsg, newCatPref, profileApiKey, profileApiKeyMsg, profileUsername, profileWeight, pwMsg, pwNew, pwOld, selectedSuggests, showSuggestBoard, suggestedMods, suggestMode} from '../store.js';
import {apiFetch, authHeader} from '../api.js';

// 设置页：个人 API Key、改密码、改用户名、偏好影响权重。
//
// import 行由抽取脚本从"块内用到的标识符"自动推导，不靠人手抄——
// auth 那次就是手抄漏了一个 authHeader，而它又被空 catch 吞了，排查了两轮。


// ========== 用户认证 ==========


// ========== 设置页 ==========
export const saveApiKey = async () => {
    const key = profileApiKey.value.trim();
    if (!key) return;
    try {
        const r = await apiFetch('/api/user/profile', {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({apiKey: key})
        });
        const d = await r.json();
        hasApiKey.value = d.hasApiKey === true;
        if (authUser.value) {
            authUser.value.hasApiKey = hasApiKey.value;
            localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
        }
        profileApiKey.value = '';
        profileApiKeyMsg.value = 'API Key 已保存成功';
        setTimeout(() => profileApiKeyMsg.value = '', 3000);
    } catch(e) {
        if (e.message === '未登录') return;
        profileApiKeyMsg.value = '保存失败: ' + e.message;
    }
};
export const changePassword = async () => {
    if (!pwOld.value || !pwNew.value) return;
    pwMsg.value = '';
    try {
        const r = await apiFetch('/api/user/change-password', {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({oldPassword: pwOld.value, newPassword: pwNew.value})
        });
        const d = await r.json();
        if (d.error) { pwMsg.value = d.error; return; }
        pwMsg.value = '密码修改成功';
        pwOld.value = ''; pwNew.value = '';
        setTimeout(() => pwMsg.value = '', 3000);
    } catch(e) { if (e.message !== '未登录') pwMsg.value = '修改失败'; }
};

export const saveUsername = async () => {
    const uname = profileUsername.value.trim();
    if (!uname) return;
    await apiFetch('/api/user/profile', {
        method: 'PUT', headers: {'Content-Type': 'application/json'},
        body: JSON.stringify({username: uname})
    });
    authUser.value.username = uname;
    localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
    profileUsername.value = '';
};
export const saveProfileWeight = async () => {
    if (!authUser.value) return;
    await fetch('/api/user/profile', {
        method: 'PUT', headers: {...authHeader(), 'Content-Type': 'application/json'},
        body: JSON.stringify({preferenceWeight: String(profileWeight.value)})
    });
    authUser.value.preferenceWeight = profileWeight.value;
    localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
};

// ========== 偏好 ==========
export const filteredModPrefs = computed(() => {
    let arr = modPrefs.value;
    if (modPrefSearch.value) {
        const kw = modPrefSearch.value.toLowerCase();
        arr = arr.filter(m => m.slug.includes(kw) || (m.category||'').includes(kw) || ((modInfoCache.value[m.slug]||{}).title||'').toLowerCase().includes(kw));
    }
    return arr;
});

// ========== 分页 ==========
export const pageSize = 10;
export const modPrefTotalPages = computed(() => Math.max(1, Math.ceil(filteredModPrefs.value.length / pageSize)));
export const pagedModPrefs = computed(() => {
    const start = modPrefPage.value * pageSize;
    return filteredModPrefs.value.slice(start, start + pageSize);
});
export const filteredBlacklist = computed(() => blacklistItems.value);
export const blacklistTotalPages = computed(() => Math.max(1, Math.ceil(filteredBlacklist.value.length / pageSize)));
export const pagedBlacklist = computed(() => {
    const start = blacklistPage.value * pageSize;
    return filteredBlacklist.value.slice(start, start + pageSize);
});

// ===== 偏好列表 / 黑名单 / 规则编辑器 =====
export const prevModPrefPage = () => { if (modPrefPage.value > 0) modPrefPage.value--; };
export const nextModPrefPage = () => { if (modPrefPage.value < modPrefTotalPages.value - 1) modPrefPage.value++; };
export const jumpModPrefPage = () => {
    const t = parseInt(modPrefJump.value);
    if (!isNaN(t) && t >= 1 && t <= modPrefTotalPages.value) modPrefPage.value = t - 1;
};
export const prevBlacklistPageFn = () => { if (blacklistPage.value > 0) blacklistPage.value--; };
export const nextBlacklistPageFn = () => { if (blacklistPage.value < blacklistTotalPages.value - 1) blacklistPage.value++; };
export const jumpBlacklistPageFn = () => {
    const t = parseInt(blacklistJump.value);
    if (!isNaN(t) && t >= 1 && t <= blacklistTotalPages.value) blacklistPage.value = t - 1;
};

// Modrinth 数据缓存 (仅渲染时获取，不存储在导出文件)
export const loadModInfoCache = async (slugs) => {
    for (const slug of slugs) {
        if (modInfoCache.value[slug]) continue;
        try {
            const r = await fetch('/api/prefs/mod-info/' + slug);
            const d = await r.json();
            if (!d.error) modInfoCache.value[slug] = d;
        } catch(e) {}
    }
};

// 手动 slug 输入添加
export const addModBySlug = async () => {
    const slug = manualSlugInput.value.trim().toLowerCase();
    if (!slug) return;
    addingModBySlug.value = true; modSlugAddMsg.value = '';
    try {
        // 先验证 slug 有效
        const infoR = await fetch('/api/prefs/mod-info/' + slug);
        const info = await infoR.json();
        if (info.error) { modSlugAddMsg.value = '该 slug 不存在于 Modrinth'; return; }
        // 添加到偏好
        const r = await apiFetch('/api/prefs/mods', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({slug, source: 'user'})
        });
        const d = await r.json();
        modInfoCache.value[slug] = info;
        modSlugAddMsg.value = '成功添加: ' + slug;
        manualSlugInput.value = '';
        await loadPrefs();
    } catch(e) {
        if (e.message !== '未登录') modSlugAddMsg.value = '添加失败';
    } finally { addingModBySlug.value = false; }
};

// 编辑构建次数
export const startEditBuildCount = (mp) => {
    editingBuildCountId.value = mp.id;
    editingBuildCountVal.value = mp.buildCount;
    nextTick(() => {
        const el = document.querySelector('input[type=number][min="0"]');
        if (el) el.focus();
    });
};
export const saveBuildCount = async (mp) => {
    const val = editingBuildCountVal.value;
    editingBuildCountId.value = null;
    if (val === mp.buildCount) return;
    try {
        await apiFetch('/api/prefs/mods/' + mp.id, {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({buildCount: val})
        });
        mp.buildCount = val;
    } catch(e) { if (e.message !== '未登录') alert('更新失败'); }
};

// 切换 source (手动 ↔ 自动)
export const toggleModSource = async (mp) => {
    const newSource = mp.source === 'user' ? 'agent' : 'user';
    try {
        const r = await apiFetch('/api/prefs/mods/' + mp.id, {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({source: newSource})
        });
        const d = await r.json();
        if (d.deleted) { await loadPrefs(); return; }
        mp.source = d.source;
        mp.buildCount = d.buildCount;
    } catch(e) { if (e.message !== '未登录') alert('操作失败'); }
};

export const loadPrefs = async () => {
    if (!authUser.value) return;
    try {
        const cr = await fetch('/api/prefs/categories', {headers: authHeader()});
        categoryPrefs.value = await cr.json();
        const mr = await fetch('/api/prefs/mods', {headers: authHeader()});
        modPrefs.value = await mr.json();
        // 异步加载 Modrinth 卡片数据
        loadModInfoCache(modPrefs.value.map(m => m.slug));
    } catch(e) {}
};
// 进入设置页时自动加载偏好
watch(currentView, (v) => {
    if (v === 'profile') {
        loadPrefs();
        loadBlacklist();
        profileWeight.value = authUser.value ? authUser.value.preferenceWeight : 0.3;
        hasApiKey.value = authUser.value ? authUser.value.hasApiKey === true : false;
        enableBlacklist.value = authUser.value ? authUser.value.enableBlacklist === true : false;
        enableUserFeedbackRules.value = authUser.value ? authUser.value.enableUserFeedbackRules === true : false;
    }
    if (v === 'knowledge-editor') loadKeEnvs();
});

export const addCategoryPref = async () => {
    await fetch('/api/prefs/categories', {
        method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'},
        body: JSON.stringify({category: newCatPref.category, rank: String(newCatPref.rank)})
    }); loadPrefs();
};
export const removeCategoryPref = async (id) => {
    await fetch('/api/prefs/categories/' + id, {method: 'DELETE', headers: authHeader()});
    loadPrefs();
};
export const removeModPref = async (id) => {
    await fetch('/api/prefs/mods/' + id, {method: 'DELETE', headers: authHeader()});
    loadPrefs();
};
export const clearPrefs = async () => {
    await fetch('/api/prefs/clear', {method: 'POST', headers: authHeader()});
    loadPrefs();
};

// ========== 黑名单管理 ==========

export const loadBlacklist = async () => {
    if (!authUser.value) return;
    try {
        const r = await fetch('/api/prefs/blacklist', {headers: authHeader()});
        blacklistItems.value = await r.json();
        loadModInfoCache(blacklistItems.value.map(b => b.slug).filter(s => !modInfoCache.value[s]));
    } catch(e) {}
};
export const addBlacklistBySlug = async () => {
    const slug = blacklistSlugInput.value.trim().toLowerCase();
    if (!slug) return;
    blacklistMsg.value = '';
    try {
        // 先验证 slug 存在于 Modrinth
        const infoR = await fetch('/api/prefs/mod-info/' + slug);
        const info = await infoR.json();
        if (info.error) { blacklistMsg.value = '该 slug 在 Modrinth 不存在'; return; }
        modInfoCache.value[slug] = info;
        const r = await apiFetch('/api/prefs/blacklist', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({slug, source: 'user'})
        });
        const d = await r.json();
        if (d.error) { blacklistMsg.value = d.error; return; }
        blacklistSlugInput.value = '';
        blacklistMsg.value = '已添加: ' + slug;
        setTimeout(() => blacklistMsg.value = '', 3000);
        await loadBlacklist();
    } catch(e) { if (e.message !== '未登录') blacklistMsg.value = '添加失败'; }
};
export const removeBlacklistItem = async (id) => {
    await fetch('/api/prefs/blacklist/' + id, {method: 'DELETE', headers: authHeader()});
    loadBlacklist();
};
export const clearBlacklist = async () => {
    await fetch('/api/prefs/blacklist/clear', {method: 'POST', headers: authHeader()});
    loadBlacklist();
};
export const saveBlacklistToggle = async () => {
    if (!authUser.value) return;
    await fetch('/api/user/profile', {
        method: 'PUT', headers: {...authHeader(), 'Content-Type': 'application/json'},
        body: JSON.stringify({enableBlacklist: String(enableBlacklist.value)})
    });
    authUser.value.enableBlacklist = enableBlacklist.value;
    localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
};
export const saveFeedbackRulesToggle = async () => {
    if (!authUser.value) return;
    await fetch('/api/user/profile', {
        method: 'PUT', headers: {...authHeader(), 'Content-Type': 'application/json'},
        body: JSON.stringify({enableUserFeedbackRules: String(enableUserFeedbackRules.value)})
    });
    authUser.value.enableUserFeedbackRules = enableUserFeedbackRules.value;
    localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
};
export const openSuggestBoardForBlacklist = () => {
    showSuggestBoard.value = true;
    suggestMode.value = 'blacklist';
    suggestedMods.value = [];
    selectedSuggests.value = new Set();
};

// ========== 知识规则编辑器 ==========

export const loadKeEnvs = async () => {
    try {
        const r = await fetch('/api/knowledge/feedback/environments');
        const envs = await r.json();
        if (envs.length > 0) { keEnvs.value = envs; keEnv.value = envs[0]; }
    } catch(e) {}
};
export const onKeSlugInput = async (which) => {
    const slug = (which === 'A' ? keModA.value : keModB.value).trim().toLowerCase();
    const setInfo = which === 'A' ? (v) => keModAInfo.value = v : (v) => keModBInfo.value = v;
    const setError = which === 'A' ? (v) => keModAError.value = v : (v) => keModBError.value = v;
    if (!slug) { setInfo(null); setError(''); return; }
    setError('');
    try {
        const r = await fetch('/api/prefs/mod-info/' + slug);
        const d = await r.json();
        if (d.error) { setInfo(null); setError('该 slug 在 Modrinth 不存在'); }
        else { setInfo(d); setError(''); }
    } catch(e) { setError('查询失败'); }
};
export const submitKeRule = async () => {
    if (!keModAInfo.value || !keModBInfo.value) return;
    keMsg.value = '';
    try {
        const r = await apiFetch('/api/knowledge/feedback/add', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({
                environment: keEnv.value,
                modA: keModAInfo.value.slug,
                modB: keModBInfo.value.slug,
                relationType: keRelation.value
            })
        });
        const d = await r.json();
        if (d.error) { keMsg.value = d.error; return; }
        keMsg.value = d.message || '规则已添加';
        if (d.ok) { keModA.value = ''; keModB.value = ''; keModAInfo.value = null; keModBInfo.value = null; }
        setTimeout(() => keMsg.value = '', 5000);
    } catch(e) { if (e.message !== '未登录') keMsg.value = '提交失败'; }
};
export const exportPrefs = async () => {
    const r = await fetch('/api/prefs/export', {headers: authHeader()});
    const blob = await r.blob();
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'maa-preferences-' + new Date().toISOString().slice(0,10) + '.json';
    a.click();
};
export const importPrefs = async (e) => {
    const file = e.target.files[0]; if (!file) return;
    const text = await file.text();
    await fetch('/api/prefs/import', {
        method: 'POST', headers: {...authHeader(), 'Content-Type': 'application/json'}, body: text
    });
    loadPrefs(); e.target.value = '';
};
