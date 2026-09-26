// 前端结构检查。跑法：node eval/frontend-structure-check.mjs
//
// 为什么需要它：index.html 里的脚本已经变成了 ES 模块，而模块加载失败的表现是
// 整页白屏 / 原始 {{ }} 模板裸露——Vue 根本没挂载。这类问题的根因往往在 HTML 结构上，
// 对 .js 文件做 node --check 是看不见的。
//
// 真实事故（已修）：重构时插入了第二个 script type=module 标签，HTML 解析器把第二个
// 当成了第一个的脚本内容，于是模块体第一行是标签而不是代码，整个模块成了非法 JS。
// 当时三个 .js 文件的 node --check 全绿，一点问题都看不出来。
import {readFileSync, existsSync, readdirSync} from 'node:fs';
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

// ---- 每个模块：用到的「本地模块导出 / vue API」必须已经 import ----
//
// 这是白屏的第四个成因，也是最难靠肉眼发现的：watch(currentView, ...) ——
// currentView 是**当参数传进去**的，既不是函数调用（没有括号）也不是 .value 的接收者。
// 前三次迭代我分别只扫了「调用位」和「.value 接收者」，每次都有新的使用形态漏掉。
//
// 正确口径是：**不要假设使用形态**，只问「这个标识符是不是某个模块导出的名字」。
// 因为候选集是有限的已知集合（各模块的 export + vue 的 API），噪音天然为零——
// 图片 URL、对象字面量的键、浏览器内建都不会命中这个集合。
const jsRoot = resolve(staticDir, 'js');
const moduleFiles = [];
(function walk(dir) {
    for (const e of readdirSync(dir, {withFileTypes: true})) {
        const p = resolve(dir, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith('.js')) moduleFiles.push(p);
    }
})(jsRoot);

const VUE_API = ['ref', 'reactive', 'computed', 'watch', 'nextTick', 'onMounted', 'onUnmounted',
    'inject', 'provide', 'shallowRef', 'toRef', 'toRefs', 'watchEffect'];

const exportNames = (src) => [...src.matchAll(new RegExp(
    'export\\s+(?:const|let|var|function|class)\\s+(\\w+)', 'g'))].map(m => m[1]);
const importedNames = (src, target) => {
    const out = [];
    for (const m of src.matchAll(new RegExp("import\\s*\\{([^}]*)\\}\\s*from\\s*'([^']+)'", 'g'))) {
        if (target && m[2] !== target) continue;
        out.push(...m[1].split(',').map(s => s.trim()).filter(Boolean));
    }
    for (const m of src.matchAll(new RegExp("import\\s+(\\w+)\\s+from", 'g'))) out.push(m[1]);
    return out;
};

// 所有本地模块的导出合集：一个模块引用了其中任何一个名字，就必须自己 import
const knownExports = new Set(VUE_API);
for (const f of moduleFiles) for (const n of exportNames(readFileSync(f, 'utf8'))) knownExports.add(n);

/**
 * 扫标识符。
 *
 * 反向断言写的是 `(?<![\w.$])`：前面是 `.` 就不算（那是 `obj.prop` 的属性名，不该要求 import）。
 * 但这会把**展开运算符的实参**一起滤掉 —— `{...authHeader()}` 里 authHeader 前面是 `.`，
 * 于是"用了却没 import"永远查不出来。真实事故：delete-flow.js 的 openLab 里
 * `{...packData, headers: {...authHeader()}}` 漏了 authHeader 的 import，本检查当时是绿的，
 * 一进装配台就会 ReferenceError。所以先把 `...` 换成空格再扫。
 */
const identifiers = (src) => [...src.replace(/\.\.\./g, ' ').matchAll(
    new RegExp('(?<![\\w.$])([A-Za-z_$][\\w$]*)', 'g'))].map(m => m[1]);
const declaredNames = (src) => [...src.matchAll(new RegExp(
    '(?:const|let|var|function|class)\\s+(\\w+)', 'g'))].map(m => m[1]);

/**
 * 剥掉注释再扫描。
 *
 * 不剥的话注释里的词会被当成代码——TreeNode.js 的注释里写了「8 个 provide/inject key」，
 * provide 正好在 vue 的 API 名单里，于是被报成"用了却没 import"。这是检查器自己的 bug。
 *
 * 行注释只在该行 `//` 前面不是 `:` 时才剥，否则会把 `https://...` 后面的代码一起吃掉。
 */
const stripComments = (src) => src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(new RegExp('(^|[^:])\\/\\/[^\\n]*', 'g'), '$1');

const checkModuleScope = (src, label) => {
    const code = stripComments(src);
    const imported = new Set(importedNames(code));
    const declared = new Set(declaredNames(code));
    const missing = [...new Set(identifiers(code))]
        .filter(n => knownExports.has(n) && !imported.has(n) && !declared.has(n));
    check(missing.length === 0,
        `${label} 用到的模块导出都已 import（${imported.size} 个 import）`,
        `${label} 用了却没 import：${missing.join(', ')} —— 会在运行时 ReferenceError，页面白屏`);
};

