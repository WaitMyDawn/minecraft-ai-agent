// 删除流程：两段式删除确认 / 批量删除模式 / 右键菜单 / 执行删除 + 后台回填 / 键盘快捷键 / 装配台入口。
//
// 从 index.html 的 setup 里整体搬出来的（原 1099-1376 与 openLab），逻辑原样保留。
//
// ⚠️ provideDeleteContract() 必须在 setup() 里【同步】调用。
//    Vue 的 provide 依赖"当前组件实例"，模块顶层调用拿不到实例 —— 拿不到时不报错，
//    只是 TreeNode 里 9 个 inject 全变 undefined，报错要等用户点勾选框才出现。
// ⚠️ 这 9 个 key 与 components/TreeNode.js 的 inject 一一对应，改一边就是改契约。
//
// 与 graph.js 是单向依赖（本模块 -> graph.js）：删除标记切换 toggleDeleteMark 住在 graph.js，
// 因为它就是"改 store + paintGraphNode"，和 paintGraphNode 不可分。

import {childrenMap, conflictIds, ctxMenu, currentView, deleteBusy, deleteConflicts, deleteDialog, deleteMarked, deleteMode, enableUserFeedbackRules, globalHighlightId, isRefreshing, isResolving, nodesMap, packData, previewData, selectedMods} from '../store.js';
import {authHeader} from '../api.js';
import {buildTreeData, getGraphPositions, locateTreeNode, renderGraph, repaintAllGraphNodes, toggleDeleteMark, toggleModState} from './graph.js';
import {computed, nextTick, provide} from 'vue';

/** 把删除能力注入给递归树组件（TreeNode.js）。只能在 setup() 里同步调用。 */
export const provideDeleteContract = () => {
    provide('selectedMods', selectedMods);
    provide('nodesMap', nodesMap);
    provide('childrenMap', childrenMap);
    provide('globalHighlightId', globalHighlightId);
    provide('toggleMod', (id) => toggleModState(id));
    provide('deleteMode', deleteMode);
    provide('deleteMarked', deleteMarked);
    provide('toggleDeleteMark', (id) => toggleDeleteMark(id));
    provide('conflictIds', conflictIds);
};
// ==========================================
// 🗑️ 删除能力状态（P1/P2）
// 设计原则：勾选 = 本次导出是否包含（临时，会话内）；删除 = 从整合包清单移除（持久，写入 packData.excludedSlugs）
// ==========================================


// 两段式删除确认弹窗状态
export const closeDeleteDialog = () => {
    deleteDialog.visible = false;
    deleteDialog.stage = '';
    deleteDialog.seed = [];
    deleteDialog.dependents = [];
    deleteDialog.prerequisites = [];
};


// ---------- 删除相关的展示辅助 ----------
// 节点 id -> 可读名（优先 slug，便于和 Modrinth 对照）
export const idLabel = (id) => {
    const n = nodesMap.value[id];
    return n ? (n.slug || n.title || id) : id;
};
export const conflictSummary = computed(() =>
    deleteConflicts.value.map(c => `${c.requiredBy} ← 缺失 ${c.missing}`).join('  |  '));
// 弹窗标题里展示"本次要删除的种子模组"可读名
export const seedLabels = computed(() => deleteDialog.seed.map(idLabel).join('、'));

// ---------- DAG 邻接关系 ----------
// 注意 preview 返回的边方向：from = 前置(被依赖方)，to = 依赖方
const buildAdjacency = () => {
    const dependentsOf = {};     // X -> 依赖 X 的模组（删 X 会波及它们）
    const prerequisitesOf = {};  // Y -> Y 依赖的模组（删 Y 时可顺带删它们）
    for (const e of ((previewData.value && previewData.value.edges) || [])) {
        (dependentsOf[e.from] || (dependentsOf[e.from] = [])).push(e.to);
        (prerequisitesOf[e.to] || (prerequisitesOf[e.to] = [])).push(e.from);
    }
    return {dependentsOf, prerequisitesOf};
};

// 求传递闭包（BFS），排除起点自身
const closure = (seedIds, nextMap) => {
    const seedSet = new Set(seedIds);
    const result = new Set();
    const queue = [...seedIds];
    while (queue.length > 0) {
        const cur = queue.shift();
        for (const nxt of (nextMap[cur] || [])) {
            if (seedSet.has(nxt) || result.has(nxt)) continue;
            result.add(nxt);
            queue.push(nxt);
        }
    }
    return [...result];
};

