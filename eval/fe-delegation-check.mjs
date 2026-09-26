// 前端取数器的回归检查。跑法：node eval/fe-delegation-check.mjs（只要 Node，无 npm 依赖）
//
// 直接 import 页面真正在用的那个模块（static/js/modrinth-client.js）。
// 不抄一份到测试里——抄一份就只测了副本，真正的页面代码照样可以坏掉。
// Java 那套单测完全覆盖不到这段 JS。
//
// 重点守两件事：
//   ① 能批量的必须合并（5 个需求只发 4 次请求，而不是 5 次）；
//   ② "上游确实没有"(missing) 与 "我没抓到"(unavailable) 绝不能混——混了服务器就会把
//      网络不通读成"这个模组不存在"，产出一个缺模组的包，兜底也失效。
import {DELEGATION, fulfilWanted, readChatResponse}
    from '../src/main/resources/static/js/modrinth-client.js';

// ---- mock fetch：记录调用，并按端点返回假数据 ----
let calls = [];
let rateLimitOnce = false;

function jsonResponse(body) {
    return {
        ok: true, status: 200,
        headers: {get: () => null},
        json: async () => body
    };
}

globalThis.fetch = async (url) => {
    calls.push(url);
    if (rateLimitOnce) {
        rateLimitOnce = false;
        return {
            ok: false, status: 429,
            headers: {get: (h) => (h === 'X-Ratelimit-Reset' ? '0' : null)},
            json: async () => ({})
        };
    }
    if (url.includes('/v2/projects?')) {
        // 故意只回两个里的一个：另一个必须是 missing，不能被当成"正常结果"
        return jsonResponse([{id: 'P1', slug: 'create', title: 'Create'}]);
    }
    if (url.includes('/v2/versions?')) {
        return jsonResponse([{id: 'v1', game_versions: ['1.20.1'], loaders: ['forge']}]);
    }
    if (url.includes('/v2/search?')) {
        return jsonResponse({hits: [{slug: 'create', title: 'Create'}], total_hits: 1});
    }
    if (url.includes('/v2/project/')) {
        const slug = url.match(/\/v2\/project\/([^/]+)\/version/)[1];
        if (slug === 'offline') throw new Error('network down');
        return jsonResponse(slug === 'ghost'
            ? []
            : [{id: 'v-' + slug, game_versions: ['1.20.1'], loaders: ['forge']}]);
    }
    throw new Error('unexpected url ' + url);
};

const assert = (cond, msg) => {
    if (!cond) {
        console.error('FAIL: ' + msg);
        process.exitCode = 1;
    } else {
        console.log('ok  : ' + msg);
    }
};

// ---- 用例 1：批量合并 + missing 区分 + env_version 并发 ----
const wanted = [
    {kind: 'project', key: 'create'},
    {kind: 'project', key: 'jei'},
    {kind: 'version', key: 'v1'},
    {kind: 'env_version', key: 'create|1.20.1|["forge"]', project: 'create', mc: '1.20.1', loader: 'forge'},
    {kind: 'env_version', key: 'ghost|1.20.1|["forge"]', project: 'ghost', mc: '1.20.1', loader: 'forge'}
];
calls = [];
const facts = await fulfilWanted(wanted);

assert(calls.length === 4,
    `5 个需求应只用 4 次请求（2 个 project 合并成 1 次、1 个 version 1 次、2 个 env_version 各 1 次），实际 ${calls.length}`);
assert(calls.filter(u => u.includes('/v2/projects?')).length === 1, 'project 需求被合并成一次批量请求');
// create / v1 / create 的版本 取到；jei（批量端点静默丢弃）与 ghost（空数组）取不到
assert(facts.answered.length === 3, `answered 应为 3，实际 ${facts.answered.length}`);
assert(facts.missing.length === 2
    && facts.missing.some(m => m.key === 'jei' && m.kind === 'project')
    && facts.missing.some(m => m.key === 'ghost|1.20.1|["forge"]' && m.kind === 'env_version'),
    '取不到的两类（批量端点静默丢弃 / 返回空数组）都必须记进 missing，'
    + '否则服务器会把"不存在的模组"当成正常结果收下');
