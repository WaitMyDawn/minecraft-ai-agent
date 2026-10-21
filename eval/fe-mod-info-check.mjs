import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import {createModInfoLoader} from '../src/main/resources/static/js/utils/mod-info-loader.js';
import {bindVisibleModInfo} from '../src/main/resources/static/js/utils/visible-mod-info.js';

const tick = () => new Promise(resolve => setImmediate(resolve));
let owner = 'a', time = 0;
const cache = new Map(), calls = [];
const loader = createModInfoLoader({owner: () => owner, cached: slug => cache.has(slug),
    store: (slug, value) => cache.set(slug, value), now: () => time,
    request: (slug, signal) => new Promise((resolve, reject) => calls.push({slug, signal, resolve, reject}))});
const first = new AbortController(), second = new AbortController();
const p1 = loader.load(['shared', 'shared'], first.signal);
const p2 = loader.load(['shared'], second.signal);
await tick(); assert.equal(calls.length, 1);
first.abort(); await p1;
assert.equal(calls[0].signal.aborted, false, '另一订阅者仍需要资料时不能取消共享请求');
calls[0].resolve({slug: 'shared'}); await p2; await tick();
await loader.load(['shared']); assert.equal(calls.length, 1);

const queued = new AbortController();
const batch = loader.load(['one', 'two', 'never-started'], queued.signal);
await tick(); assert.equal(calls.length, 3, '同时最多两个实际请求');
queued.abort(); await batch;
assert(calls[1].signal.aborted && calls[2].signal.aborted);
const replacement = loader.load(['fresh']);
await tick(); assert.equal(calls.length, 3, '取消后仍须等待旧传输结束才能释放并发名额');
calls[1].resolve({}); calls[2].resolve({}); await tick();
assert.equal(calls.length, 4); assert.equal(calls[3].slug, 'fresh');
calls[3].resolve({}); await replacement; await tick();
assert(!cache.has('one'));

const broken = loader.load(['broken']); await tick();
calls.at(-1).reject(new Error('offline')); await broken; await tick();
const count = calls.length;
await loader.load(['broken']); assert.equal(calls.length, count);
time = 60_001;
const retry = loader.load(['broken']); await tick(); assert.equal(calls.length, count + 1);
calls.at(-1).resolve({}); await retry; await tick();

const old = loader.load(['late']); await tick(); const late = calls.at(-1);
owner = 'b'; loader.reset(); cache.clear(); await old;
const current = loader.load(['late']); await tick(); const fresh = calls.at(-1);
late.resolve({account: 'old'}); await tick(); assert(!cache.has('late'));
fresh.resolve({account: 'new'}); await current; await tick();
assert.equal(cache.get('late').account, 'new');

let callback, disconnected = false;
const element = new EventTarget(); element.tagName = 'DETAILS'; element.open = false;
const demands = [];
const binding = bindVisibleModInfo(element, (slugs, signal) => demands.push({slugs, signal}), cb => {
    callback = cb;
    return {observe(target) { assert.equal(target, element); }, disconnect() { disconnected = true; }};
});
binding.update(['core', 'core'], 'a'); assert.equal(demands.length, 0);
callback([{target: element, isIntersecting: true}]); assert.equal(demands.length, 0);
element.open = true; element.dispatchEvent(new Event('toggle')); assert.equal(demands.length, 1);
assert.deepEqual(demands[0].slugs, ['core']);
binding.update(['core'], 'a'); assert.equal(demands.length, 1, '无变化的组件更新不重启请求');
callback([{target: element, isIntersecting: false}]); assert(demands[0].signal.aborted);
callback([{target: element, isIntersecting: true}]); assert.equal(demands.length, 2);
element.open = false; element.dispatchEvent(new Event('toggle')); assert(demands[1].signal.aborted);
element.open = true; element.dispatchEvent(new Event('toggle'));
binding.update(['core'], 'b'); assert(demands[2].signal.aborted); assert.equal(demands.length, 4);
binding.update(['core'], null); assert(demands[3].signal.aborted);
binding.dispose(); assert(disconnected);
const disposedCount = demands.length;
binding.update(['late'], 'c');
callback([{target: element, isIntersecting: true}]);
assert.equal(demands.length, disposedCount, '卸载后迟到的观察回调不能重新发起请求');

