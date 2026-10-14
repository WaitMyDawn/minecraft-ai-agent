// 取数委派：把 Modrinth 请求交给用户自己的浏览器。
//
// 服务器不再代所有用户发请求（那样必然撞出口 IP 的限额），
// 而是下发"要哪些事实"，由这里去取。配额算在用户自己的出口 IP 上。
//
// 服务端有界分波下发；浏览器回传每波结果。服务端默认等待 120 秒，浏览器
// 单波最多使用 110 秒，预留回传余量；未下发需求不消耗浏览器等待预算。
//
// 自动检查：node eval/fe-delegation-check.mjs

export const DELEGATION = {
    CONCURRENCY: 6,      // 单页面的所有委派共用连接额度
    PER_MINUTE: 240,     // 比 300 的硬限额留 20% 余量，别把自己打到 429
    CHUNK: 100,          // 批量端点实测 100 个/请求
    MAX_ROUNDS: 200      // 防呆：正常一轮构筑的委派轮次远低于这个
};

const delay = (ms, signal) => new Promise((resolve, reject) => {
    signal?.throwIfAborted();
    const abort = () => { clearTimeout(timer); reject(signal.reason); };
    const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
    signal?.addEventListener('abort', abort, {once: true});
});

// 各业务任务共享连接额度，不能每份需求清单各启动六条并行请求。
let activeRequests = 0;
const slotWaiters = [];
const acquireSlot = async (signal) => {
    signal?.throwIfAborted();
    if (activeRequests < DELEGATION.CONCURRENCY) { activeRequests++; return; }
    await new Promise((resolve, reject) => {
        const waiter = {resolve, signal, abort: null};
        waiter.abort = () => {
            const index = slotWaiters.indexOf(waiter);
            if (index >= 0) slotWaiters.splice(index, 1);
            reject(signal.reason);
        };
        slotWaiters.push(waiter);
        signal?.addEventListener('abort', waiter.abort, {once: true});
    });
};
const releaseSlot = () => {
    const waiter = slotWaiters.shift();
    if (waiter) {
        waiter.signal?.removeEventListener('abort', waiter.abort);
        waiter.resolve();
    } else activeRequests--;
};

/** 客户端令牌桶：连续几次构筑时别把自己的出口 IP 打到 429 */
export const makeTokenBucket = (perMinute) => {
    const burst = Math.min(8, perMinute);
    let tokens = burst;
    let last = Date.now();
    return async (signal) => {
        for (; ;) {
            signal?.throwIfAborted();
            const now = Date.now();
            tokens = Math.min(burst, tokens + (now - last) * perMinute / 60000);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return;
            }
            await delay(Math.ceil(60000 / perMinute), signal);
        }
    };
};

// 同一页面的所有回合共用额度，不能在每轮回执时重新获得突发信用。
const browserBucket = makeTokenBucket(DELEGATION.PER_MINUTE);

/** 单次取数：与后端同一套 429 语义（优先用官方重置头，其次指数退避） */
export const fetchModrinth = async (url, bucket, stats, attempt, signal) => {
    const target = new URL(url);
    if (target.protocol !== 'https:' || target.hostname !== 'api.modrinth.com'
            || target.username || target.password || !target.pathname.startsWith('/v2/')) {
        throw new Error('委派请求目标不合法');
    }
    const tries = attempt || 0;
    await bucket(signal);
    // 刻意不设 User-Agent：它属于 Fetch 规范的禁止头，浏览器会静默丢弃，
    // 写上去只会造成"已声明身份"的误解。Modrinth 会看到浏览器自己的 UA。
    await acquireSlot(signal);
    const timeout = AbortSignal.timeout(10_000);
    let res, data;
    const started = Date.now();
    try {
        signal?.throwIfAborted();
        stats.requests++;
        res = await fetch(url, {signal: signal ? AbortSignal.any([signal, timeout]) : timeout});
        if (res.ok) data = await res.json();
        console.info('[Modrinth HTTP]', JSON.stringify({url, status: res.status,
            elapsedMs: Date.now() - started, attempt: tries + 1}));
    } catch (error) {
        console.info('[Modrinth HTTP]', JSON.stringify({url, status: res?.status || 0,
            elapsedMs: Date.now() - started, attempt: tries + 1,
            error: error.name, message: error.message}));
        throw error;
    } finally { releaseSlot(); }
    if (res.status === 429 && tries < 3) {
        stats.rateLimited++;
        const reset = parseInt(res.headers.get('X-Ratelimit-Reset')
            || res.headers.get('Retry-After') || '0', 10);
        const waitMs = reset > 0 ? Math.min(60, reset) * 1000 + 500 : 1000 * Math.pow(2, tries);
        await delay(waitMs, signal);
        return fetchModrinth(url, bucket, stats, tries + 1, signal);
    }
    if (!res.ok) {
        const error = new Error('HTTP ' + res.status);
        error.status = res.status;
        throw error;
    }
    return data;
};

/** 固定并发池：同时最多 limit 个任务在跑 */
export const runPool = async (items, worker, limit) => {
    const out = new Array(items.length);
    let next = 0;
    const runners = Array.from({length: Math.min(limit, items.length)}, async () => {
        for (; ;) {
            const i = next++;
            if (i >= items.length) return;
            out[i] = await worker(items[i]);
        }
    });
    await Promise.all(runners);
    return out;
};

