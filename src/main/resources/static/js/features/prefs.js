import {computed, nextTick, watch} from 'vue';
import {addingModBySlug, authUser, bindForm, bindMsg, blacklistItems, blacklistJump, blacklistMsg, blacklistPage, blacklistSlugInput, categoryPrefs, currentView, dbRules, dbVoteBusy, editingBuildCountId, editingBuildCountVal, emailAction, emailHintDismissed, enableBlacklist, enableUserFeedbackRules, hasApiKey, keEnv, keLoader, keLoaderEnvs, keModA, keModAError, keModAInfo, keModB, keModBError, keModBInfo, keMsg, keMsgOk, keRelation, keVersion, manualSlugInput, modInfoCache, modPrefJump, modPrefPage, modPrefs, modPrefSearch, modSlugAddMsg, newCatPref, profileApiKey, profileApiKeyMsg, profileUsername, profileWeight, pwCode, pwMsg, pwNew, pwOld, selectedSuggests, showSuggestBoard, suggestedMods, suggestMode} from '../store.js';
import {apiFetch, authHeader} from '../api.js';
import {startCooldown} from '../utils/code-cooldown.js';

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
    // 验证方式与后端一致：绑了邮箱只认邮箱验证码（不传当前密码），没绑才要当前密码
    const hasEmail = !!(authUser.value && authUser.value.email);
    if (hasEmail ? (!pwNew.value || !pwCode.value) : (!pwOld.value || !pwNew.value)) return;
    pwMsg.value = '';
    try {
        const r = await apiFetch('/api/user/change-password', {
            method: 'PUT', headers: {'Content-Type': 'application/json'},
            // 两个字段都带上也无所谓（后端按有没有绑邮箱只取需要的那个）：
            // 有邮箱 → 只看 code；没邮箱 → 只看 oldPassword
            body: JSON.stringify({
                oldPassword: hasEmail ? '' : pwOld.value,
                newPassword: pwNew.value,
                code: hasEmail ? pwCode.value : ''
            })
        });
        const d = await r.json();
        if (d.error) { pwMsg.value = d.error; return; }
        pwMsg.value = '密码修改成功';
        pwOld.value = ''; pwNew.value = ''; pwCode.value = '';
        setTimeout(() => pwMsg.value = '', 3000);
    } catch(e) { if (e.message !== '未登录') pwMsg.value = '修改失败'; }
};

// ---------- 邮箱：改密码的验证码 / 换绑（旧邮箱码 + 新邮箱码）----------
//
// 三个按钮各用各的倒计时 key：它们发给不同邮箱或不同用途，服务端彼此独立，共用一个倒计时
// 会让用户在本来能立刻发的按钮上白等 60 秒。

/** 发"改密码"的验证码（后端固定发到当前绑定邮箱，前端不指定收件人） */
export const sendPwCode = async () => {
    pwMsg.value = '';
    try {
        const r = await apiFetch('/api/user/email-code', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({purpose: 'change_password'})
        });
        const d = await r.json();
        if (d.error) { pwMsg.value = d.error; return; }
        startCooldown('pw', 60);
        pwMsg.value = '验证码已发到你的绑定邮箱';
    } catch(e) { if (e.message !== '未登录') pwMsg.value = '发送失败'; }
};

/** 发"新邮箱"的验证码（换绑现在只验新邮箱，不再要旧邮箱的码） */
export const sendBindCode = async () => {
    bindMsg.value = '';
    if (!(bindForm.newEmail || '').trim()) {
        bindMsg.value = '请先填写新邮箱'; return;
    }
    try {
        const r = await apiFetch('/api/user/email-code', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({purpose: 'bind_new', email: bindForm.newEmail})
        });
        const d = await r.json();
        if (d.error) { bindMsg.value = d.error; return; }
        startCooldown('new', 60);
        bindMsg.value = '验证码已发到新邮箱';
    } catch(e) { if (e.message !== '未登录') bindMsg.value = '发送失败'; }
};

export const saveBindEmail = async () => {
    bindMsg.value = '';
    const newEmail = (bindForm.newEmail || '').trim();
    if (!newEmail) { bindMsg.value = '请填写新邮箱'; return; }
    try {
        const r = await apiFetch('/api/user/bind-email', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({newEmail, oldCode: bindForm.oldCode, newCode: bindForm.newCode})
        });
        const d = await r.json();
        if (d.error) { bindMsg.value = d.error; return; }
        // 同步本地登录态：设置页要立刻显示新邮箱，提示条也该消失
        if (authUser.value) {
            authUser.value = {...authUser.value, email: d.email};
            localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
        }
        bindMsg.value = d.message || '邮箱已更新';
        bindForm.newEmail = ''; bindForm.oldCode = ''; bindForm.newCode = '';
    } catch(e) { if (e.message !== '未登录') bindMsg.value = '保存失败'; }
};

