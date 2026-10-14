// 「生成空图谱」：不调用任何 LLM，直接开一张空图进装配台手动拼整合包。
//
// 背景：装配台（lab 视图）原先只能从对话结果进入 —— 入口是聊天气泡里的按钮，而对话要求
// 用户自备 LLM Key。于是"没配 Key"就等于"连手动拼包都做不到"。这条路径把入口前移，
// 只依赖公开接口（preview / suggest / build 都不需要 Key，也不会走四阶段 Agent 管线）。
//
// 与对话的关系：产品口径是"等价于在对话里说一句『生成空图谱』"，所以确认后往当前会话里
// 落两条消息（用户请求 + 确认回执），会话进历史、可继续对话调优；但**不经过 ChatController**，
// Architect/Critic 的隔离与提示词完全不受影响。
import {computed, reactive, ref} from 'vue';
import {authError, authUser, chatHistory, packData, showAuthModal} from '../store.js';
import {saveMsg} from './auth.js';
import {openLab} from './delete-flow.js';
import {recordCurrentVersion} from './pack-snapshot.js';
import {defaultVersionFor, loadLoaderEnvs} from '../utils/loader-envs.js';

export const showEmptyGraphModal = ref(false);
export const emptyGraphBusy = ref(false);
export const emptyGraphError = ref('');
/** 加载器 → 可选 MC 版本（与规则编辑器同源，见 utils/loader-envs.js） */
export const emptyGraphEnvs = ref({});
export const emptyGraphForm = reactive({name: '', loader: '', version: ''});

/** 当前所选加载器可选的版本（模板里直接遍历） */
export const emptyGraphVersions = computed(() => emptyGraphEnvs.value[emptyGraphForm.loader] || []);

export const openEmptyGraphModal = async () => {
    // 必须登录：这会往会话历史里落消息，落库依赖账号；也顺带挡掉匿名流量
    if (!authUser.value) {
        authError.value = '生成空图谱需要先登录 —— 结果会记录到你的对话历史里';
        showAuthModal.value = true;
        return;
    }
    if (Object.keys(emptyGraphEnvs.value).length === 0) {
        emptyGraphEnvs.value = await loadLoaderEnvs();
    }
    const loaders = Object.keys(emptyGraphEnvs.value);
    // 默认沿用当前包的名字与环境：多数场景是"在当前包的基础上另起一个"
    emptyGraphForm.name = packData.name && packData.name !== 'MAA-Pack' ? packData.name : '我的整合包';
    emptyGraphForm.loader = loaders.includes(packData.loader) ? packData.loader : (loaders[0] || '');
    emptyGraphForm.version = defaultVersionFor(emptyGraphEnvs.value, emptyGraphForm.loader);
    emptyGraphError.value = '';
    showEmptyGraphModal.value = true;
};

export const closeEmptyGraphModal = () => { showEmptyGraphModal.value = false; };

/** 换加载器要把版本落到该加载器真实存在的值上，否则会拼出不存在的环境 */
export const onEmptyGraphLoaderChange = () => {
    emptyGraphForm.version = defaultVersionFor(emptyGraphEnvs.value, emptyGraphForm.loader);
};

export const confirmEmptyGraph = async () => {
    const name = (emptyGraphForm.name || '').trim();
    if (!name) { emptyGraphError.value = '请填写整合包名称'; return; }
    if (!emptyGraphForm.loader || !emptyGraphForm.version) {
        emptyGraphError.value = '请选择加载器与游戏版本';
        return;
    }
    emptyGraphBusy.value = true;
    emptyGraphError.value = '';
    try {
        // 换包 = 换工作区：清单与删除排除名单都清空，避免把上一个包的模组带进新图
        packData.name = name;
        packData.mcVersion = emptyGraphForm.version;
        packData.loader = emptyGraphForm.loader;
        packData.modSlugs = [];
        packData.excludedSlugs = [];
        packData.constraints = null; packData.importedPack = null;
        packData.assembly = {loader: packData.loader, mcVersion: packData.mcVersion, roots: [], versions: {}, origins: {}, rootsKnown: true};

        // 落历史：saveMsg 会在没有会话时自己建一个（不会清空工作区），标题取首条用户消息前 30 字，
        // 于是侧边栏一眼能认出是哪个包
        const ask = `生成空图谱：${name}（${emptyGraphForm.loader} ${emptyGraphForm.version}）`;
        chatHistory.value.push({role: 'user', content: ask});
        await saveMsg('user', ask, false, '', []);
        const reply = '已生成空图谱「' + name + '」（' + emptyGraphForm.loader + ' '
                + emptyGraphForm.version + '），当前 0 个模组。\n'
                + '左侧输入 slug 就能添加，或点 💡 智能抽卡 批量挑；依赖补全与冲突校验照样生效。';
        const aiMsg = {role: 'ai', content: reply, hasData: true};
        chatHistory.value.push(aiMsg);
        // 空图谱本身就是一版（0 个模组）：给它一个版本号，这条消息的按钮以后就指向这张空图，
        // 而不是"当前最新图"；顺带也让装配台首次进入时不再补一条"（未命名）→ 我的整合包"的流水
        await recordCurrentVersion(aiMsg);

        showEmptyGraphModal.value = false;
        // 复用既有装配台入口：preview + 建树 + 渲染，并切到 lab 视图
        await openLab();
    } catch (e) {
        emptyGraphError.value = '生成失败：' + (e.message || '未知错误');
    } finally {
        emptyGraphBusy.value = false;
    }
};
