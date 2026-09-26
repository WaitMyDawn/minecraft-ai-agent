// 取数委派：把 Modrinth 请求交给用户自己的浏览器。
//
// 服务器不再代所有用户发请求（那样必然撞 300 次/分钟/出口 IP 的限额），
// 而是下发"要哪些事实"，由这里去取。配额算在用户自己的出口 IP 上。
//
// 并发和限速不是优化项，是必需品：单次委派只有 5 秒预算，串行取数会让
// 一批 70 个事实花掉十几秒，服务器只好兜底自己抓——那就白做了。
//
// 自动检查：node eval/fe-delegation-check.mjs

// ==========================================
// 🚀 取数委派：把 Modrinth 请求交给用户自己的浏览器
//
// 服务器不再代所有用户发请求（那样必然撞 300 次/分钟/出口 IP 的限额），
// 而是下发"要哪些事实"，由这里去取。配额算在用户自己的出口 IP 上。
//
// 并发和限速不是优化项，是必需品：单次委派只有 5 秒预算，串行取数会让
// 一批 70 个事实花掉十几秒，服务器只好兜底自己抓——那就白做了。
// ==========================================
export const DELEGATION = {
    CONCURRENCY: 6,      // 浏览器单域名本来也就 ~6 条连接
    PER_MINUTE: 240,     // 比 300 的硬限额留 20% 余量，别把自己打到 429
    CHUNK: 100,          // 批量端点实测 100 个/请求
    MAX_ROUNDS: 200      // 防呆：正常一轮构筑的委派轮次远低于这个
};

/** 客户端令牌桶：连续几次构筑时别把自己的出口 IP 打到 429 */
export const makeTokenBucket = (perMinute) => {
    let tokens = perMinute;
    let last = Date.now();
    return async () => {
        for (; ;) {
            const now = Date.now();
            tokens = Math.min(perMinute, tokens + (now - last) * perMinute / 60000);
            last = now;
            if (tokens >= 1) {
                tokens -= 1;
                return;
            }
            await new Promise(r => setTimeout(r, Math.ceil(60000 / perMinute)));
        }
    };
};

/** 单次取数：与后端同一套 429 语义（优先用官方重置头，其次指数退避） */
export const fetchModrinth = async (url, bucket, stats, attempt) => {
    const tries = attempt || 0;
    await bucket();
    stats.requests++;
    // 刻意不设 User-Agent：它属于 Fetch 规范的禁止头，浏览器会静默丢弃，
    // 写上去只会让人误以为我们报了身份。Modrinth 会看到浏览器自己的 UA。
    const res = await fetch(url);
    if (res.status === 429 && tries < 3) {
        stats.rateLimited++;
        const reset = parseInt(res.headers.get('X-Ratelimit-Reset')
            || res.headers.get('Retry-After') || '0', 10);
        const waitMs = reset > 0 ? reset * 1000 + 500 : 1000 * Math.pow(2, tries);
        await new Promise(r => setTimeout(r, waitMs));
        return fetchModrinth(url, bucket, stats, tries + 1);
    }
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res.json();
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
 * 只能并发逐个查。取不到的一律记进 missing —— 必须区分"上游确实没有"和"我没抓到"，
 * 因为批量端点对无效 id 是静默丢弃的，不做这个区分服务器会把不存在的模组当正常结果收下。
 *
 * <p>回执分三类，不能混：
 *   answered    = 取到了
 *   missing     = 上游明确没有（批量端点静默丢弃 / 版本列表为空）
 *   unavailable = 我没抓到（网络不通、被扩展拦了、整批请求失败）
 * missing 与 unavailable 混在一起是致命的：服务器会把"网络不通"读成
 * "这个模组不存在"，然后产出一个缺模组的包，而你要的服务器兜底也不会触发。
 */
export const fulfilWanted = async (wanted) => {
    const stats = {requests: 0, rateLimited: 0};
    const started = Date.now();
    const answered = [];
    const missing = [];
    const unavailable = [];
    const bucket = makeTokenBucket(DELEGATION.PER_MINUTE);
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
                    `https://api.modrinth.com/v2/${spec.endpoint}?ids=${ids}`, bucket, stats, 0);
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
                const got = await fetchModrinth(w.key, bucket, stats, 0);
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
    const envWants = list.filter(w => w.kind === 'env_version');
    if (envWants.length > 0) {
        const results = await runPool(envWants, async (w) => {
            const gv = encodeURIComponent(JSON.stringify([w.mc]));
            const ld = encodeURIComponent(JSON.stringify([w.loader]));
            const url = `https://api.modrinth.com/v2/project/${encodeURIComponent(w.project)}`
                + `/version?game_versions=${gv}&loaders=${ld}`;
            try {
                const got = await fetchModrinth(url, bucket, stats, 0);
                const arr = Array.isArray(got) ? got : (got ? [got] : []);
                // 拿到了但列表为空 = 上游确实没有这个环境的版本（不是没抓到）
                return arr.length > 0
                    ? {found: {kind: 'env_version', key: w.key, data: arr[0]}}
                    : {absent: {kind: 'env_version', key: w.key}};
            } catch (e) {
                return {failed: {kind: 'env_version', key: w.key}};
            }
        }, DELEGATION.CONCURRENCY);
        envWants.forEach((w, i) => {
            const r = results[i] || {failed: {kind: 'env_version', key: w.key}};
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