/** 关掉"还没绑邮箱"的提示条（存 localStorage，别每次刷新都烦人） */
export const dismissEmailHint = () => {
    emailHintDismissed.value = true;
    try { localStorage.setItem('maa-email-hint-dismissed', '1'); } catch (e) {}
};

/** 展开/收起邮箱卡片里的表单（'' | 'bind' | 'unbind'）；切换时清掉上一条提示 */
export const openEmailAction = (action) => {
    emailAction.value = emailAction.value === action ? '' : action;
    bindMsg.value = '';
};

// 解绑邮箱：按用户要求改成"点一下就解绑"，不再要验证码。
// 所以这里必须把后果贴在按钮旁边 —— 解绑之后忘记密码就只能人工找站长，而且拿到会话的人也能一键解绑。
export const doUnbindEmail = async () => {
    bindMsg.value = '';
    try {
        const r = await apiFetch('/api/user/unbind-email', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({})
        });
        const d = await r.json();
        if (d.error) { bindMsg.value = d.error; return; }
        if (authUser.value) {
            authUser.value = {...authUser.value, email: ''};
            localStorage.setItem('maa-auth', JSON.stringify(authUser.value));
        }
        emailAction.value = '';
        bindMsg.value = d.message || '邮箱已解绑';
    } catch(e) { if (e.message !== '未登录') bindMsg.value = '解绑失败'; }
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

/**
 * 拉取编辑器的环境选项：{ 加载器: [该加载器的 MC 版本...] }，来自 maa_db/loader-versions.json。
 *
 * <p>以前这里拉的是"知识库里已出现过的 environment"，于是只有 neoforge-1.21.1 时编辑器就只能选它。
 */
export const loadKeEnvs = async () => {
    try {
        const r = await fetch('/api/knowledge/feedback/loader-envs');
        keLoaderEnvs.value = (await r.json()) || {};
        const loaders = Object.keys(keLoaderEnvs.value);
        if (loaders.length === 0) return;
        if (!loaders.includes(keLoader.value)) keLoader.value = loaders[0];
        applyKeVersionForLoader(keLoader.value);
    } catch(e) {}
};

/** 切加载器时把版本落到该加载器真实存在的值（优先 1.21.1，否则最新的那个） */
const applyKeVersionForLoader = (loader) => {
    const versions = keLoaderEnvs.value[loader] || [];
    if (versions.length === 0) { keVersion.value = ''; return; }
    if (!versions.includes(keVersion.value)) {
        keVersion.value = versions.includes('1.21.1') ? '1.21.1' : versions[versions.length - 1];
    }
};
// 用户在编辑器里换了加载器 → 版本下拉立刻跟着换（否则会停在上一个加载器的版本上，
// 拼出来的环境是 loaderA-versionB 这种不存在的组合）
watch(keLoader, (v) => applyKeVersionForLoader(v));
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
        if (d.error) { keMsgOk.value = false; keMsg.value = d.error; return; }
        keMsgOk.value = true;
        keMsg.value = d.message || '规则已添加';
        if (d.ok) { keModA.value = ''; keModB.value = ''; keModAInfo.value = null; keModBInfo.value = null; }
        setTimeout(() => keMsg.value = '', 5000);
    } catch(e) {
        // apiFetch 把"200 但 body 带 error"也收敛成异常，所以真正的失败原因在 e.message 上，
        // 写死"提交失败"会把"该规则已存在且为官方规则"这种可操作的原因盖掉。
        if (e.message !== '未登录') { keMsgOk.value = false; keMsg.value = e.message || '提交失败'; }
    }
};

// ========== 知识库规则管理视图：认可 / 不认可 ==========

/** 重新拉取规则列表（进入视图、投票后刷新共用这一处） */
export const refreshDbRules = async () => {
    const res = await apiFetch('/api/knowledge');
    dbRules.value = await res.json();
};

/**
 * 认可 / 不认可 / 取消投票。
 *
 * <p>再点一次已经按下的那个按钮 = 取消（后端收到 CLEAR），所以"我点了认可又想撤回"不需要刷新页面。
 * 两个按钮的互斥由后端保证（投认可会把自己从不认可名单里摘掉），前端只负责发对动作。
 */
export const voteRule = async (rule, vote) => {
    if (!authUser.value) { alert('登录后才能投票'); return; }
    if (dbVoteBusy.value) return;
    const action = rule.myVote === vote ? 'CLEAR' : vote;
    dbVoteBusy.value = true;
    try {
        const r = await apiFetch('/api/knowledge/feedback/vote', {
            method: 'POST', headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ruleId: rule.id, vote: action})
        });
        const d = await r.json();
        if (d.error) { alert(d.error); return; }
        await refreshDbRules();
    } catch(e) {
        if (e.message !== '未登录') alert(e.message || '投票失败');
    } finally {
        dbVoteBusy.value = false;
    }
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
