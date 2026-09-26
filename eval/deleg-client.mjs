// 委派协议的参考客户端（Node 版）：并发取数 + 令牌桶 + 429 退避。
// 用途是验证"单个请求 5 秒预算"够不够——之前那版 PowerShell 探针是串行的，
// 48 个事实串着取要 7 秒，排在后面的自然超时，会让人误以为预算太短。
import {readFileSync} from 'node:fs';

const BASE = 'http://localhost:8080';
const CONCURRENCY = 6;
const PER_MINUTE = 240;
const CHUNK = 100;

const scenario = JSON.parse(readFileSync(new URL('./deleg-scenario.json', import.meta.url), 'utf8'));

let clientRequests = 0;
let rateLimited = 0;
let tokens = PER_MINUTE;
let lastRefill = Date.now();

const bucket = async () => {
    for (; ;) {
        const now = Date.now();
        tokens = Math.min(PER_MINUTE, tokens + (now - lastRefill) * PER_MINUTE / 60000);
        lastRefill = now;
        if (tokens >= 1) {
            tokens -= 1;
            return;
        }
        await new Promise(r => setTimeout(r, 10));
    }
};

const mfetch = async (url, attempt = 0) => {
    await bucket();
    clientRequests++;
    const r = await fetch(url, {headers: {'User-Agent': 'MAA-NodeClient/1.0'}});
    if (r.status === 429 && attempt < 3) {
        rateLimited++;
        const reset = parseInt(r.headers.get('x-ratelimit-reset') || '0', 10);
        await new Promise(res => setTimeout(res, reset > 0 ? reset * 1000 + 500 : 1000 * 2 ** attempt));
        return mfetch(url, attempt + 1);
    }
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return r.json();
};

const pool = async (items, worker, limit) => {
    const out = new Array(items.length);
    let next = 0;
    await Promise.all(Array.from({length: Math.min(limit, items.length)}, async () => {
        for (; ;) {
            const i = next++;
            if (i >= items.length) return;
            out[i] = await worker(items[i]);
        }
    }));
    return out;
};

const post = async (path, body) => {
    const r = await fetch(BASE + path, {
        method: 'POST',
        headers: {'Content-Type': 'application/json; charset=utf-8'},
        body: JSON.stringify(body)
    });
    return {ct: r.headers.get('content-type') || '', text: await r.text()};
};

const fulfil = async (wanted) => {
    const answered = [];
    const missing = [];
    const unavailable = [];

    for (const [kind, endpoint] of [['project', 'projects'], ['version', 'versions']]) {
        const wants = wanted.filter(w => w.kind === kind);
        for (let i = 0; i < wants.length; i += CHUNK) {
            const chunk = wants.slice(i, i + CHUNK);
            let arr = [];
            let ok = true;
            try {
                const ids = encodeURIComponent(JSON.stringify(chunk.map(w => w.key)));
                const got = await mfetch(`https://api.modrinth.com/v2/${endpoint}?ids=${ids}`);
                arr = Array.isArray(got) ? got : (got ? [got] : []);
            } catch (e) {
                ok = false;
            }
            const byKey = new Map();
            for (const it of arr) {
                if (it && it.id) byKey.set(it.id, it);
                if (it && it.slug) byKey.set(it.slug, it);
            }
            for (const w of chunk) {
                const hit = byKey.get(w.key);
                if (hit) answered.push({kind, key: w.key, data: hit});
                else if (ok) missing.push({kind, key: w.key});
                else unavailable.push({kind, key: w.key});
            }
        }
    }

    const envWants = wanted.filter(w => w.kind === 'env_version');
    const results = await pool(envWants, async (w) => {
        try {
            const gv = encodeURIComponent(JSON.stringify([w.mc]));
            const ld = encodeURIComponent(JSON.stringify([w.loader]));
            const got = await mfetch(`https://api.modrinth.com/v2/project/${encodeURIComponent(w.project)}`
                + `/version?game_versions=${gv}&loaders=${ld}`);
            const arr = Array.isArray(got) ? got : (got ? [got] : []);
            return arr.length > 0
                ? {found: {kind: 'env_version', key: w.key, data: arr[0]}}
                : {absent: {kind: 'env_version', key: w.key}};
        } catch (e) {
            return {failed: {kind: 'env_version', key: w.key}};
        }
    }, CONCURRENCY);
    for (const r of results) {
        if (r.found) answered.push(r.found);
        else if (r.absent) missing.push(r.absent);
        else unavailable.push(r.failed);
    }

    return {answered, missing, unavailable};
};

const started = Date.now();
let res = await post('/api/chat', scenario);
let round = 0;

while (res.ct.includes('json')) {
    const need = JSON.parse(res.text);
    if (need.stage !== 'need-data') {
        console.log('UNEXPECTED stage=' + need.stage);
        break;
    }
    round++;
    const t0 = Date.now();
    const wanted = need.wanted || [];
    const facts = await fulfil(wanted);
    const kinds = {};
    for (const w of wanted) kinds[w.kind] = (kinds[w.kind] || 0) + 1;
    console.log(`  round ${round}: wanted=${wanted.length} `
        + `(${Object.entries(kinds).map(([k, v]) => k + ':' + v).join(' ')}) `
        + `answered=${facts.answered.length} missing=${facts.missing.length} `
        + `unavailable=${facts.unavailable.length} took=${Date.now() - t0}ms clientReq=${clientRequests}`);
    res = await post(`/api/chat/task/${encodeURIComponent(need.taskId)}/facts`, {
        ...facts,
        client: {requests: clientRequests, rateLimited, via: 'node-client'}
    });
}

const trace = /<trace>([\s\S]*?)<\/trace>/.exec(res.text);
if (trace) {
    const up = /"upstream":\{[^}]*\}/.exec(trace[1]);
    console.log('server ' + (up ? up[0] : 'upstream not found'));
}
const mods = /<mods>([\s\S]*?)<\/mods>/.exec(res.text);
if (mods) console.log('final mod count = ' + mods[1].split(',').filter(Boolean).length);
console.log(`DONE rounds=${round} clientUpstreamRequests=${clientRequests} rateLimited=${rateLimited} `
    + `wall=${((Date.now() - started) / 1000).toFixed(1)}s`);