// ---------- 两段式删除流程 ----------
// 第 1 段：被删项若是别人的必需前置 → 提示"删除 X 和依赖 X 的所有模组 / 不删除"
const startDeleteFlow = (ids) => {
    const seed = [...new Set(ids)].filter(id => nodesMap.value[id]);
    if (seed.length === 0) return;
    const {dependentsOf} = buildAdjacency();
    const cascade = closure(seed, dependentsOf);
    if (cascade.length > 0) {
        Object.assign(deleteDialog, {
            visible: true, stage: 'cascade', seed, dependents: cascade, prerequisites: []
        });
        return;
    }
    askDeletePrerequisites(seed);
};

// 第 2 段：被删项自身有前置 → 提示"是否顺带删除所有前置"
const askDeletePrerequisites = (ids) => {
    const {prerequisitesOf} = buildAdjacency();
    const prereqs = closure(ids, prerequisitesOf);
    if (prereqs.length > 0) {
        Object.assign(deleteDialog, {
            visible: true, stage: 'prereq', seed: ids, dependents: [], prerequisites: prereqs
        });
        return;
    }
    closeDeleteDialog();
    performDelete(ids);
};

// 用户选择「删除 X 及依赖它的模组」→ 合并后继续询问前置
export const acceptCascade = () => {
    const merged = [...new Set([...deleteDialog.seed, ...deleteDialog.dependents])];
    askDeletePrerequisites(merged);
};

// 用户选择「只删除选中的」→ 直接执行
export const acceptSeedOnly = () => {
    const seed = [...deleteDialog.seed];
    closeDeleteDialog();
    performDelete(seed);
};

// 用户选择「同时删除前置」→ 新增的前置可能又被别的模组依赖，需要再确认一轮
// （每轮集合严格增长，图有限，必定收敛）
export const acceptPrerequisites = () => {
    const merged = [...new Set([...deleteDialog.seed, ...deleteDialog.prerequisites])];
    const {dependentsOf} = buildAdjacency();
    const cascade = closure(merged, dependentsOf);
    if (cascade.length > 0) {
        Object.assign(deleteDialog, {
            visible: true, stage: 'cascade', seed: merged, dependents: cascade, prerequisites: []
        });
        return;
    }
    closeDeleteDialog();
    performDelete(merged);
};

// 该前置是否仍被"不在删除集里"的模组需要（用于弹窗内的红色提示）
export const stillNeeded = (prereqId) => {
    const {dependentsOf} = buildAdjacency();
    const inDelete = new Set([...deleteDialog.seed, ...deleteDialog.prerequisites]);
    return (dependentsOf[prereqId] || []).some(d => !inDelete.has(d));
};

// ---------- 批量删除模式（左侧 🗑️） ----------
export const enterDeleteMode = () => {
    const all = ((previewData.value && previewData.value.nodes) || []).map(n => n.id);
    // 规则：进入删除模式后，"已勾选(要导出)"→未勾选，"未勾选(不导出)"→已勾选
    deleteMarked.value = new Set(all.filter(id => !selectedMods.value.has(id)));
    deleteMode.value = true;
    repaintAllGraphNodes();
};
export const exitDeleteMode = () => {
    const wasDeleteMode = deleteMode.value;
    deleteMode.value = false;
    deleteMarked.value = new Set();
    if (wasDeleteMode) repaintAllGraphNodes();
};

export const confirmBatchDelete = () => {
    if (deleteMarked.value.size === 0) return;
    startDeleteFlow([...deleteMarked.value]);
};

// ---------- 右键菜单 ----------
export const ctxMenuLabel = computed(() => idLabel(ctxMenu.nodeId));
export const ctxLocate = () => {
    const id = ctxMenu.nodeId;
    ctxMenu.visible = false;
    if (!id) return;
    globalHighlightId.value = id;
    locateTreeNode(id);
};
export const ctxOpenModrinth = () => {
    const node = nodesMap.value[ctxMenu.nodeId];
    ctxMenu.visible = false;
    if (node && node.slug) window.open('https://modrinth.com/mod/' + node.slug, '_blank');
};
export const ctxDelete = () => {
    const id = ctxMenu.nodeId;
    ctxMenu.visible = false;
    if (id) startDeleteFlow([id]);
};