for (const f of moduleFiles) checkModuleScope(readFileSync(f, 'utf8'), f.replace(staticDir, ''));
checkModuleScope(body, 'index.html（模块体）');

// ---- 跨模块的「私有名」引用 ----
//
// 搬块时最容易漏的一类：函数被搬进新模块但忘了 export，而 index.html 还在引用它。
// 真实事故：delete-flow.js 的 confirmBatchDelete 没 export，index.html 的 return 里还留着它，
// setup() 直接 ReferenceError；更坑的是 Vue 的 handleError 把 setup 的异常吞掉（只 console.error），
// 异常不逃到 window —— 红条守卫一声不响，页面只剩页脚，表现为"没有报错的白屏"。
// 上面那圈检查只认识"别的模块 export 过的名字"，对这类私有名完全瞎。
const privateTopLevel = new Map();     // 模块顶层声明但没 export 的名字 -> 所在文件
for (const f of moduleFiles) {
    const src = readFileSync(f, 'utf8');
    const exported = new Set(exportNames(src));
    for (const m of src.matchAll(new RegExp('^(?:const|let|var|function|class)\\s+(\\w+)', 'gm'))) {
        if (!exported.has(m[1])) privateTopLevel.set(m[1], f);
    }
}
/** 本文件里"算作有定义"的名字：声明 + 函数参数 + 解构绑定 */
const localNames = (src) => {
    const out = new Set(declaredNames(src));
    for (const re of [/\(([^()]*)\)\s*=>/g, /function\s*(?:\w+)?\s*\(([^()]*)\)/g,
                      /catch\s*\(([^()]*)\)/g, /([A-Za-z_$][\w$]*)\s*=>/g]) {
        for (const m of src.matchAll(re)) {
            for (const p of m[1].split(',')) {
                const n = p.split('=')[0].trim();
                if (/^[A-Za-z_$][\w$]*$/.test(n)) out.add(n);
            }
        }
    }
    for (const m of src.matchAll(new RegExp('(?:const|let|var)\\s*[{\\[]([^}\\]]*)[}\\]]\\s*=', 'g'))) {
        for (const p of m[1].split(',')) {
            const n = p.split(':').pop().split('=')[0].trim();
            if (/^[A-Za-z_$][\w$]*$/.test(n)) out.add(n);
        }
    }
    return out;
};
const checkNoForeignPrivate = (src, label, self) => {
    const code = stripComments(src);
    const imported = new Set(importedNames(code));
    const local = localNames(code);
    const bad = [...new Set(identifiers(code))].filter(n =>
        privateTopLevel.has(n) && privateTopLevel.get(n) !== self && !imported.has(n) && !local.has(n));
    check(bad.length === 0,
        `${label} 没有引用别的模块的私有名`,
        `${label} 引用了别的模块没 export 的名字：`
            + bad.map(n => n + '（' + privateTopLevel.get(n).replace(staticDir, '') + '）').join('、')
            + ' —— 会 ReferenceError；Vue 还会吞掉这个异常，表现成"没有红条的白屏"');
};
checkNoForeignPrivate(body, 'index.html（模块体）', null);
for (const f of moduleFiles) checkNoForeignPrivate(readFileSync(f, 'utf8'), f.replace(staticDir, ''), f);

// ---- 模板引用的名字必须在 setup 的 return 里 ----
//
// "模板留在 HTML 里"这个方案的固有风险：模板写了 @click="foo" 或 {{ bar }}，而 return 里没有这个名字，
// Vue **只 console.warn**，页面静默少一块 —— 既不报红条，静态检查也看不见（前三道门都不查模板↔return）。
// 口径：模板表达式里的标识符 − 模板局部作用域(v-for 变量) − Vue 内建 − JS 字面量 ⊆ return 的键。
//
// 两个模板区的作用域不同，分别校验：
//   1) #app 里的视图模板 → 作用域是 index.html 的 setup return
//   2) x-template 里的 tree-node 模板 → 作用域是 TreeNode.js 的 props + 它 setup 的 return
const stripStringsAndKeys = (s) => s
    .replace(/'[^'\n]*'/g, ' ').replace(/"[^"\n]*"/g, ' ').replace(/`[^`\n]*`/g, ' ')
    .replace(new RegExp('(^|[{,\\s(])([A-Za-z_$][\\w$]*)\\s*:', 'g'), '$1');
