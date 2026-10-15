// 聊天主流程：耗时计时与实际阶段记录 → 发送指令 → 委派取数循环 → 解析 AI 回复 → 落库。
//
// 本模块只负责发送与收口，共享状态在 store.js，图谱渲染在 graph.js，删除流程在 delete-flow.js。
// ⚠️ timerInterval 是模块级单例：本应用只 mount 一次，安全；重复 mount 会串表。
// ⚠️ sendMessage 的落库在 finally 里 —— 覆盖"附件解析失败 / 纯附件建图 / 手动终止"三条早退路径。
//
// 单向依赖：本模块 -> store / api / auth / modrinth-client / graph。
import {apiFetch, authHeader} from '../api.js';
import {DELEGATION, fulfilWanted, readChatResponse} from '../modrinth-client.js';
import {authUser, chatHistory, currentConvId, currentThought, buildProgress, currentView, enableUserFeedbackRules, isLoading, isResolving, packData, pendingMrpack, previewData, selectedMods, sessionUuid, thinkTime, userInput, showAuthModal} from '../store.js';
import {newConversation, saveMsg} from './auth.js';
import {recordCurrentVersion} from './pack-snapshot.js';
import {buildTreeData, renderGraph} from './graph.js';
import {nextTick, ref} from 'vue';
import {cleanChatReply} from '../utils/format.js';

// 计时器状态（聊天思考面板用）
let timerInterval = null;
let activeRequest = null;
export const readClarificationTicket = (content) => {
    const match = String(content || '').match(/<clarification_context>(.*?)<\/clarification_context>/s);
    if (!match) return null;
    try {
        const context = JSON.parse(match[1]);
        return context.schemaVersion === 1 && typeof context.ticket === 'string'
            && /^[A-Za-z0-9-]{1,64}$/.test(context.ticket) ? context.ticket : null;
    } catch { return null; }
};
export const replacementPrompt = ref(null);
export const replacementLink = (slug) => 'https://modrinth.com/mod/' + encodeURIComponent(slug);
let completeReplacement = null;
export const confirmReplacement = () => {
    if (!replacementPrompt.value || !completeReplacement) return;
    const choices = {};
    for (const group of replacementPrompt.value.groups) {
        const choice = group.choice;
        const manualSlug = choice.manualSlug.trim();
        if (replacementPrompt.value.kind === 'OVERLAP') {
            if (!choice.slugs.length || group.candidates.some(c => c.protectedSelection && !choice.slugs.includes(c.slug))) {
                replacementPrompt.value.error = '每组至少保留一个项目，并保留点名核心、锁定版本及必要前置。';
                return;
            }
            choices[group.original] = {slugs: [...choice.slugs], drop: false, manualSlug: ''};
            continue;
        }
        if (!choice.drop && !manualSlug && !choice.slugs.length) {
            replacementPrompt.value.error = '请为每个模组选择候选、补充准确 slug，或明确去除要求。';
            return;
        }
        choices[group.original] = {slugs: choice.drop || manualSlug ? [] : [...choice.slugs],
            drop: choice.drop, manualSlug: choice.drop ? '' : manualSlug};
    }
    completeReplacement(choices);
};
export const cancelReplacement = () => completeReplacement?.(null);
export const sameSelectionWorkspace = (request) => request.currentMods === (packData.modSlugs || []).join(',')
    && request.mcVersion === packData.mcVersion && request.loader === packData.loader
    && request.uuid === sessionUuid.value
    && request.assembly === (packData.assembly ? JSON.stringify(packData.assembly) : '')
    && request.importedPack === (packData.importedPack ? JSON.stringify(packData.importedPack) : '')
    && request.constraints === (packData.constraints ? JSON.stringify(packData.constraints) : '')
    && request.excludedSlugs === (packData.excludedSlugs || []).join(',');