// ---------- 执行删除：本地立即移除 + 后台异步重跑回填 ----------
const performDelete = async (ids) => {
    if (!previewData.value || ids.length === 0) return;
    const idSet = new Set(ids);
    const removedNodes = previewData.value.nodes.filter(n => idSet.has(n.id));
    if (removedNodes.length === 0) return;
    deleteBusy.value = true;
    const removedSlugs = removedNodes.map(n => n.slug).filter(Boolean);

    // 1) 本地即时移除节点与相关边，立刻给用户反馈
    const localConflicts = [];
    for (const e of (previewData.value.edges || [])) {
        if (idSet.has(e.from) && !idSet.has(e.to)) {
            localConflicts.push({requiredBy: idLabel(e.to), missing: idLabel(e.from)});
        }
    }
    const remainNodes = previewData.value.nodes.filter(n => !idSet.has(n.id));
    const remainEdges = previewData.value.edges.filter(e => !idSet.has(e.from) && !idSet.has(e.to));
    deleteConflicts.value = localConflicts;

    previewData.value = {...previewData.value, nodes: remainNodes, edges: remainEdges};
    // 同步整合包清单 + 记入排除名单（后续 preview/chat 都不会把它补回来）
    packData.modSlugs = packData.modSlugs.filter(s => !removedSlugs.includes(s));
    packData.excludedSlugs = [...new Set([...(packData.excludedSlugs || []), ...removedSlugs])];

    const newSelected = new Set(selectedMods.value);
    ids.forEach(id => newSelected.delete(id));
    selectedMods.value = newSelected;
    if (idSet.has(globalHighlightId.value)) globalHighlightId.value = null;
    exitDeleteMode();

    buildTreeData(previewData.value);
    nextTick(() => renderGraph(getGraphPositions()));
    deleteBusy.value = false;

    // 2) 后台异步重跑 preview，用权威解析结果回填（不阻塞界面）
    refreshPreviewInBackground();
};

// 后台重跑：保持用户勾选状态，仅用服务端结果校正图谱与断链诊断
const refreshPreviewInBackground = async () => {
    if (isRefreshing.value) return;
    isRefreshing.value = true;
    try {
        const res = await fetch('/api/modpack/preview', {
            method: 'POST', headers: {'Content-Type': 'application/json', ...authHeader()},
            body: JSON.stringify({...packData, excludeUserFeedbackRules: !enableUserFeedbackRules.value})
        });
        const data = await res.json();
        if (!data || !data.nodes) return;

        // 勾选状态继承：原有节点沿用，新增节点默认勾选，已消失的忽略
        const oldNodes = nodesMap.value;
        const oldSel = selectedMods.value;
        const nextSel = new Set();
        data.nodes.forEach(n => {
            if (!oldNodes[n.id] || oldSel.has(n.id)) nextSel.add(n.id);
        });

        // 服务端诊断优先（它基于权威解析结果；为空则清空本地推断）
        deleteConflicts.value = Array.isArray(data.conflicts) ? data.conflicts : [];
        conflictIds.value = new Set(
            data.nodes.filter(n => deleteConflicts.value.some(c => c.requiredBy === n.slug))
                      .map(n => n.id)
        );

        const positions = getGraphPositions();
        previewData.value = data;
        selectedMods.value = nextSel;
        buildTreeData(data);
        nextTick(() => renderGraph(positions));
    } catch (e) {
        console.warn('后台依赖校验失败，保留本地结果', e);
    } finally {
        isRefreshing.value = false;
    }
};

// ---------- 全局键盘：Delete 删除选中节点 / Esc 关闭浮层 ----------
const isTypingTarget = (el) => {
    if (!el) return false;
    const tag = (el.tagName || '').toUpperCase();
    return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable === true;
};
const onGlobalKeyDown = (e) => {
    if (e.key === 'Escape') {
        if (ctxMenu.visible) { ctxMenu.visible = false; return; }
        if (deleteDialog.visible) { closeDeleteDialog(); return; }
        if (deleteMode.value) { exitDeleteMode(); return; }
        return;
    }
    if (currentView.value !== 'lab' || deleteDialog.visible) return;
    if (isTypingTarget(document.activeElement)) return;
    if ((e.key === 'Delete' || e.key === 'Backspace') && globalHighlightId.value) {
        e.preventDefault();
        startDeleteFlow([globalHighlightId.value]);
    }
};
window.addEventListener('keydown', onGlobalKeyDown);

// ---------- 装配台入口 ----------
// 放在本模块，是因为它第一件事就是 exitDeleteMode() + 清断链提示；
// 它调用的 buildTreeData/renderGraph 来自 graph.js。将来把 lab 视图整体搬成模块时，它应该跟着走。
export const openLab = async () => {
    currentView.value = 'lab';
    isResolving.value = true;
    // 重新进入图谱：退出删除模式并清掉上一轮的断链提示
    exitDeleteMode();
    deleteConflicts.value = [];
    conflictIds.value = new Set();
    try {
        const res = await fetch('/api/modpack/preview', {
            method: 'POST', headers: {'Content-Type': 'application/json', ...authHeader()},
            body: JSON.stringify({...packData, excludeUserFeedbackRules: !enableUserFeedbackRules.value})
        });
        const data = await res.json();
        previewData.value = data;
        selectedMods.value = new Set(data.nodes.map(n => n.id));
        buildTreeData(data);
        setTimeout(() => renderGraph(), 100);
    } finally {
        isResolving.value = false;
    }
};
