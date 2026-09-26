// 构筑图谱渲染：左侧依赖树的数据构建 + 右侧 vis-network DAG。
//
// 从 index.html 的 setup 里整体搬出来的，原样保留逻辑，只做了两件事：
//   1) 去掉 setup 的 12 空格缩进；
//   2) 把原来靠"同作用域可见"的东西改成显式来源：
//      - networkGraph 变成模块级单例，外部只通过 getGraphPositions() 拿旧坐标；
//      - 删除标记的切换 toggleDeleteMark 也在本模块（它和 paintGraphNode 不可分）。
//
// ⚠️ networkGraph 是模块级单例：本应用只 mount 一次，安全。若将来出现重复 mount
//    （HMR、多实例），第二次 renderGraph 会把第一次的实例变成僵尸（监听器还在，容器已被覆盖）。
//
// ⚠️ vis 是全局的（index.html 用 UMD script 引入），模块里裸写 vis 依然解析到 window.vis。

import {nextTick, computed } from 'vue';
import {childrenMap, conflictIds, ctxMenu, deleteMarked, deleteMode, globalHighlightId, nodesMap, previewData, rootTreeNodes, searchQuery, selectedMods} from '../store.js';

// 删除标记的切换（改 store 里的 deleteMarked + 重绘本节点）。
// 它和 paintGraphNode 是一件事，所以住在图谱模块；delete-flow.js 从这儿 import，
// 保证两个模块是单向依赖（delete-flow -> graph），不会互相 import。
export const toggleDeleteMark = (id) => {
    const s = new Set(deleteMarked.value);
    if (s.has(id)) s.delete(id); else s.add(id);
    deleteMarked.value = s;
    paintGraphNode(id);
};


let networkGraph = null;

/** 当前图谱各节点的坐标（没有图时返回 null）。用于重绘时复用旧坐标，避免整图跳动。 */
export const getGraphPositions = () => (networkGraph ? networkGraph.getPositions() : null);

// 定位左侧树节点（被搜索过滤隐藏时先清空过滤再定位）
export const locateTreeNode = (id) => {
    nextTick(() => {
        const el = document.getElementById('tree-node-' + id);
        if (el) {
            el.scrollIntoView({behavior: 'smooth', block: 'center'});
            return;
        }
        if (searchQuery.value) {
            searchQuery.value = '';
            nextTick(() => {
                const el2 = document.getElementById('tree-node-' + id);
                if (el2) el2.scrollIntoView({behavior: 'smooth', block: 'center'});
            });
        }
    });
};

export const buildTreeData = (data) => {
    const map = {};
    data.nodes.forEach(n => map[n.id] = n);
    nodesMap.value = map;
    const reqBy = {};
    const depOn = {};
    data.edges.forEach(e => {
        if (!reqBy[e.from]) reqBy[e.from] = [];
        reqBy[e.from].push(e.to);
        if (!depOn[e.to]) depOn[e.to] = [];
        depOn[e.to].push(e.from);
    });
    childrenMap.value = depOn;
    rootTreeNodes.value = data.nodes.filter(n => !reqBy[n.id]).sort((a, b) => (a.slug || '').localeCompare(b.slug || ''));
};

export const toggleModState = (id) => {
    const newSet = new Set(selectedMods.value);
    if (newSet.has(id)) newSet.delete(id); else newSet.add(id);
    selectedMods.value = newSet;
    paintGraphNode(id);
};

/**
 * 重绘单个 DAG 节点的"激活"配色。
 * 配色语义随模式切换：普通模式=是否导出(靛蓝)，删除模式=是否待删(红色)。
 */
export const paintGraphNode = (id) => {
    if (!networkGraph) return;
    const active = deleteMode.value
        ? deleteMarked.value.has(id)
        : selectedMods.value.has(id);
    const idle = deleteMode.value
        ? {border: '#fca5a5', background: '#fff'}
        : {border: '#cbd5e1', background: '#f1f5f9'};
    networkGraph.body.data.nodes.update({
        id: id,
        color: active
            ? (deleteMode.value ? {border: '#dc2626', background: '#fee2e2'}
                                : {border: '#4f46e5', background: '#fff'})
            : idle,
        font: {color: active ? '#1e293b' : '#94a3b8'}
    });
};

export const repaintAllGraphNodes = () => {
    if (!networkGraph || !previewData.value) return;
    previewData.value.nodes.forEach(n => paintGraphNode(n.id));
};