const templateExprs = (tpl) => {
    const out = [];
    for (const m of tpl.matchAll(/\{\{([\s\S]*?)\}\}/g)) out.push(m[1]);
    for (const m of tpl.matchAll(/(?:@|:|v-)[\w:.-]*\s*=\s*"([^"]*)"/g)) out.push(m[1]);
    return out;
};
const templateLocals = (tpl) => {
    const locals = new Set(['$refs', '$event', '$emit', '$attrs', '$slots', '$props', '$index', '$key', '$value',
        'true', 'false', 'null', 'undefined', 'in', 'of', 'typeof', 'new', 'return', 'if', 'else', 'this',
        'window', 'document', 'console', 'Math', 'JSON', 'Object', 'Array', 'Number', 'String', 'Boolean',
        'Set', 'Map', 'Date', 'parseInt', 'parseFloat', 'isNaN', 'alert', 'confirm']);
    for (const m of tpl.matchAll(/v-for="\s*\(([^)]*)\)/g)) {
        for (const n of m[1].split(',')) if (n.trim()) locals.add(n.trim());
    }
    for (const m of tpl.matchAll(/v-for="\s*([A-Za-z_$][\w$]*)\s+(?:in|of)\s/g)) locals.add(m[1]);
    for (const m of tpl.matchAll(/(?:v-slot(?::[\w-]+)?|#[\w-]+)\s*=\s*"([^"]*)"/g)) {
        if (/^[A-Za-z_$][\w$]*$/.test(m[1].trim())) locals.add(m[1].trim());
    }
    return locals;
};
/**
 * 取某个 setup 里 `return { ... }` 的键。
 * 必须用括号配对找收尾，不能拿 lastIndexOf('}') —— 那会越过 return 对象吃到组件对象/函数的大括号，
 * 结果把最后一个键（TreeNode 的 isConflict）整段丢掉，检查器自己就会误报。
 */
const returnKeys = (src) => {
    const code = stripComments(src).replace(new RegExp('//[^\n]*', 'g'), ' ');
    const i = code.lastIndexOf('return {');
    if (i < 0) return new Set();
    let depth = 0, end = -1;
    for (let p = code.indexOf('{', i); p < code.length; p++) {
        if (code[p] === '{') depth++;
        else if (code[p] === '}' && --depth === 0) { end = p; break; }
    }
    if (end < 0) return new Set();
    const body = code.slice(code.indexOf('{', i) + 1, end);
    return new Set(body.split(',').map(s => s.trim()).filter(s => /^[A-Za-z_$][\w$]*$/.test(s)));
};
const checkTemplateScope = (tpl, label, allowed) => {
    const locals = templateLocals(tpl);
    const ids = new Set();
    for (const e of templateExprs(tpl)) {
        for (const n of identifiers(stripStringsAndKeys(e))) {
            if (!locals.has(n) && !allowed.has(n)) ids.add(n);
        }
    }
    check(ids.size === 0, `${label} 引用的名字都在作用域里`,
        `${label} 引用了作用域里没有的名字：${[...ids].join(', ')} —— Vue 只 console.warn，页面会静默少一块`);
};
// #app 的视图模板：到 x-template 开始为止（再往后就是 tree-node 的模板，作用域是另一个）
const appTemplate = html.slice(html.indexOf('<div id="app"'), html.indexOf('<script type="text/x-template"'));
const nodeTemplate = (new RegExp('<script type="text/x-template"[^>]*>([\\s\\S]*?)</script>').exec(html) || [, ''])[1];
const nodeSrc = readFileSync(resolve(staticDir, 'js/components/TreeNode.js'), 'utf8');
const nodeProps = (new RegExp("props:\\s*\\[([^\\]]*)\\]").exec(nodeSrc) || [, ''])[1]
    .split(',').map(s => s.trim().replace(/['"]/g, '')).filter(Boolean);
checkTemplateScope(appTemplate, 'index.html（#app 模板）', returnKeys(body));
checkTemplateScope(nodeTemplate, 'tree-node 模板',
    new Set([...nodeProps, ...returnKeys(nodeSrc)]));

// ---- provide / inject 契约 ----
//
// inject 拿不到 key 时【不会当场报错】，只是拿到 undefined；TreeNode 里立刻用在
// computed 的 .has() 上，于是变成"点勾选框才炸"，而且栈里看不出是契约断了。
// 模块化之后 provide 从 setup 搬进了 provideDeleteContract()，这条最容易悄悄断，单独查。
const provideRe = new RegExp("provide\\('(\\w+)'", 'g');
const injectRe = new RegExp("inject\\('(\\w+)'", 'g');
const provided = new Set();
for (const f of [resolve(staticDir, 'index.html'), ...moduleFiles]) {
    for (const m of readFileSync(f, 'utf8').matchAll(provideRe)) provided.add(m[1]);
}
const injected = [];
for (const f of [resolve(staticDir, 'index.html'), ...moduleFiles]) {
    for (const m of readFileSync(f, 'utf8').matchAll(injectRe)) injected.push({key: m[1], file: f.replace(staticDir, '')});
}
const orphan = injected.filter(i => !provided.has(i.key));
check(orphan.length === 0,
    `每个 inject 都有对应的 provide（provide ${provided.size} 个 / inject ${injected.length} 个）`,
    '这些 inject 找不到 provide：' + orphan.map(o => o.key + ' @ ' + o.file).join(', ')
        + ' —— 契约断了不会当场报错，要等用户点勾选框才炸');

console.log(failed ? '\nSOME CHECKS FAILED' : '\nALL CHECKS PASSED');
process.exit(failed ? 1 : 0);
