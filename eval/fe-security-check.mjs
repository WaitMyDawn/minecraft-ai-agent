import assert from 'node:assert/strict';
import {makeTokenBucket, fetchModrinth, fulfilWanted} from '../src/main/resources/static/js/modrinth-client.js';

const originalFetch = globalThis.fetch;
let outgoing = 0;
globalThis.fetch = async () => { outgoing++; throw new Error('unexpected fetch'); };
try {
    await assert.rejects(() => fetchModrinth('https://example.com/collect', async () => {}, {requests: 0}, 0));
    await assert.rejects(() => fetchModrinth('https://user:pass@api.modrinth.com/v2/search', async () => {}, {requests: 0}, 0));
    assert.equal(outgoing, 0);
    const controller = new AbortController();
    controller.abort();
    const result = await fulfilWanted([{kind: 'project', key: 'fixture'}], controller.signal);
    assert.equal(outgoing, 0);
    assert.equal(result.unavailable.length, 1);
} finally { globalThis.fetch = originalFetch; }

const realNow = Date.now;
const realTimeout = globalThis.setTimeout;
let now = realNow();
Date.now = () => now;
globalThis.setTimeout = (callback, delay) => { now += delay; queueMicrotask(callback); return 0; };
try {
    const bucket = makeTokenBucket(60);
    const started = now;
    for (let i = 0; i < 8; i++) await bucket();
    assert.equal(now, started);
    for (let i = 0; i < 5; i++) await bucket();
    assert.ok(now - started >= 5000, '持续请求必须消耗同一桶的补充额度');
} finally { Date.now = realNow; globalThis.setTimeout = realTimeout; }
console.log('前端安全检查通过：委派目标限制、取消传播、持续限速。');
