// 聊天主流程：思考模拟器（计时器）→ 发送指令 → 委派取数循环 → 解析 AI 回复 → 落库。
//
// 从 index.html 的 setup 里整体搬出来的（原 1091-1092 与 1128-1354），逻辑原样保留。
// ⚠️ timerInterval 是模块级单例：本应用只 mount 一次，安全；重复 mount 会串表。
// ⚠️ sendMessage 的落库在 finally 里 —— 覆盖"附件解析失败 / 纯附件建图 / 手动终止"三条早退路径。
//
// 单向依赖：本模块 -> store / api / auth / modrinth-client / graph。
import {authHeader} from '../api.js';
import {DELEGATION, fulfilWanted, readChatResponse} from '../modrinth-client.js';
import {authUser, chatHistory, currentConvId, currentThought, currentView, enableUserFeedbackRules, isLoading, isResolving, packData, pendingMrpack, previewData, selectedMods, sessionUuid, thinkTime, userInput} from '../store.js';
import {newConversation, saveMsg} from './auth.js';
import {buildTreeData, renderGraph} from './graph.js';
import {nextTick} from 'vue';

// 计时器状态（聊天思考面板用）
let timerInterval = null;
// 🔥 启动思维模拟器：显示思考状态和思考时间
const startThinking = () => {
    isLoading.value = true;
    const startMs = Date.now();
    currentThought.value = "🧠 阶段1/4 — 正在解析自然语言，提取核心构筑意图与情景需求...";

    timerInterval = setInterval(() => {
        const elapsed = (Date.now() - startMs) / 1000;
        thinkTime.value = elapsed.toFixed(1);

        if (elapsed > 2 && elapsed <= 5)
            currentThought.value = "🌐 阶段1/4 — 中文黑话翻译为英文 Slug，构建搜索关键词矩阵...";
        else if (elapsed > 5 && elapsed <= 12)
            currentThought.value = "⚡ 阶段2/4 — 多路并发召回，向 Modrinth 发起大批量虚拟线程验证...";
        else if (elapsed > 12 && elapsed <= 20)
            currentThought.value = "🕵️ 阶段3/4 — Critic 审核员正在评估候选池，剔除冲突模组、筛定最终名单...";
        else if (elapsed > 20 && elapsed <= 35)
            currentThought.value = "🛠️ 阶段4/4 — 图谱引擎运行中，BFS 深度依赖穿透 + 死链抢救 + 冲突检测...";
        else if (elapsed > 35 && elapsed <= 60)
            currentThought.value = "🛠️ 阶段4/4 — 仍在穿透深层依赖链，请耐心等待 Modrinth API 响应...";
        else if (elapsed > 60 && elapsed <= 90)
            currentThought.value = "⏳ 网络延迟较高，依赖穿透正在重试失败的请求 (已耗时 " + Math.floor(elapsed) + "s)...";
        else if (elapsed > 90)
            currentThought.value = "🧩 最终阶段 — 装配过滤蓝图清单，生成构筑图谱...";
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
export const abortThinking = async () => {
    try {
        await fetch('/api/chat/abort', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({ uuid: sessionUuid.value })
        });
        stopThinking();
        chatHistory.value.push({role: 'ai', content: "⛔ 思考已手动终止。"});
    } catch (e) {
        console.error("终止请求发送失败", e);
    }
};

// 🔥 待发送的 mrpack 附件

export const sendMessage = async () => {
    let msg = userInput.value.trim();
    const attached = pendingMrpack.value;
    if ((!msg && !attached) || isLoading.value) return;

    // 登录用户：首条消息先建会话（saveMsg 依赖 currentConvId）
    if (authUser.value && !currentConvId.value) await newConversation();

    try {
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
                const parseRes = await fetch('/api/modpack/parse-mrpack', {
                    method: 'POST', body: fd
                });
                const parseData = await parseRes.json();
                if (parseData.slugs && parseData.slugs.length > 0) {
                    baseSlugs = parseData.slugs;
                    packData.name = fileToParse.name.replace('.mrpack', '');
                }
            } catch (e) {
                isResolving.value = false;
                currentView.value = 'chat';
                chatHistory.value.push({role: 'ai', content: '❌ 解析 ' + fileToParse.name + ' 失败'});
                return;
            }

            if (!msg) {
                // 只有附件无文字 → 直接渲染图谱 (大包需要等待 Modrinth API 逐个拉取图标, 可能较慢)
                // 导入 = 换了一个包，删除排除名单必须重置
                packData.modSlugs = baseSlugs;
                packData.excludedSlugs = [];
                chatHistory.value.push({
                    role: 'ai',
                    content: '已解析 ' + fileToParse.name + '，共 ' + baseSlugs.length + ' 个模组。正在展开构筑图谱 (加载模组图标和依赖关系)...',
                    hasData: true
                });
                try {
                    const previewRes = await fetch('/api/modpack/preview', {
                        method: 'POST', headers: {'Content-Type': 'application/json', ...authHeader()},
                        body: JSON.stringify({...packData, excludeUserFeedbackRules: !enableUserFeedbackRules.value})
                    });
                    const data = await previewRes.json();
                    previewData.value = data;
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
            isResolving.value = false;
            currentView.value = 'chat';
        }

        // 有文字指令 → 发给 AI (currentMods 使用 baseSlugs)
        startThinking();
        try {
            let res = await fetch('/api/chat', {
                method: 'POST',
                headers: {'Content-Type': 'application/json', ...authHeader()},
                body: JSON.stringify({
                    prompt: msg,
                    currentMods: baseSlugs.join(','),
                    uuid: sessionUuid.value,
                    // 🌍 F01：把包环境一起发给后端，续聊时才不会漂移到默认 neoforge/1.21.1
                    mcVersion: packData.mcVersion,
                    loader: packData.loader,
                    // 🗑️ 图谱里删掉的模组：后端不得再召回/补回（P2）
                    excludedSlugs: (packData.excludedSlugs || []).join(',')
                })
            });
            let payload = await readChatResponse(res);

            // 🔁 委派循环：服务器需要外部事实时会把清单发下来，我们取完再送回去，
            // 它继续跑并返回"下一份清单"或"最终回复"。不需要外部数据的轮次一次都不会进来
            // （响应直接就是 text/plain），所以开关关掉时这段等于不存在。
            let delegationRound = 0;
            while (payload.kind === 'need-data') {
                if (++delegationRound > DELEGATION.MAX_ROUNDS) {
                    throw new Error('取数轮次异常偏多（已 ' + delegationRound + ' 轮），已中止');
                }
                const wanted = payload.data.wanted || [];
                currentThought.value = `🌐 第 ${delegationRound} 轮取数 — `
                    + `${wanted.length} 项由你的网络直连 Modrinth（服务器不代劳）...`;
                const facts = await fulfilWanted(wanted);
                res = await fetch(`/api/chat/task/${encodeURIComponent(payload.data.taskId)}/facts`, {
                    method: 'POST',
                    headers: {'Content-Type': 'application/json', ...authHeader()},
                    body: JSON.stringify(facts)
                });
                payload = await readChatResponse(res);
            }

            const aiReply = payload.data;
            if (aiReply.includes("⛔ 思考已手动终止")) return;

            const modsMatch = aiReply.match(/<mods>(.*?)<\/mods>/s);
            let hasData = false;
            if (modsMatch) {
                packData.modSlugs = modsMatch[1].split(',').map(m => m.trim()).filter(Boolean);
                packData.name = (aiReply.match(/<name>(.*?)<\/name>/s) || [])[1] || packData.name;
                packData.mcVersion = (aiReply.match(/<mc>(.*?)<\/mc>/s) || [])[1] || packData.mcVersion;
                packData.loader = (aiReply.match(/<loader>(.*?)<\/loader>/s) || [])[1] || packData.loader;
                hasData = true;
            }
            const cleanReply = aiReply.replace(/<name>.*?<\/name>|<mc>.*?<\/mc>|<loader>.*?<\/loader>|<ops_summary>.*?<\/ops_summary>|<trace>.*?<\/trace>|<mods>.*?<\/mods>/gs, '').trim();
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
                role: 'ai', content: cleanReply, hasData, ops, thinkTime: finalTime
            });
        } catch (e) {
            stopThinking();
            chatHistory.value.push({role: 'ai', content: "系统连接中断，请检查后端服务是否启动。"});
        } finally {
            nextTick(() => {
                if (currentView.value === 'chat') {
                    const box = document.querySelector('main');
                    if (box) box.scrollTop = box.scrollHeight;
                }
            });
        }
    } finally {
        // 登录用户：本轮对话落库（原 sendMessageWithHistory 包装体内联）。
        // 放 finally 是为了覆盖三条早退路径：附件解析失败 / 纯附件建图 / 手动终止。
        if (authUser.value && currentConvId.value) {
            saveMsg('user', msg + (attached ? ' 📦' + attached.name : ''), false, '', []);
            nextTick(() => {
                const last = chatHistory.value[chatHistory.value.length - 1];
                if (last && last.role === 'ai') {
                    saveMsg('ai', last.content, last.hasData || false, last.thinkTime || '', packData.modSlugs);
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
