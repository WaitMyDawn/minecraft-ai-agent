// 前端结构检查。跑法：node eval/frontend-structure-check.mjs
//
// 为什么需要它：index.html 里的脚本已经变成了 ES 模块，而模块加载失败的表现是
// 整页白屏 / 原始 {{ }} 模板裸露——Vue 根本没挂载。这类问题的根因往往在 HTML 结构上，
// 对 .js 文件做 node --check 是看不见的。
//
// 真实事故（已修）：重构时插入了第二个 script type=module 标签，HTML 解析器把第二个
// 当成了第一个的脚本内容，于是模块体第一行是标签而不是代码，整个模块成了非法 JS。
// 当时三个 .js 文件的 node --check 全绿，一点问题都看不出来。
import {readFileSync, existsSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {dirname, resolve} from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const staticDir = resolve(here, '../src/main/resources/static');
const html = readFileSync(resolve(staticDir, 'index.html'), 'utf8');

let failed = false;
const check = (cond, ok, bad) => {
    console.log((cond ? 'ok  : ' : 'FAIL: ') + (cond ? ok : bad));
    if (!cond) failed = true;
};

const MODULE_TAG = new RegExp('<script type="module">', 'g');
const count = (s, re) => (s.match(re) || []).length;

check(count(html, MODULE_TAG) === 1,
    '恰好一个 script type=module 标签',
    '必须有且只有一个 module 标签——重复的那个会被 HTML 解析器当成前一个的脚本内容');

const opens = count(html, new RegExp('<script\\b', 'g'));
const closes = count(html, new RegExp('</script>', 'g'));
check(opens === closes, `script 标签配平（${opens}/${closes}）`,
    `script 标签不配平：开 ${opens} 闭 ${closes}`);

// 按浏览器口径取模块体：第一个 module 开标签到下一个闭标签
const body = new RegExp('<script type="module">([\\s\\S]*?)</script>').exec(html)[1];
const firstLine = body.trimStart().split('\n')[0].trim();
check(!firstLine.startsWith('<'),
    `模块体首行是代码：[${firstLine.slice(0, 60)}]`,
    `模块体首行是标签 "${firstLine}"——说明有嵌套的 script，模块必然加载失败`);

const IMPORT_RE = new RegExp("from\\s+'(\.[^']+)'", 'g');
// 只查相对路径：裸标识符（如 'vue'）由 importmap 解析，不能按文件路径判断
const imports = [...html.matchAll(IMPORT_RE)].map(m => m[1]).filter(s => s.startsWith('.'));
check(imports.length > 0, `发现 ${imports.length} 个本地 import`,
    '没有找到任何本地 import，结构可能已变');

for (const spec of imports) {
    const target = resolve(staticDir, spec.replace(/^\.\//, ''));
    check(existsSync(target), `import 目标存在：${spec}`, `import 目标不存在：${spec}`);
}

// 模块之间的相互 import 也走一遍
for (const spec of imports) {
    const file = resolve(staticDir, spec.replace(/^\.\//, ''));
    if (!existsSync(file)) continue;
    const nested = [...readFileSync(file, 'utf8').matchAll(IMPORT_RE)]
        .map(m => m[1]).filter(s => s.startsWith('.'));
    for (const spec2 of nested) {
        const target = resolve(dirname(file), spec2);
        check(existsSync(target), `${spec} 的子 import 存在：${spec2}`,
            `${spec} 的子 import 不存在：${spec2}`);
    }
}

// ---- 具名 import 与 export 必须对得上 ----
//
// 引入一个对方没导出的名字，会在**模块链接阶段**直接失败 → 整页白屏。
// node --check 只解析语法、看不见这个；路径存在性检查也看不见它。
// 这是白屏的第三个成因，前两个（重复 module 标签、import 路径写错）已经各发生过一次。
const namedImports = (src) => [...src.matchAll(
    new RegExp("import\\s*\\{([^}]*)\\}\\s*from\\s*'(\.[^']+)'", 'g'))]
    .map(m => ({spec: m[2], names: m[1].split(',').map(s => s.trim()).filter(Boolean)}));

const exportedNames = (src) => [...src.matchAll(new RegExp('^export const (\\w+)', 'gm'))]
    .map(m => m[1]);

const linked = new Set();
const checkLinks = (src, label, baseDir) => {
    for (const {spec, names} of namedImports(src)) {
        const target = resolve(baseDir, spec);
        if (!existsSync(target) || linked.has(target)) continue;
        linked.add(target);
        const provided = exportedNames(readFileSync(target, 'utf8'));
        const missing = names.filter(n => !provided.includes(n));
        check(missing.length === 0,
            `${label} → ${spec} 引入的 ${names.length} 个名字都有导出`,
            `${label} → ${spec} 引入了不存在的导出：${missing.join(', ')}（会白屏）`);
        checkLinks(readFileSync(target, 'utf8'), spec, dirname(target));
    }
};
checkLinks(html, 'index.html', staticDir);

console.log(failed ? '\nSOME CHECKS FAILED' : '\nALL CHECKS PASSED');
process.exit(failed ? 1 : 0);