export const renderGraph = (fixedPositions) => {
    const container = document.getElementById('network-canvas');
    if (!container) return;
    const visNodes = new vis.DataSet(previewData.value.nodes.map(n => {
        // 缺少必需前置的节点用琥珀色描边警示
        const conflicted = conflictIds.value.has(n.id);
        const item = {
            id: n.id,
            label: n.title,
            title: n.description,
            shape: 'circularImage',
            image: n.icon || 'https://cdn.modrinth.com/placeholder.svg',
            size: 24,
            font: {size: 12, face: 'sans-serif'},
            color: {border: conflicted ? '#f59e0b' : '#4f46e5', background: '#fff'},
            borderWidth: 3
        };
        // 复用旧坐标，避免删除后重绘导致整图重新布局跳动
        const p = fixedPositions && fixedPositions[n.id];
        if (p) { item.x = p.x; item.y = p.y; }
        return item;
    }));
    const visEdges = new vis.DataSet(previewData.value.edges.map(e => ({
        from: e.from,
        to: e.to,
        arrows: 'to',
        dashes: e.sourceType === 'USER_FEEDBACK' ? false : (e.dashes || false),
        color: {color: e.sourceType === 'USER_FEEDBACK' ? '#7c3aed' : (e.dashes ? '#f59e0b' : '#94a3b8')},
        smooth: {type: 'continuous'}
    })));

    const options = {
        layout: {hierarchical: false},
        physics: {
            solver: 'barnesHut',
            barnesHut: {
                gravitationalConstant: -2000,
                centralGravity: 0.3,
                springLength: 150,
                springConstant: 0.04,
                damping: 0.2,
                avoidOverlap: 1
            },
            stabilization: {iterations: 300}
        },
        interaction: {hover: true, tooltipDelay: 200}
    };

    networkGraph = new vis.Network(container, {nodes: visNodes, edges: visEdges}, options);

    // 物理引擎控制: 稳定后冻结; 拖动时唤醒; 松手后渐进减速再冻结
    const enablePhysics = (dampingVal, iterations) => {
        networkGraph.setOptions({
            physics: {
                enabled: true,
                solver: 'barnesHut',
                barnesHut: {
                    gravitationalConstant: -2000,
                    centralGravity: 0.3,
                    springLength: 150,
                    springConstant: 0.04,
                    damping: dampingVal,
                    avoidOverlap: 0.5
                },
                stabilization: {
                    enabled: true,
                    iterations: iterations,
                    fit: false
                }
            }
        });
    };

    networkGraph.on("stabilizationIterationsDone", function () {
        networkGraph.setOptions({physics: {enabled: false}});
    });

    networkGraph.on("dragStart", function () {
        ctxMenu.visible = false;
        // 拖动时完全启用物理 (低阻尼, 长迭代, 让节点自由运动)
        enablePhysics(0.15, 1000);
    });

    networkGraph.on("dragEnd", function () {
        // 松手后渐进减速: 分两阶段
        // 阶段1 (立即): 中阻尼 400 迭代 → 快速减速
        enablePhysics(0.3, 400);
        // 阶段2 (2s后): 高阻尼 200 迭代 → 接近静止 → 完成后冻结
        setTimeout(() => {
            enablePhysics(0.6, 200);
        }, 2000);
    });

    // 🎯 点击语义：第一次点击 = 定位 + 高亮（不改勾选）；对同一节点再点一次才切换勾选
    networkGraph.on("click", (params) => {
        ctxMenu.visible = false;
        if (params.nodes.length === 0) {
            globalHighlightId.value = null;   // 点空白 = 取消选中
            return;
        }
        const nodeId = params.nodes[0];
        if (globalHighlightId.value === nodeId) {
            // 第二次点击：按当前模式切换状态（普通=是否导出，删除模式=是否待删）
            if (deleteMode.value) toggleDeleteMark(nodeId);
            else toggleModState(nodeId);
        } else {
            globalHighlightId.value = nodeId; // 第一次点击：仅定位（勾选保持不变）
        }
        locateTreeNode(nodeId);
    });

    // 🖱️ 右键菜单：在节点上右键弹出删除/定位/打开 Modrinth
    networkGraph.on("oncontext", (params) => {
        const evt = params.event;
        if (evt && typeof evt.preventDefault === 'function') evt.preventDefault();
        if (evt && evt.srcEvent && typeof evt.srcEvent.preventDefault === 'function') {
            evt.srcEvent.preventDefault();
        }
        if (params.nodes.length === 0) {
            ctxMenu.visible = false;
            return;
        }
        const dom = (params.pointer && params.pointer.DOM) || {x: 20, y: 20};
        ctxMenu.nodeId = params.nodes[0];
        ctxMenu.x = dom.x;
        ctxMenu.y = dom.y;
        ctxMenu.visible = true;
        globalHighlightId.value = params.nodes[0];
    });

    networkGraph.on("zoom", () => { ctxMenu.visible = false; });
};

// ---------- 左侧依赖树的搜索过滤 ----------
// 与 locateTreeNode / 图谱同属 lab 视图；用的是同一份 nodesMap/childrenMap。
// 🔥 深度搜索算法 (DFS)：判断一个节点及其所有子孙节点是否包含关键词
const doesNodeOrChildrenMatch = (nodeId, kw) => {
    const node = nodesMap.value[nodeId];
    if (!node) return false;

    // 自己匹配上了
    if ((node.title && node.title.toLowerCase().includes(kw)) ||
        (node.slug && node.slug.toLowerCase().includes(kw))) {
        return true;
    }

    // 自己没匹配上，去问儿子们
    const children = childrenMap.value[nodeId] || [];
    for (let childId of children) {
        if (doesNodeOrChildrenMatch(childId, kw)) return true;
    }

    return false;
};

export const filteredRootNodes = computed(() => {
    let arr = rootTreeNodes.value;
    if (searchQuery.value && searchQuery.value.trim() !== '') {
        const kw = searchQuery.value.toLowerCase();
        // 如果某个根节点底下的隐藏层级有符合的模组，这个根节点就会显示出来！
        arr = arr.filter(n => doesNodeOrChildrenMatch(n.id, kw));
    }
    return arr;
});
