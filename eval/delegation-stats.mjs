// 委派效果统计（只报告，不作为门；不进 mvn test）。
// 跑法：node eval/delegation-stats.mjs
//
// 为什么用 Node 自己 walk，而不是 `rg logs`：
//   `.gitignore` 里有 `logs/`，而 `rg <pattern> logs` 对"显式给出的被忽略目录"会**静默跳过** ——
//   返回空、退出码也不显眼。我（AI）就因为这个把"工具没搜"误判成"日志里没有委派"，
//   凭空得出过"一次委派都没跑过"的错误结论。自己 walk 目录就没这个坑。
//
// 指标含义（对应 ChatController.logSettlement 那一行）：
//   下放 X 项      = 这一轮构筑里，服务器下发给浏览器去取的项次（按轮累加）
//   用户完成 Y 项   = 浏览器真带回结果的 key 数（与 X 不是同一口径，别相减当丢失量）
//   自抓 Z 项      = 用户没赶上、退回服务器自己抓的次数 ← **预算够不够就看这个**
//   服务器出网 H 次 = 服务器本轮真实发出的 Modrinth 请求数（委派生效时应当接近 0）
//   429 / 令牌闸排队 = 限流健康度
import {readFileSync, readdirSync, existsSync} from 'node:fs';
import {join} from 'node:path';

const ROOT = 'logs';
const SETTLE_RE = /\[([\d-]+ [\d:.]+)\]\s*📊 委派结算 \[任务 (\w+)\] 共 (\d+) 轮：下放 (\d+) 项 \/ 用户完成 (\d+) 项 \/ 用户没赶上、服务器自抓 (\d+) 项；服务器本轮真实发出请求 (\d+) 次（429 (\d+) 次，令牌闸排队 (\d+)ms）/;

if (!existsSync(ROOT)) {
    console.log('没有 logs/ 目录，跳过');
    process.exit(0);
}

const rows = [];
(function walk(dir) {
    for (const e of readdirSync(dir, {withFileTypes: true})) {
        const p = join(dir, e.name);
        if (e.isDirectory()) { walk(p); continue; }
        if (!e.name.endsWith('.log')) continue;
        readFileSync(p, 'utf8').split('\n').forEach((line) => {
            const m = SETTLE_RE.exec(line);
            if (!m) return;
            rows.push({
                at: m[1], task: m[2], rounds: +m[3],
                dispatched: +m[4], served: +m[5], selfFetch: +m[6],
                http: +m[7], rateLimited: +m[8], throttleMs: +m[9],
                user: e.name.replace(/\.log$/, ''), file: p,
            });
        });
    }
})(ROOT);

if (rows.length === 0) {
    console.log('logs/ 里没有「📊 委派结算」记录（还没跑过带委派的构筑？）');
    process.exit(0);
}
rows.sort((a, b) => a.at.localeCompare(b.at));

// 中文是双宽字符，按"显示宽度"补齐才对齐（padEnd 按字符数算会错位）
const width = (s) => [...String(s)].reduce((a, c) => a + (/[\u1100-\uFFE6]/.test(c) ? 2 : 1), 0);
const pad = (s, n) => String(s) + ' '.repeat(Math.max(0, n - width(s)));
const HEAD = ['时间', '用户', '轮', '下放', '完成', '自抓', '出网', '429', '排队ms'];
const W = [24, 24, 5, 7, 7, 7, 7, 6, 8];
console.log(HEAD.map((h, i) => pad(h, W[i])).join(''));
console.log('-'.repeat(W.reduce((a, b) => a + b, 0)));
for (const r of rows) {
    console.log([
        pad(r.at, W[0]), pad(r.user, W[1]), pad(r.rounds, W[2]), pad(r.dispatched, W[3]),
        pad(r.served, W[4]), pad(r.selfFetch, W[5]), pad(r.http, W[6]), pad(r.rateLimited, W[7]), r.throttleMs,
    ].join(''));
}
const sum = (k) => rows.reduce((a, r) => a + r[k], 0);
const selfFetch = sum('selfFetch');
console.log('-'.repeat(W.reduce((a, b) => a + b, 0)));
console.log(`合计 ${rows.length} 次委派构筑：下放 ${sum('dispatched')} / 完成 ${sum('served')} / 自抓 ${selfFetch}`
    + ` / 服务器出网 ${sum('http')} 次 / 429 ${sum('rateLimited')} 次`);
console.log(`自抓率 ${(selfFetch / Math.max(1, sum('dispatched')) * 100).toFixed(1)}%`
    + `（0 说明单轮预算够用；长期 >0 才值得改预算策略）`);
const worst = rows.filter(r => r.selfFetch > 0);
if (worst.length) {
    console.log('打穿过预算的运行：');
    for (const r of worst) console.log('  ' + r.at + '  ' + r.user + '：自抓 ' + r.selfFetch + ' 项（下放 ' + r.dispatched + '）');
}

// 按天汇总：样本会越攒越多，趋势看这里；不要手工维护某个"基线文件"里的数字。
const byDay = new Map();
for (const r of rows) {
    const day = r.at.slice(0, 10);
    const d = byDay.get(day) || {n: 0, dispatched: 0, served: 0, selfFetch: 0, http: 0, rateLimited: 0};
    d.n++; d.dispatched += r.dispatched; d.served += r.served;
    d.selfFetch += r.selfFetch; d.http += r.http; d.rateLimited += r.rateLimited;
    byDay.set(day, d);
}
console.log('\n按天汇总：');
console.log(pad('日期', 14) + pad('次数', 6) + pad('下放', 8) + pad('完成', 8) + pad('自抓', 8) + pad('自抓率', 10) + '429');
for (const [day, d] of [...byDay].sort()) {
    const rate = (d.selfFetch / Math.max(1, d.dispatched) * 100).toFixed(1) + '%';
    console.log(pad(day, 14) + pad(d.n, 6) + pad(d.dispatched, 8) + pad(d.served, 8)
        + pad(d.selfFetch, 8) + pad(rate, 10) + d.rateLimited);
}