export const syncReplacementSelection = (slug, checked) => {
    if (replacementPrompt.value?.kind !== 'OVERLAP') return;
    for (const group of replacementPrompt.value.groups) if (group.candidates.some(c => c.slug === slug)) {
        group.choice.slugs = checked ? [...new Set([...group.choice.slugs, slug])] : group.choice.slugs.filter(s => s !== slug);
    }
};
export const chooseReplacements = (options, signal, previous = null, message = '') => new Promise((resolve, reject) => {
    signal.throwIfAborted();
    replacementPrompt.value = {...options, error: message, groups: options.groups.map(group => ({...group,
        choice: previous?.[group.original] ? {...previous[group.original], slugs: [...previous[group.original].slugs]}
            : {slugs: options.kind === 'OVERLAP' ? group.candidates.filter(c => c.recommended || c.protectedSelection).map(c => c.slug)
                : group.candidates.length ? [group.candidates[0].slug] : [], drop: false, manualSlug: ''}}))};
    if (options.kind === 'OVERLAP') {
        const selected = new Set(replacementPrompt.value.groups.flatMap(group => group.choice.slugs));
        for (const group of replacementPrompt.value.groups)
            group.choice.slugs = group.candidates.filter(c => selected.has(c.slug)).map(c => c.slug);
    }
    nextTick(() => document.getElementById('replacement-confirm')?.focus());
    const finish = (choices) => {
        signal.removeEventListener('abort', abort);
        replacementPrompt.value = null;
        completeReplacement = null;
        resolve(choices);
    };
    const abort = () => {
        signal.removeEventListener('abort', abort);
        replacementPrompt.value = null;
        completeReplacement = null;
        reject(signal.reason);
    };
    completeReplacement = finish;
    signal.addEventListener('abort', abort, {once: true});
});
// 计时器只更新耗时，业务阶段由任务快照驱动。
let thinkingStartedAt = 0;
const startThinking = (reset = true) => {
    isLoading.value = true;
    if (reset) { thinkingStartedAt = Date.now(); buildProgress.value = []; }
    currentThought.value = '';
    clearInterval(timerInterval);
    timerInterval = setInterval(() => {
        thinkTime.value = ((Date.now() - thinkingStartedAt) / 1000).toFixed(1);
    }, 100);
};

const stopThinking = () => {
    isLoading.value = false;
    if (timerInterval) {
        clearInterval(timerInterval);
        timerInterval = null;
    }
};

// 🔥 新增：手动终止请求
// 刷新 / 关标签 / 切走页面时，如果还有一轮在跑，主动告诉服务器"这一轮我不要了"。
//
// 不这么做的后果是真实的线上问题：那一轮会继续在服务器上跑完（每次等数据超时后自己回源），
// 期间一直占着"同一账号同时只能跑一轮"的名额；用户刷新完再发指令只会看到
// "⏳ 你上一轮构筑还在进行中"，而此时 isLoading 已经变 false、终止按钮根本不在页面上，
// 除了等它自己跑完没有任何办法 —— 这就是"只能重启服务器"的来源。
//
// 用 sendBeacon 而不是 fetch：页面卸载时 fetch 会被浏览器直接掐断，sendBeacon 由浏览器保证送出。
// 但它不能带自定义请求头，所以把 token 放进 body，后端两种都认。
window.addEventListener('pagehide', () => {
    if (!isLoading.value) return;
    if (replacementPrompt.value?.ticket) {
        apiFetch(`/api/chat/${replacementPrompt.value.kind === 'OVERLAP' ? 'overlaps' : 'replacements'}/${encodeURIComponent(replacementPrompt.value.ticket)}/cancel`,
            {method: 'POST', keepalive: true}).catch(() => {});
        return;
    }
    try {
        const body = JSON.stringify({
            uuid: sessionUuid.value,
            token: (authUser.value && authUser.value.token) || ''
        });
        navigator.sendBeacon('/api/chat/abort', new Blob([body], {type: 'application/json'}));
    } catch (e) { /* 卸载路径上什么都做不了，静默 */ }
});

export const abortThinking = async () => {
    const abortedRequest = activeRequest;
    const awaitingSelection = !!replacementPrompt.value;
    abortedRequest?.abort();
    if (awaitingSelection) {
        if (activeRequest === abortedRequest) activeRequest = null;
        stopThinking();
        chatHistory.value.push({role: 'ai', content: "⛔ 思考已手动终止。"});
        return;
    }
    try {
        await apiFetch('/api/chat/abort', {
            method: 'POST',
            // 带上 token：服务端要按账号找这一轮的委派任务（uuid 每次刷新都会变，靠它找不着），
            // 顺便让"拿别人 uuid 乱终止"这条路走不通
            headers: {'Content-Type': 'application/json', ...authHeader()},
            body: JSON.stringify({ uuid: sessionUuid.value })
        });
        stopThinking();
        chatHistory.value.push({role: 'ai', content: "⛔ 思考已手动终止。"});
    } catch (e) {
        console.error("终止请求发送失败", e);
    } finally {
        if (activeRequest === abortedRequest) { activeRequest = null; stopThinking(); }
    }
};

