import assert from 'node:assert/strict';
import {fulfilWanted} from '../src/main/resources/static/js/modrinth-client.js';

const originalFetch = globalThis.fetch, originalNow = Date.now, originalTimer = globalThis.setTimeout;
let now = originalNow() + 30_000, calls = 0;
Date.now = () => now;
globalThis.setTimeout = (callback, ms) => { now += ms; queueMicrotask(callback); return 0; };
const want = (project, kind = 'env_version', mc = '1.21.1', loader = 'neoforge') =>
    ({kind, key:`${project}|${mc}|["${loader}"]`, project, mc, loader});
const version = id => ({id, project_id:'P', game_versions:['1.21.1'], loaders:['neoforge']});
const success = data => ({ok:true, status:200, json:async () => data});
try {
    globalThis.fetch = async () => { calls++; return success([version('V1'), version('V2')]); };
    const both = await fulfilWanted([want('cost-core'), want('cost-core', 'env_versions')]);
    assert.equal(calls, 1); assert.equal(both.client.coalesced, 1);
    assert.equal(both.answered[0].data.id, 'V1'); assert.equal(both.answered[1].data.length, 2);
    both.answered[0].data.id = 'mutated';
    const cached = await fulfilWanted([want('cost-core')]);
    assert.equal(calls, 1); assert.equal(cached.client.requests, 0); assert.equal(cached.client.cacheHits, 1);
    assert.equal(cached.answered[0].data.id, 'V1');
    now += 15_001;
    await fulfilWanted([want('cost-core')]); assert.equal(calls, 2);
    await fulfilWanted([want('cost-core', 'env_version', '1.20.1')]);
    await fulfilWanted([want('cost-core', 'env_version', '1.21.1', 'fabric')]); assert.equal(calls, 4);

    const aborted = new AbortController(); aborted.abort();
    const cancelled = await fulfilWanted([want('cost-core')], aborted.signal);
    assert.equal(calls, 4); assert.equal(cancelled.unavailable[0].reason, 'CLIENT_ABORTED');

    let round = 0;
    globalThis.fetch = async () => { calls++; return success(round++ ? [version('Fresh')] : []); };
    assert.equal((await fulfilWanted([want('cost-empty')])).missing.length, 1);
    assert.equal((await fulfilWanted([want('cost-empty')])).answered[0].data.id, 'Fresh');
    round = 0;
    globalThis.fetch = async () => { if (!round++) throw new Error('offline'); return success([version('Recovered')]); };
    const failed = await fulfilWanted([want('cost-failure')]);
    assert.equal(failed.missing.length, 0); assert.equal(failed.unavailable[0].reason, 'NETWORK_OR_CORS');
    assert.equal((await fulfilWanted([want('cost-failure')])).answered[0].data.id, 'Recovered');
    globalThis.fetch = async () => success({error:'invalid'});
    const malformed = await fulfilWanted([want('cost-invalid'), {kind:'project', key:'cost-invalid-project'}]);
    assert.equal(malformed.missing.length, 0); assert.equal(malformed.unavailable.length, 2);
    assert.ok(malformed.unavailable.every(r => r.reason === 'INVALID_RESPONSE'));
    globalThis.fetch = async () => { throw Object.assign(new Error('timeout'), {name:'TimeoutError'}); };
    assert.equal((await fulfilWanted([want('cost-timeout')])).unavailable[0].reason, 'REQUEST_TIMEOUT');
    let limitedCalls = 0;
    globalThis.fetch = async () => { limitedCalls++; return {ok:false, status:429, headers:{get:() => null}}; };
    const limited = await fulfilWanted([want('cost-limited')]);
    assert.equal(limitedCalls, 4); assert.equal(limited.client.rateLimited, 4);
    assert.equal(limited.unavailable[0].reason, 'RATE_LIMITED'); assert.equal(limited.missing.length, 0);
    const exhausted = new AbortController(); exhausted.abort(new DOMException('budget', 'TimeoutError'));
    assert.equal((await fulfilWanted([want('cost-budget')], exhausted.signal)).unavailable[0].reason, 'BUDGET_EXHAUSTED');

    let searches = 0;
    globalThis.fetch = async () => { searches++; return success({hits:[], total_hits:0}); };
    const search = {kind:'search', key:'https://api.modrinth.com/v2/search?limit=1&query=cost'};
    await fulfilWanted([search]); await fulfilWanted([search]); assert.equal(searches, 2);

    let largeCalls = 0;
    globalThis.fetch = async () => { largeCalls++; return success([{...version('Large'), changelog:'x'.repeat(2 * 1024 * 1024)}]); };
    await fulfilWanted([want('cost-large')]); await fulfilWanted([want('cost-large')]); assert.equal(largeCalls, 2);

    now += 30_000;
    let entries = 0;
    globalThis.fetch = async () => { entries++; return success([version('Small')]); };
    const firstStoredAt = now;
    for (let i = 0; i < 65; i++) await fulfilWanted([want(`cost-entry-${i}`)]);
    assert.ok(now - firstStoredAt < 15_000);
    await fulfilWanted([want('cost-entry-0')]); assert.equal(entries, 66);
} finally {
    globalThis.fetch = originalFetch; Date.now = originalNow; globalThis.setTimeout = originalTimer;
}

// 各调用只在自己的波次合并在途请求，取消一方不取消另一方。
const module = await import('../src/main/resources/static/js/modrinth-client.js?isolated-cost-cancel');
const a = new AbortController(), b = new AbortController();
const entered = [];
globalThis.fetch = (url, {signal}) => new Promise((resolve, reject) => {
    entered.push(resolve);
    signal.addEventListener('abort', () => reject(signal.reason), {once:true});
});
try {
    const first = module.fulfilWanted([want('cost-independent')], a.signal);
    const second = module.fulfilWanted([want('cost-independent')], b.signal);
    for (let i = 0; i < 20 && entered.length < 2; i++) await new Promise(resolve => setImmediate(resolve));
    assert.equal(entered.length, 2);
    a.abort(); entered[1](success([version('StillActive')]));
    assert.equal((await first).unavailable[0].reason, 'CLIENT_ABORTED');
    assert.equal((await second).answered[0].data.id, 'StillActive');
} finally { a.abort(); b.abort(); globalThis.fetch = originalFetch; }
console.log('Modrinth成本检查通过：波次合并、短缓存、容量与时间边界、失败分类及独立取消。');
