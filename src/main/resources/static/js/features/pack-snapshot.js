// 整合包版本流水：把"这一版是什么"与"相对上一版改了什么"记进对话历史。
//
// 为什么需要：装配台里的每个按钮原本都指向"最新一版"——因为按钮只是重新用当前 packData
// 跑一次 preview。于是同一段对话里出现多个"查看图谱"入口时，点哪个都看到同一张最新图，
// 用户没法回到"当时那一版"继续做。现在每一版都有内容版本号（服务端算），
// 消息带着版本号与完整状态，按钮因此可以做到"点开就是那一版"。
//
// 分工：
//   · 对话轮次产生的版本 → 由 chat.js 保存消息时带上状态（走同一个 saveMsg）；
//   · 装配台里的改动     → 由本模块在预览完成后比对状态，产生一条确定性变更说明（不经 LLM）。
// 装配台的改动只往 chatHistory 追加消息，**不切回对话视图**：用户自己返回时才看到流水。
import {authUser, chatHistory, labChangeNote, labUnresolved, latestPackState,
    latestSnapshotId, packData} from '../store.js';
import {saveMsg} from './auth.js';
import {openLab, setPreviewHook} from './delete-flow.js';
import {applyPackState, attachVersion, packStateOf, readPackState, stateEquals} from '../utils/pack-state.js';
import {computed, ref} from 'vue';

/** 解析失败原因 → 给用户看的话。UPSTREAM_UNAVAILABLE 不在里面：那是"没查到"，不是"没有" */
const HARD_FAIL_REASONS = ['PROJECT_NOT_FOUND', 'NO_VERSION_FOR_ENV', 'LOADER_MISMATCH', 'VERSION_CONFLICT'];

const REASON_TEXT = {
    PROJECT_NOT_FOUND: 'Modrinth 上没有这个项目',
    NO_VERSION_FOR_ENV: '没有适配当前环境的版本',
    LOADER_MISMATCH: '只有其它加载器的版本',
    VERSION_CONFLICT: '精确版本要求在当前环境无法满足',
    UPSTREAM_UNAVAILABLE: '暂时查不到（网络或限流）',
    DEPENDENT_OF_DROPPED: '它的前置被剔除了',
    CONFLICT_WITH_KEPT: '与保留的模组互斥',
    CONFLICT_BETWEEN_ROOTS: '与另一个核心模组互斥',
    BUDGET_EXCEEDED: '解析预算触顶，这轮没查完',
};

/** 上一次记录时的节点 slug 集合（用来区分"用户加的"和"依赖自动补入的"） */
let lastNodes = null;
/** 切历史版本时记下"从哪一版切过来"，供流水文案使用；正常改动为 null */
let switchFromId = '';

const reasonText = (reason) => REASON_TEXT[reason] || reason || '未知原因';

/**
 * 预览完成后的收口（由 delete-flow 的预览钩子调用）。
 *
 * <p>两件事：① 把"当前环境找不到"的模组摘掉并告诉用户（硬失败），
 * ② 状态真的变了就记一条版本流水。
 *
 * @param {{state: object, nodes: Array, unresolved: Array, dropped: Array}} result
 */
export const handlePreviewResult = async ({state, nodes, unresolved, dropped}) => {
    labUnresolved.value = Array.isArray(unresolved) ? unresolved : [];

    // ① 硬失败（不存在 / 当前环境没版本）的模组从清单里摘掉：留着的话每次预览都会重复报同一个错，
    //    而且会被算进版本号里，让"同一版"看起来一直在变。查不通（UPSTREAM_UNAVAILABLE）不摘。
    const hardFail = new Set((unresolved || [])
        .filter(u => HARD_FAIL_REASONS.includes(u.reason))
        .filter(u => (state.modSlugs || []).includes(u.slug))
        .map(u => u.slug));
    if (hardFail.size > 0) {
        packData.modSlugs = packData.modSlugs.filter(s => !hardFail.has(s));
        state = packStateOf(packData);   // 摘完之后的状态才是"这一版"
    }

    // ② 状态没变 → 不产生新版本（重复预览、打开历史版本查看都不该刷屏）
    if (stateEquals(state, latestPackState.value)) {
        lastNodes = nodes || lastNodes;
        return;
    }

    const note = buildChangeNote(state, nodes, dropped, hardFail);
    const summary = summarize(state, nodes, hardFail);
    labChangeNote.value = summary;
    lastNodes = nodes || [];
    switchFromId = '';

    if (!authUser.value) {
        // 未登录：没有地方记流水，只更新内存游标（否则每次预览都会重复记一条）
        latestPackState.value = state;
        return;
    }
    const msg = {role: 'ai', content: note, hasData: true};
    chatHistory.value.push(msg);
    await recordState(msg, state);
};

/** 把"这一版"落库，并把版本号挂回消息（按钮靠它区分版本） */
const recordState = async (msg, state) => {
    const id = await saveMsg(msg.role, msg.content, !!msg.hasData, msg.thinkTime || '', state.modSlugs, state);
    if (id) {
        // 版本号与状态必须成对挂到消息上（原因见 attachVersion 的注释）
        attachVersion(msg, id, state);
        latestSnapshotId.value = id;
        latestPackState.value = state;
    }
    return id;
};