// 🔥 待发送的 mrpack 附件

export const sendMessage = async () => {
    if (!authUser.value) { showAuthModal.value = true; return; }
    let msg = userInput.value.trim();
    const attached = pendingMrpack.value;
    if ((!msg && !attached) || isLoading.value) return;
    const request = new AbortController();
    activeRequest = request;
    startThinking();

    try {
        if (authUser.value && !currentConvId.value) await newConversation();
        request.signal.throwIfAborted();
        const lastReply = chatHistory.value.at(-1);
        const clarificationTicket = !attached && lastReply?.role === 'ai' ? readClarificationTicket(lastReply.content) : null;
        // 🔥 立即显示用户消息 (含附件标记)
        let userContent = msg;
        if (attached) {
            userContent = (msg ? '📦 ' + attached.name + '\n' + msg : '📦 ' + attached.name);
        }
        chatHistory.value.push({role: 'user', content: userContent});
        userInput.value = '';
        const fileToParse = attached ? attached.file : null;
        pendingMrpack.value = null;

        // 🔥 如果有附件, 先切到 lab 显示加载动画, 再后台解析 mrpack
        let baseSlugs = packData.modSlugs;
        if (fileToParse) {
            // 立即切到 lab 视图显示 spinner
            currentView.value = 'lab';
            isResolving.value = true;
            try {
                const fd = new FormData();
                fd.append('file', fileToParse);
                const parseRes = await apiFetch('/api/modpack/parse-mrpack', {
                    method: 'POST', body: fd, headers: authHeader(), signal: request.signal
                });
                const parseData = await parseRes.json();
                request.signal.throwIfAborted();
                if (Array.isArray(parseData.slugs) && parseData.importedPack) {
                    baseSlugs = parseData.slugs;
                    packData.name = parseData.name;
                    packData.mcVersion = parseData.importedPack.mcVersion;
                    packData.loader = parseData.importedPack.loader;
                    packData.importedPack = parseData.importedPack;
                } else throw new Error('导入结果缺少精确版本信息');
            } catch (e) {
                isResolving.value = false;
                if (request.signal.aborted) return;
                currentView.value = 'chat';
                chatHistory.value.push({role: 'ai', content: '❌ 解析 ' + fileToParse.name + ' 失败：' + (e.message || '请检查文件')});
                return;
            }

            if (!msg) {
                // 只有附件无文字 → 直接渲染图谱 (大包需要等待 Modrinth API 逐个拉取图标, 可能较慢)
                // 导入 = 换了一个包，删除排除名单必须重置
                packData.modSlugs = baseSlugs;
                packData.excludedSlugs = [];
                packData.constraints = null;
                packData.assembly = null;
                chatHistory.value.push({
                    role: 'ai',
                    content: '已解析 ' + fileToParse.name + '，共 ' + baseSlugs.length + ' 个模组。正在展开构筑图谱 (加载模组图标和依赖关系)...',
                    hasData: true
                });
                try {
                    const previewRes = await apiFetch('/api/modpack/preview', {
                        method: 'POST', headers: {'Content-Type': 'application/json', ...authHeader()},
                        body: JSON.stringify({...packData, excludeUserFeedbackRules: !enableUserFeedbackRules.value})
                    });
                    const data = await previewRes.json();
                    previewData.value = data;
                    if (data.assembly) packData.assembly = data.assembly;
                    selectedMods.value = new Set(data.nodes.map(n => n.id));
                    buildTreeData(data);
                    setTimeout(() => renderGraph(), 100);
                } finally {
                    isResolving.value = false;
                }
                return;
            }

            // 有文字指令 → 切回聊天, 附件模组已记录在 baseSlugs 中
            packData.modSlugs = baseSlugs;
            packData.constraints = null;
            packData.assembly = null;
            packData.excludedSlugs = [];
            isResolving.value = false;
            currentView.value = 'chat';
        }

        // 有文字指令 → 发给 AI (currentMods 使用 baseSlugs)
        try {
            const selectionConversation = currentConvId.value;
            const chatHeaders = () => ({'Content-Type': 'application/json', ...authHeader(),
                ...(selectionConversation ? {'X-Conversation-Id': String(selectionConversation)} : {})});
            const buildRequest = {
                prompt: msg, currentMods: baseSlugs.join(','),
                ...(clarificationTicket ? {clarificationTicket} : {}),
                importedPack: packData.importedPack ? JSON.stringify(packData.importedPack) : '',
                assembly: packData.assembly ? JSON.stringify(packData.assembly) : '',
                uuid: sessionUuid.value, mcVersion: packData.mcVersion, loader: packData.loader,
                excludedSlugs: (packData.excludedSlugs || []).join(','),
                constraints: packData.constraints ? JSON.stringify(packData.constraints) : ''
            };
            let res = await apiFetch('/api/chat', {
                method: 'POST',
                signal: request.signal,
                headers: chatHeaders(),
                body: JSON.stringify(buildRequest)
            });
            let payload = await readChatResponse(res);
            request.signal.throwIfAborted();

            // 🔁 委派循环：服务器需要外部事实时会把清单发下来，浏览器取完再回传，
            // 它继续跑并返回"下一份清单"或"最终回复"。不需要外部数据的轮次一次都不会进来
            // （响应直接就是 text/plain），所以开关关掉时这段等于不存在。
            let delegationRound = 0;
            while (payload.kind === 'need-data') {
                if (++delegationRound > DELEGATION.MAX_ROUNDS) {
                    throw new Error('取数轮次异常偏多（已 ' + delegationRound + ' 轮），已中止');
                }
                const wanted = payload.data.wanted || [];
                const facts = await fulfilWanted(wanted, request.signal);
                request.signal.throwIfAborted();
                res = await apiFetch(`/api/chat/task/${encodeURIComponent(payload.data.taskId)}/facts`, {
                    method: 'POST',
                    signal: request.signal,
                    headers: chatHeaders(),
                    body: JSON.stringify(facts)
                });
                payload = await readChatResponse(res);
                request.signal.throwIfAborted();
            }

            let aiReply = payload.data;
            for (let selectionRound = 0; ; selectionRound++) {
                const options = aiReply.match(/<replacement_options>(.*?)<\/replacement_options>/s);
                if (!options) break;
                if (selectionRound >= 5) throw new Error('候选确认次数过多，请核对目标环境和模组 slug 后重新提交');
                clearInterval(timerInterval);
                timerInterval = null;
                currentThought.value = '等待确认候选组合，原清单保持不变';
                const selection = JSON.parse(options[1]);
                let previousSelection = null, selectionError = '';
                for (;;) {
                    const releaseSelection = () => apiFetch(`/api/chat/${selection.kind === 'OVERLAP' ? 'overlaps' : 'replacements'}/${encodeURIComponent(selection.ticket)}/cancel`,
                        {method: 'POST'}).catch(() => {});
                    request.signal.addEventListener('abort', releaseSelection, {once: true});
                    let choices;
                    try { choices = await chooseReplacements(selection, request.signal, previousSelection, selectionError); }
                    finally { request.signal.removeEventListener('abort', releaseSelection); }
                    request.signal.throwIfAborted();
                    if (!choices) {
                        await releaseSelection();
                        aiReply = '已暂停构建，尚未修改整合包清单。可重新提交需求选择候选。';
                        break;
                    }
                    if (currentConvId.value !== selectionConversation || !sameSelectionWorkspace(buildRequest)) {
                        await releaseSelection();
                        throw new Error('工作区已变化，本次候选草案已取消，请在当前包重新提交需求');
                    }
                    startThinking(false);
                    try {
                        res = await apiFetch('/api/chat', {method: 'POST', signal: request.signal,
                            headers: chatHeaders(),
                            body: JSON.stringify({...buildRequest, replacementTicket: selection.ticket, selectionKind: selection.kind,
                                clarificationTicket: undefined,
                                replacementChoices: JSON.stringify(choices)})});
                        break;
                    } catch (error) {
                        if (request.signal.aborted || ![409, 429].includes(error.status)) throw error;
                        clearInterval(timerInterval);
                        timerInterval = null;
                        previousSelection = choices;
                        selectionError = '服务繁忙，当前选择已保留。请稍后点击“确认并继续”重试，或取消构建。';
                        currentThought.value = '等待重试续建，当前选择已保留';
                    }
                }
                if (aiReply.startsWith('已暂停构建')) break;
                payload = await readChatResponse(res);
                request.signal.throwIfAborted();
                aiReply = payload.data;
            }
            if (aiReply.includes("⛔ 思考已手动终止")) return;

            const modsMatch = aiReply.match(/<mods>(.*?)<\/mods>/s);
            let importNotice = '';
            let hasData = false;
            if (modsMatch) {
                const assemblyTrace = (aiReply.match(/<trace>(.*?)<\/trace>/s) || [])[1];
                packData.assembly = assemblyTrace ? JSON.parse(assemblyTrace).assembly || null : null;
                const constraintsMatch = aiReply.match(/<constraints>(.*?)<\/constraints>/s);
                if (constraintsMatch) {
                    packData.constraints = JSON.parse(constraintsMatch[1]);
                    packData.excludedSlugs = [...packData.constraints.exclude];
                }
                packData.modSlugs = modsMatch[1].split(',').map(m => m.trim()).filter(Boolean);
                packData.name = (aiReply.match(/<name>(.*?)<\/name>/s) || [])[1] || packData.name;
                packData.mcVersion = (aiReply.match(/<mc>(.*?)<\/mc>/s) || [])[1] || packData.mcVersion;
                packData.loader = (aiReply.match(/<loader>(.*?)<\/loader>/s) || [])[1] || packData.loader;
                if (packData.importedPack) {
                    const traceText = (aiReply.match(/<trace>(.*?)<\/trace>/s) || [])[1];
                    const mode = traceText ? JSON.parse(traceText).contract?.mode : null;
                    if (mode === 'NEW' || mode === 'EMPTY' || packData.loader !== packData.importedPack.loader
                        || packData.mcVersion !== packData.importedPack.mcVersion) {
                        packData.importedPack = null;
                        importNotice = '\nℹ️ 新建包或切换环境后，原包的版本锁定已解除，将按新环境重新选版。';
                    } else packData.importedPack.files = packData.importedPack.files.filter(f => packData.modSlugs.includes(f.slug));
                }
                hasData = true;
            }
            const history = [...new Set(buildProgress.value.map(row => row.taskId))].map(taskId => ({
                taskId, entries: buildProgress.value.filter(row => row.taskId === taskId)
                    .map(({sequence, title, startedAt, endedAt}) => ({sequence, title, startedAt, endedAt}))
            }));
            const cleanReply = cleanChatReply(aiReply).replace(/<build_progress>.*?<\/build_progress>/gs, '')
                + (history.length ? '\n<build_progress>' + JSON.stringify({history}) + '</build_progress>' : '');
            // 🔧 本轮变更（后端 Java 状态生成的结构化摘要）：解析失败就当没有，绝不打断对话
            const opsMatch = aiReply.match(/<ops_summary>([\s\S]*?)<\/ops_summary>/);
            let ops = null;
            if (opsMatch) {
                try {
                    ops = JSON.parse(opsMatch[1]);
                } catch (e) {
                    console.warn('ops_summary 解析失败，已跳过变更卡片', e);
                }
            }
            const finalTime = thinkTime.value;
            stopThinking();
            chatHistory.value.push({
                role: 'ai', content: cleanReply + importNotice, hasData, ops, thinkTime: finalTime
            });
        } catch (e) {
            if (!request.signal.aborted) {
                stopThinking();
                const retainedContext = clarificationTicket && [409, 429].includes(e.status)
                    ? '\n<clarification_context>' + JSON.stringify({schemaVersion: 1, ticket: clarificationTicket}) + '</clarification_context>' : '';
                chatHistory.value.push({role: 'ai', content: (e.message || '请求失败，请稍后重试') + retainedContext});
            }
        } finally {
            nextTick(() => {
                if (currentView.value === 'chat') {
                    const box = document.querySelector('main');
                    if (box) box.scrollTop = box.scrollHeight;
                }
            });
        }
    } finally {
        if (activeRequest === request && !request.signal.aborted) { activeRequest = null; stopThinking(); }
        // 登录用户：本轮对话落库（原 sendMessageWithHistory 包装体内联）。
        // 放 finally 是为了覆盖三条早退路径：附件解析失败 / 纯附件建图 / 手动终止。
        if (authUser.value && currentConvId.value && !request.signal.aborted) {
            saveMsg('user', msg + (attached ? ' 📦' + attached.name : ''), false, '', []);
            nextTick(() => {
                const last = chatHistory.value[chatHistory.value.length - 1];
                if (last && last.role === 'ai') {
                    // 这一轮也构成"一版"：落库时带上完整状态，并把版本号挂到消息上，
                    // 于是这条消息的"查看图谱"按钮以后永远指向这一版（而不是最新一版）
                    recordCurrentVersion(last);
                }
            });
        }
    }
};

// 🔥 mrpack 上传 → 只暂存文件, 不立即解析
export const onMrpackUpload = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    pendingMrpack.value = { file, name: file.name };
    e.target.value = '';
};