// Execute production pagination and watchers with explicit reactive flush boundaries.
const prefsSource = await readFile(new URL('../src/main/resources/static/js/features/prefs.js', import.meta.url), 'utf8');
const watchers = [], requests = [];
const ref = value => ({value});
const fixture = {createModInfoLoader, authUser: ref({token: 'first'}), currentView: ref('chat'),
    selectionPreferences: ref({}), selectionPrefsMsg: ref(''),
    modPrefs: ref([]), blacklistItems: ref([]), categoryPrefs: ref([]), modInfoCache: ref({}),
    modPrefPage: ref(0), blacklistPage: ref(0), modPrefSearch: ref(''), addingModBySlug: ref(false),
    computed: getter => ({get value() { return getter(); }}),
    watch(source, callback, options = {}) {
        const getter = typeof source === 'function' ? source : () => source.value;
        const watcher = {getter, callback, value: getter(), cleanup: null};
        watchers.push(watcher);
        if (options.immediate) callback(watcher.value, undefined, fn => { watcher.cleanup = fn; });
    },
    authHeader: () => ({}),
    apiFetch: async url => {
        requests.push(url);
        return {json: async () => ({slug: url.split('/').at(-1)})};
    }
};
const flush = () => {
    for (const w of watchers) {
        const value = w.getter();
        if (value !== w.value) {
            const previous = w.value; w.value = value; w.cleanup?.();
            w.callback(value, previous, fn => { w.cleanup = fn; });
        }
    }
};
globalThis.metadataPageFixture = fixture;
const sections = [
    prefsSource.slice(prefsSource.indexOf('export const filteredModPrefs'), prefsSource.indexOf('// ===== 偏好列表')),
    prefsSource.slice(prefsSource.indexOf('const metadataOwner'), prefsSource.indexOf('// 手动 slug 输入添加')),
    prefsSource.slice(prefsSource.indexOf('export const loadPrefs ='), prefsSource.indexOf('// 进入设置页')),
    prefsSource.slice(prefsSource.indexOf('export const loadBlacklist ='), prefsSource.indexOf('export const addBlacklistBySlug'))
];
const pages = await import('data:text/javascript;base64,' + Buffer.from(
    `const {${Object.keys(fixture).join(',')}}=globalThis.metadataPageFixture;\n` + sections.join('\n')).toString('base64'));
assert.deepEqual(fixture.selectionPreferences.value,{addons:true,replacements:true,overlap:true,generation:true});
fixture.modPrefs.value = Array.from({length: 100}, (_, i) => ({slug: 'pref-' + i}));
fixture.blacklistItems.value = Array.from({length: 100}, (_, i) => ({slug: 'black-' + i}));
flush(); await tick(); assert.equal(requests.length, 0, '后台刷新列表不触发整表资料读取');
fixture.currentView.value = 'profile'; flush();
for (let i = 0; i < 12; i++) await tick();
assert.equal(requests.length, 20, '只读取偏好和黑名单各自当前页的10项');
fixture.modPrefPage.value = 1; flush();
for (let i = 0; i < 8; i++) await tick();
assert.equal(requests.length, 30, '翻页复用黑名单与已有页面资料');
fixture.modPrefSearch.value = 'pref-99'; flush();
for (let i = 0; i < 3; i++) await tick();
assert.equal(fixture.modPrefPage.value, 0);
assert.equal(requests.length, 31);
fixture.modInfoCache.value['pref-0'] = {title: 'pref-99'};
assert.equal(pages.filteredModPrefs.value.length, 1, '检索结果不随已加载标题改变，slug与类别在全列表本地检索');
fixture.currentView.value = 'chat'; flush();
fixture.modPrefPage.value = 2; flush(); await tick(); assert.equal(requests.length, 31);

let releaseBody;
const raceFixture = {...fixture, apiFetch: async () => ({json: () => new Promise(resolve => { releaseBody = resolve; })})};
globalThis.metadataRaceFixture = raceFixture;
const raceModule = await import('data:text/javascript;base64,' + Buffer.from(
    `const {${Object.keys(raceFixture).join(',')}}=globalThis.metadataRaceFixture;\n` + sections.join('\n')).toString('base64'));
const stale = raceModule.loadBlacklist(); await tick();
fixture.authUser.value = {token: 'second',selectionPreferences:{overlap:false}}; flush();
assert.deepEqual(fixture.selectionPreferences.value,{addons:true,replacements:true,overlap:false,generation:true});
releaseBody([{slug: 'old-account'}]); await stale;
assert.deepEqual(fixture.blacklistItems.value, [], '响应体解析晚于账号切换时不得重新填入旧账号列表');
assert.deepEqual(fixture.modInfoCache.value, {});
const bodies = [];
const refreshFixture = {...fixture, apiFetch: async () => ({json: () => new Promise(resolve => bodies.push(resolve))})};
globalThis.metadataRefreshFixture = refreshFixture;
const refreshModule = await import('data:text/javascript;base64,' + Buffer.from(
    `const {${Object.keys(refreshFixture).join(',')}}=globalThis.metadataRefreshFixture;\n` + sections.join('\n')).toString('base64'));
const earlier = refreshModule.loadBlacklist(); await tick();
const later = refreshModule.loadBlacklist(); await tick();
bodies[1]([{slug: 'latest'}]); await later;
bodies[0]([{slug: 'outdated'}]); await earlier;
assert.deepEqual(fixture.blacklistItems.value, [{slug: 'latest'}], '同账号旧刷新不能覆盖较新的列表');
delete globalThis.metadataPageFixture; delete globalThis.metadataRaceFixture;
delete globalThis.metadataRefreshFixture;
console.log('资料加载回归通过：共享取消、并发界限、翻页取消、失败冷却、账号迟到隔离、可见且展开触发。');