/** 变更说明：只用确定性 diff，不经 LLM（产品口径：这一步是代码操作） */
const buildChangeNote = (state, nodes, dropped, hardFail) => {
    const prev = latestPackState.value || {name: '', mcVersion: '', loader: '', modSlugs: [], excludedSlugs: []};
    const prevSlugs = new Set(prev.modSlugs || []);
    const selfAdded = state.modSlugs.filter(s => !prevSlugs.has(s));
    const removed = (prev.modSlugs || []).filter(s => !state.modSlugs.includes(s));
    const nodeSlugs = new Set((nodes || []).map(n => n.slug));
    const prevNodes = lastNodes ? new Set(lastNodes.map(n => n.slug)) : null;
    // 依赖自动补入：在图里但用户没主动加过，且上一版图里也没有
    const depAdded = prevNodes ? [...nodeSlugs].filter(s => !prevNodes.has(s) && !selfAdded.includes(s)) : [];
    const droppedList = (dropped || []).filter(d => !nodeSlugs.has(d.slug));

    const lines = [];
    lines.push(switchFromId
        ? '⤺ 切到历史版本 #' + switchFromId + '（未改动内容，只是把工作区换成那一版）'
        : '📦 装配台改动');
    if (prev.name !== state.name) lines.push('名称：' + (prev.name || '（未命名）') + ' → ' + state.name);
    if (prev.loader !== state.loader || prev.mcVersion !== state.mcVersion) {
        lines.push('环境：' + prev.loader + ' ' + prev.mcVersion + ' → ' + state.loader + ' ' + state.mcVersion);
    }
    if (selfAdded.length) lines.push('+ 新增：' + selfAdded.join('、'));
    if (depAdded.length) lines.push('+ 依赖补入：' + depAdded.join('、'));
    if (removed.length) lines.push('− 移除：' + removed.join('、'));
    if (droppedList.length) {
        lines.push('− 依赖剔除：' + droppedList.map(d => d.slug + '（' + reasonText(d.reason) + '）').join('、'));
    }
    for (const slug of hardFail) {
        const item = (labUnresolved.value || []).find(u => u.slug === slug);
        lines.push('⚠ 已移除：' + slug + '（' + reasonText(item && item.reason) + '）');
    }
    lines.push('当前 ' + state.modSlugs.length + ' 个模组 / ' + (nodes || []).length + ' 个节点');
    return lines.join('\n');
};

/** 给装配台顶部横幅用的一句话摘要 */
const summarize = (state, nodes, hardFail) => {
    const prev = latestPackState.value;
    const delta = prev ? state.modSlugs.length - (prev.modSlugs || []).length : state.modSlugs.length;
    const sign = delta > 0 ? '+' + delta : String(delta);
    return '最近改动：模组 ' + sign + ' → 共 ' + state.modSlugs.length
        + ' 个 / ' + (nodes || []).length + ' 个节点'
        + (hardFail && hardFail.size ? '（已移除 ' + hardFail.size + ' 个找不到的模组）' : '')
        + ' · 已记入对话';
};

/**
 * 点历史消息上的版本按钮。
 *
 * <p>没有状态的旧消息退回"打开当前图谱"（历史行为）；有状态就把工作区换成那一版再打开。
 * 当前工作区与最近一次记录的版本不一致时先确认——切换会丢弃那部分改动。
 */
export const switchToVersion = async (msg) => {
    const state = readPackState(msg && msg.packState);
    if (!state) return openLab();                       // 老消息：没有版本信息，按老行为打开当前图
    if (stateEquals(state, latestPackState.value)) return openLab();   // 就是当前这一版

    const currentState = packStateOf(packData);
    const dirty = latestPackState.value && !stateEquals(currentState, latestPackState.value);
    if (dirty && !confirm('当前装配台有还没记进对话的改动，切到这一版会丢弃它们。继续切换？')) return;

    switchFromId = msg.snapshotId || latestSnapshotId.value || '';
    applyPackState(packData, state);
    await openLab();
};

// 注册到预览钩子：delete-flow 每次预览完成都会回调它
setPreviewHook(handlePreviewResult);

// ==================== 界面用的派生状态 ====================

/** 装配台里"找不到的模组"提示行：slug + 人话原因 */
export const unresolvedRows = computed(() => (labUnresolved.value || []).map(u => ({
    slug: u.slug,
    reason: reasonText(u.reason),
    hard: HARD_FAIL_REASONS.includes(u.reason),
})));

// ==================== 整合包名称（装配台内直接改） ====================

export const editingPackName = ref(false);
export const packNameDraft = ref('');

export const startEditPackName = () => {
    packNameDraft.value = packData.name || '';
    editingPackName.value = true;
};

/**
 * 提交改名。改名也是"一版"（产品口径：名称变化同样要记流水），所以走一次 openLab：
 * 解析缓存会命中，代价只是重画一次图；副作用是版本流水里会多一条"名称：A → B"。
 */
export const commitPackName = async () => {
    if (!editingPackName.value) return;
    editingPackName.value = false;
    const next = (packNameDraft.value || '').trim();
    if (!next || next === packData.name) return;
    packData.name = next;
    await openLab();
};

/**
 * 把当前工作区状态记成"一版"并挂到给定消息上。
 *
 * <p>两种调用场合：对话轮次结束（chat.js）、"生成空图谱"的确认消息（empty-graph.js）。
 * 状态与上一次完全相同时不重复记，但**仍把当前版本号挂到消息上** —— 于是"这轮没改动模组"
 * 的消息，点开也是它当时那一版，而不是最新一版。
 */
export const recordCurrentVersion = async (msg) => {
    if (!msg) return null;
    const state = packStateOf(packData);
    if (stateEquals(state, latestPackState.value)) {
        if (latestSnapshotId.value) msg.snapshotId = latestSnapshotId.value;
        return latestSnapshotId.value;
    }
    // 直接交给 recordState：没有会话时 saveMsg 会自建一个（自愈路径），
    // 不要再在这里用 currentConvId 拦一道 —— 那会让"进页面后第一次生成空图谱"丢版本号
    return recordState(msg, state);
};