assert(facts.missing.every(m => m.kind), 'missing 条目必须带 kind，否则服务器无法配对');
assert(facts.client.requests === 4 && facts.client.via === 'browser', 'client 回执要如实上报请求数');

// ---- 用例 2：429 退避后能恢复 ----
calls = [];
rateLimitOnce = true;
const retried = await fulfilWanted([{kind: 'version', key: 'v1'}]);
assert(retried.answered.length === 1, '429 重试后应拿到结果');
assert(retried.client.rateLimited === 1, '429 次数要如实上报');
assert(retried.client.requests === 2, `重试应记两次请求，实际 ${retried.client.requests}`);

// ---- 用例 3：响应形态分流 ----
const textRes = {headers: {get: () => 'text/plain;charset=UTF-8'}, text: async () => '<mods>a,b</mods>'};
const jsonRes = {headers: {get: () => 'application/json'}, json: async () => ({taskId: 't1', stage: 'need-data'})};
assert((await readChatResponse(textRes)).kind === 'text', 'text/plain → 最终回复');
assert((await readChatResponse(jsonRes)).kind === 'need-data', 'application/json + stage=need-data → 要去取数');
assert(DELEGATION.CONCURRENCY >= 2 && DELEGATION.PER_MINUTE < 300,
    '并发要够（否则 5 秒预算不够用），限速要低于 300 的硬限额');

// ---- 用例 4：抓取失败必须与"上游没有"分开（这条错了会产出缺模组的包） ----
calls = [];
const mixed = await fulfilWanted([
    {kind: 'env_version', key: 'ghost|1.20.1|["forge"]', project: 'ghost', mc: '1.20.1', loader: 'forge'},
    {kind: 'env_version', key: 'offline|1.20.1|["forge"]', project: 'offline', mc: '1.20.1', loader: 'forge'}
]);
assert(mixed.missing.length === 1 && mixed.missing[0].key.startsWith('ghost'),
    '版本列表为空 → missing（上游确实没有）');
assert(mixed.unavailable.length === 1 && mixed.unavailable[0].key.startsWith('offline'),
    '请求抛异常（网络不通）→ unavailable（我没抓到），绝不能混进 missing，'
    + '否则服务器会把"网络不通"读成"这个模组不存在"');

// ---- 用例 5：整批批量请求失败时，那一批全部退回服务器 ----
calls = [];
const savedFetch = globalThis.fetch;
globalThis.fetch = async (url) => {
    calls.push(url);
    throw new Error('network down');
};
const allFailed = await fulfilWanted([{kind: 'project', key: 'create'}, {kind: 'project', key: 'jei'}]);
globalThis.fetch = savedFetch;
assert(allFailed.unavailable.length === 2 && allFailed.missing.length === 0,
    '整批失败时两个 key 都要进 unavailable，不能让服务器以为它们不存在');

// ---- 用例 6：搜索类需求（key 是完整 URL） ----
calls = [];
const searchUrl = 'https://api.modrinth.com/v2/search?limit=30&offset=0&facets=%5B%5D';
const searched = await fulfilWanted([{kind: 'search', key: searchUrl}]);
assert(calls.length === 1 && calls[0] === searchUrl, '搜索需求应按原 URL 直接请求');
assert(searched.answered.length === 1 && Array.isArray(searched.answered[0].data.hits),
    '搜索响应要整份带回（含 hits），服务器才能当正常结果收下');
assert(searched.unavailable.length === 0, '成功的搜索不该进 unavailable');

console.log(process.exitCode ? 'SOME CHECKS FAILED' : 'ALL CHECKS PASSED');
