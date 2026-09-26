// 加载器版本表审计：上游有什么 vs maa_db/loader-versions.json 里维护了什么。
// 跑法：node eval/loader-versions-audit.mjs   （需要网络；只读报告，不改文件）
//
// 它解决的问题：这张表过去是"只更新不发现"（遍历表里已有的键），所以新 MC 版本永远进不了表
// （Forge 26.3 就是这么漏的），而且内容只是人工挑的子集。修完之后用这个脚本随时看一眼缺口。
//
// ⚠️ 这里推导 NeoForge 的"版本 → MC 版本"是 **Java 侧规则的一份镜像**
//    （见 LoaderVersionService.mcVersionOfNeoForge）。唯一真相在 Java 里；这个脚本只用于肉眼对账，
//    一旦两边规则不一致，以 Java + 它的单测为准。
import {readFileSync} from 'node:fs';

const UA = {'User-Agent': 'MAA-local-audit/1.0'};
const table = JSON.parse(readFileSync('maa_db/loader-versions.json', 'utf8')).loaders;

const mcOfNeoForge = (v) => {
    const core = v.split('-')[0].split('+')[0];
    const p = core.split('.');
    if (p.length < 2) return null;
    const major = Number(p[0]);
    if (major === 20 || major === 21) {
        const minor = Number(p[1]);
        return minor === 0 ? `1.${major}` : `1.${major}.${minor}`;
    }
    if (major >= 22) {
        const third = p.length > 2 ? Number(p[2]) : 0;
        return third === 0 ? `${major}.${p[1]}` : `${major}.${p[1]}.${third}`;
    }
    return null;
};
const fetchText = async (u) => (await fetch(u, {headers: UA})).text();
const fetchJson = async (u) => (await fetch(u, {headers: UA})).json();

// ---- NeoForge：新 artifact + MC 1.20.1 的旧 artifact ----
const nfXml = await fetchText('https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml');
const legacyXml = await fetchText('https://maven.neoforged.net/releases/net/neoforged/forge/maven-metadata.xml');
const versionsOf = (xml) => [...xml.matchAll(/<version>([^<]+)<\/version>/g)].map(m => m[1]);
const discoveredNf = new Set(versionsOf(nfXml).map(mcOfNeoForge).filter(Boolean));
for (const v of versionsOf(legacyXml)) {
    const i = v.indexOf('-');
    if (i > 0) discoveredNf.add(v.slice(0, i));          // 1.20.1-47.1.106 → 1.20.1
}

// ---- Forge：promotions 的键 ----
const promos = (await fetchJson('https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json')).promos;
const discoveredForge = new Set(Object.keys(promos).map(k => k.replace(/-(latest|recommended)$/, '')));

const report = (name, key, discovered) => {
    const entry = table[key] || {};
    const owned = new Set(Object.keys(entry.gameVersions || {}));
    const missing = [...discovered].filter(m => !owned.has(m)).sort();
    const extra = [...owned].filter(m => !discovered.has(m)).sort();
    const pre = entry.prerelease || {};
    console.log(`\n=== ${name} ===`);
    console.log(`  表里 ${owned.size} 个 / 上游发现 ${discovered.size} 个`);
    console.log(`  上游有、表里没有（${missing.length}）: ${missing.join(', ') || '无'}`);
    console.log(`  表里有、上游推不出（${extra.length}）: ${extra.join(', ') || '无'}`);
    console.log(`  预发布标记: ${Object.entries(pre).map(([k, v]) => k + '=' + v).join(', ') || '无'}`);
    const manual = entry.manual || [];
    if (manual.length) console.log(`  人工钉住（不自动覆盖）: ${manual.join(', ')}`);
};
report('neoforge', 'neoforge', discoveredNf);
report('forge', 'forge', discoveredForge);
console.log('\n（fabric-loader 是 "*" 通配，不参与对账）');