/**
 * 满足一份需求清单。
 *
 * <p>project / version 合并成批量端点（一次 100 个）；env_version 没有批量等价物，
 * 只能并发逐个查。必须区分"上游确实没有"和"我没抓到"，
 * 因为批量端点对无效 id 是静默丢弃的，不做这个区分服务器会把不存在的模组当正常结果收下。
 *
 * <p>回执分三类，不能混：
 *   answered    = 取到了
 *   missing     = 上游明确没有（批量端点静默丢弃 / 版本列表为空）
 *   unavailable = 我没抓到（网络不通、被扩展拦了、整批请求失败）
 * missing 与 unavailable 混在一起是致命的：服务器会把"网络不通"读成
 * "这个模组不存在"，然后产出一个缺模组的包，而服务器兜底也不会触发。
 */
export const fulfilWanted = async (wanted, signal, budgetMs = 110_000) => {
    const budget = AbortSignal.timeout(Math.max(1, Math.min(110_000, budgetMs)));
    signal = signal ? AbortSignal.any([signal, budget]) : budget;
    const stats = {requests: 0, rateLimited: 0};
    const started = Date.now();
    const answered = [];
    const missing = [];
    const unavailable = [];
    const bucket = browserBucket;
    const list = wanted || [];

    // 1) 可批量：合并同类需求，一次请求拿 100 个
    for (const spec of [{kind: 'project', endpoint: 'projects'}, {kind: 'version', endpoint: 'versions'}]) {
        const wants = list.filter(w => w.kind === spec.kind);
        for (let i = 0; i < wants.length; i += DELEGATION.CHUNK) {
            const chunk = wants.slice(i, i + DELEGATION.CHUNK);
            const ids = encodeURIComponent(JSON.stringify(chunk.map(w => w.key)));
            let arr = [];
            let ok = true;
            try {
                const got = await fetchModrinth(
                    `https://api.modrinth.com/v2/${spec.endpoint}?ids=${ids}`, bucket, stats, 0, signal);
                arr = Array.isArray(got) ? got : (got ? [got] : []);
            } catch (e) {
                // 整批失败是"没抓到"，不是"上游没有"——交给服务器兜底
                ok = false;
                console.warn('批量取数失败，整批退回服务器：', spec.kind, e);
            }
            const byKey = new Map();
            for (const item of arr) {
                if (item && item.id) byKey.set(item.id, item);
                if (item && item.slug) byKey.set(item.slug, item);
            }
            for (const w of chunk) {
                const hit = byKey.get(w.key);
                if (hit) answered.push({kind: spec.kind, key: w.key, data: hit});
                else if (ok) missing.push({kind: spec.kind, key: w.key});
                else unavailable.push({kind: spec.kind, key: w.key});
            }
        }
    }

    // 2) 搜索：key 就是完整 URL，直接并发取回整个搜索响应
    const searchWants = list.filter(w => w.kind === 'search');
    if (searchWants.length > 0) {
        const results = await runPool(searchWants, async (w) => {
            try {
                const got = await fetchModrinth(w.key, bucket, stats, 0, signal);
                return (got && Array.isArray(got.hits))
                    ? {found: {kind: 'search', key: w.key, data: got}}
                    : {failed: {kind: 'search', key: w.key}};
            } catch (e) {
                // 抓不到 ≠ 没有结果：搜索"零命中"是合法响应（hits 为空数组），
                // 而请求失败必须让服务器自己重试，混在一起会让服务器误判成"查不到"。
                return {failed: {kind: 'search', key: w.key}};
            }
        }, DELEGATION.CONCURRENCY);
        for (const r of results) {
            if (r.found) answered.push(r.found);
            else unavailable.push(r.failed);
        }
    }

    // 3) 环境版本：无批量端点，并发逐个查
    const envWants = list.filter(w => w.kind === 'env_version' || w.kind === 'env_versions');
    if (envWants.length > 0) {
        const results = await runPool(envWants, async (w) => {
            const gv = encodeURIComponent(JSON.stringify([w.mc]));
            const ld = encodeURIComponent(JSON.stringify([w.loader]));
            const url = `https://api.modrinth.com/v2/project/${encodeURIComponent(w.project)}`
                + `/version?game_versions=${gv}&loaders=${ld}`;
            try {
                const got = await fetchModrinth(url, bucket, stats, 0, signal);
                const arr = Array.isArray(got) ? got : (got ? [got] : []);
                // 拿到了但列表为空 = 上游确实没有这个环境的版本（不是没抓到）
                return arr.length > 0
                    ? {found: {kind: w.kind, key: w.key, data: w.kind === 'env_versions' ? arr.slice(0, 4) : arr[0]}}
                    : {absent: {kind: w.kind, key: w.key}};
            } catch (e) {
                if (e.status === 404) return {absent: {kind: w.kind, key: w.key}};
                return {failed: {kind: w.kind, key: w.key}};
            }
        }, DELEGATION.CONCURRENCY);
        envWants.forEach((w, i) => {
            const r = results[i] || {failed: {kind: w.kind, key: w.key}};
            if (r.found) answered.push(r.found);
            else if (r.absent) missing.push(r.absent);
            else unavailable.push(r.failed);
        });
    }

    return {
        answered, missing, unavailable,
        client: {
            requests: stats.requests, rateLimited: stats.rateLimited,
            elapsedMs: Date.now() - started, via: 'browser'
        }
    };
};

/**
 * 读一次 /api/chat 或 /facts 的响应。
 * 响应自己描述自己：text/plain 就是最终回复，application/json 就是"还要取数"。
 */
export const readChatResponse = async (res) => {
    const ct = (res.headers.get('content-type') || '');
    if (!ct.includes('json')) return {kind: 'text', data: await res.text()};
    const body = await res.json();
    if (body && body.stage === 'need-data') return {kind: 'need-data', data: body};
    return {kind: 'text', data: body && body.error ? ('委派失败：' + body.error) : ''};
};
