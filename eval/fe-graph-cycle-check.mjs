import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

const read = path => readFile(new URL(path, import.meta.url), 'utf8');
const traversal = await read('../src/main/resources/static/js/utils/graph-traversal.js');
const {matchingAncestors, coverTreeRoots} = await import('data:text/javascript;base64,' + Buffer.from(traversal).toString('base64'));
const source = await read('../src/main/resources/static/js/features/graph.js');
const ref = value => ({value});
const context = {
    matchingAncestors, coverTreeRoots, computed: fn => ({get value() { return fn(); }}),
    nodesMap: ref({}), childrenMap: ref({}), rootTreeNodes: ref([]), searchQuery: ref(''),
    selectedMods: ref(new Set(['a'])), conflictIds: ref(new Set()), ctxMenu: {},
    deleteMarked: ref(new Set()), deleteMode: ref(false), globalHighlightId: ref(null), previewData: ref(null)
};
vm.runInNewContext(source.replace(/^import .*;\r?\n/gm, '').replace(/^export /gm, '')
    + '\nglobalThis.graph = {buildTreeData, filteredRootNodes};', context);
const nodes = ids => ids.map(id => ({id, slug: `mod-${id}`}));
const load = (ids, edges) => context.graph.buildTreeData({nodes: nodes(ids), edges: edges.map(([from,to]) => ({from,to}))});
const search = query => {
    context.searchQuery.value = query;
    return Array.from(context.graph.filteredRootNodes.value, n => n.id);
};

load(['root','a','b'], [['a','root'],['b','a'],['a','b']]);
assert.deepEqual(search('zz'), []);
assert.deepEqual(search('MOD-B'), ['root']);
assert.deepEqual(search(' mod-b '), ['root']);
assert.deepEqual(search('  '), ['root']);
assert.deepEqual([...context.selectedMods.value], ['a']);

load(['a','b'], [['a','b'],['b','a']]);
assert.deepEqual(search(''), ['a']);
assert.deepEqual(search('mod-b'), ['a']);
assert.deepEqual(search('zz'), []);
load(['self'], [['self','self']]);
assert.deepEqual(search('mod-self'), ['self']);
assert.deepEqual(search('zz'), []);

load(['r1','r2','a','b','shared'], [['a','r1'],['b','r2'],['shared','a'],['shared','b']]);
assert.deepEqual(search('shared'), ['r1','r2']);
assert.deepEqual(search('mod-a'), ['r1']);
load(['plain','a','b','isolated'], [['a','b'],['b','a'],['missing','plain']]);
assert.deepEqual(new Set(search('')), new Set(['plain','isolated','a']));
assert.deepEqual(search('mod-b'), ['a']);
assert.equal(context.childrenMap.value.missing, undefined);

const count = 15000;
const chain = nodes(Array.from({length: count}, (_,i) => String(i)));
context.graph.buildTreeData({nodes:chain, edges:chain.slice(1).map((n,i) => ({from:n.id,to:String(i)}))});
assert.deepEqual(search(`mod-${count-1}`), ['0']);
assert.deepEqual(search('zz'), []);
load([1,2], [[2,1],[1,2]]);
assert.deepEqual(search('mod-2'), [1]);
context.graph.buildTreeData({nodes:[{id:'new',title:'New Title'}],edges:[]});
assert.deepEqual(search('title'), ['new']);
assert.deepEqual(search('mod-b'), []);

// 以逐起点可达性作为独立判据，覆盖混合环、菱形及不连通关系。
let randomState = 73;
const random = () => (randomState = (randomState * 1664525 + 1013904223) >>> 0) / 4294967296;
for (let sample = 0; sample < 80; sample++) {
    const ids = Array.from({length:8}, (_,i) => `n${i}`);
    const map = Object.fromEntries(nodes(ids).map(n => [n.id,n]));
    const children = Object.fromEntries(ids.map(id => [id, ids.filter(() => random() < 0.2)]));
    const actual = matchingAncestors(map, children, 'mod-n3');
    const reachable = id => {
        const queue = [id], seen = new Set(queue);
        for (let i=0; i<queue.length; i++) for (const child of children[queue[i]]) if (!seen.has(child)) {
            seen.add(child); queue.push(child);
        }
        return seen;
    };
    for (const id of ids) assert.equal(actual.has(id), reachable(id).has('n3'));
    const roots = coverTreeRoots(Object.values(map), [], children);
    assert.deepEqual(new Set(roots.flatMap(n => [...reachable(n.id)])), new Set(ids));
}

// 删除闭包必须排除种子，且环与共享节点均只访问一次。
const deletion = await read('../src/main/resources/static/js/features/delete-flow.js');
const start = deletion.indexOf('const closure =');
const end = deletion.indexOf('\n};', start) + 3;
const closure = vm.runInNewContext(deletion.slice(start,end) + '\nclosure;');
assert.deepEqual(Array.from(closure(['a'], {a:['a','b'],b:['a','c'],c:['b']})), ['b','c']);
assert.deepEqual(Array.from(closure(['a','b'], {a:['b'],b:['a','c'],c:['b']})), ['c']);
const tree = await read('../src/main/resources/static/js/components/TreeNode.js');
const html = await read('../src/main/resources/static/index.html');
assert.match(tree, /props\.visited\.includes\(props\.node\.id\)/);
assert.match(html, /v-if="isCycle"[\s\S]*v-else-if="children.length > 0"/);
console.log('图谱循环检查通过：搜索、无根分量、自环、共享依赖、15000节点深链、状态更新、删除闭包及树渲染截断。');
