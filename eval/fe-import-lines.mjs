// 前端 import 行核对。跑法：node eval/fe-import-lines.mjs [--write]
//
// 为什么需要它：每搬一块，收尾都要重算"index.html 还需要哪些模块导出"。这件事我手算过三次，
// 三次都翻车 —— 一次自证式地把 import 行本身算进使用集（留下 5 个没人用的名字），
// 一次漏了 confirmBatchDelete（它没 export，index.html 还在用 → setup ReferenceError → 白屏）。
//
// 口径（与 frontend-structure-check.mjs 一致）：
//   某文件里【真正出现的标识符】∩【目标模块的导出】= 它必须 import 的名字。
//   - 少 import → 运行时 ReferenceError（Vue 会把它吞掉，表现成"没有红条的白屏"）
//   - 多 import → 只是噪音，报 WARN，不算失败
//   算之前必须先把该文件自己的 import 行剥掉：否则老 import 里的名字会自证"还在用"。
//
// 默认只读；`--write` 才按上面的口径重写【有漂移】的那几条 import（没漂移的保持字节不变，不制造 diff）。
import {readFileSync, writeFileSync, readdirSync, existsSync} from 'node:fs';
import {resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const staticDir = resolve(here, '../src/main/resources/static');
const write = process.argv.includes('--write');

let failed = false;
const check = (cond, ok, bad) => {
    console.log((cond ? 'ok  : ' : 'FAIL: ') + (cond ? ok : bad));
    if (!cond) failed = true;
};

const jsFiles = [];
(function walk(dir) {
    for (const e of readdirSync(dir, {withFileTypes: true})) {
        const p = resolve(dir, e.name);
        if (e.isDirectory()) walk(p);
        else if (e.name.endsWith('.js')) jsFiles.push(p);
    }
})(resolve(staticDir, 'js'));

const exportRe = new RegExp('export\\s+(?:const|let|var|function|class)\\s+(\\w+)', 'g');
const exportsOf = (src) => [...src.matchAll(exportRe)].map(m => m[1]);

const stripComments = (src) => src
    .replace(/\/\*[\s\S]*?\*\//g, ' ')
    .replace(new RegExp('(^|[^:])\\/\\/[^\\n]*', 'g'), '$1');
// 反向断言 (?<![\w.$]) 会把展开运算符的实参 (`{...authHeader()}`) 滤掉 —— 先把 `...` 换成空格
const identifiers = (src) => [...src.replace(/\.\.\./g, ' ').matchAll(
    new RegExp('(?<![\\w.$])([A-Za-z_$][\\w$]*)', 'g'))].map(m => m[1]);

const IMPORT_RE = new RegExp("import\\s*\\{([^}]*)\\}\\s*from\\s*'([^']+)';", 'g');

/**
 * 最小改动重排：只做"删掉多余的名字 / 追加缺的名字"，保留原有的分行与缩进。
 * （第一版是整个列表重新排序+折行，改动看着像重写了一遍 import 块 —— 没必要，diff 越干净越好。）
 */
const editStatement = (text, names, needed) => {
    const extras = new Set(names.filter(n => !needed.includes(n)));
    const missing = needed.filter(n => !names.includes(n));
    const arr = text.split('\n');
    const indent = (text.match(/^[ \t]*/) || [''])[0];

    if (arr.length === 1) {                                       // 单行：直接重排
        const spec = /from\s*'([^']+)'/.exec(text)[1];
        const body = text.replace(/^[ \t]*import\s*\{\s*/, '').replace(/\s*\}\s*from\s*'[^']+';\s*$/, '');
        const kept = body.split(',').map(s => s.trim()).filter(s => s && !extras.has(s));
        return indent + 'import {' + kept.concat(missing).sort().join(', ') + "} from '" + spec + "';";
    }

    const out = [];
    for (let i = 0; i < arr.length; i++) {
        if (i === 0 || i === arr.length - 1) { out.push(arr[i]); continue; }   // `import {` / `} from ...;`
        const lIndent = (arr[i].match(/^[ \t]*/) || [''])[0];
        const trailingComma = /,\s*$/.test(arr[i]);
        const kept = arr[i].trim().replace(/,$/, '').split(',').map(s => s.trim())
            .filter(s => s && !extras.has(s));
        if (kept.length > 0) out.push(lIndent + kept.join(', ') + (trailingComma ? ',' : ''));
    }
    if (missing.length > 0 && out.length >= 2) {
        out[out.length - 2] = out[out.length - 2].replace(/,\s*$/, '')
            + ', ' + missing.slice().sort().join(', ') + ',';
    }
    return out.join('\n');
};

const targets = [resolve(staticDir, 'index.html'), ...jsFiles];

/**
 * 扫一遍所有文件的具名 import。write=true 时顺手修掉有漂移的那几条。
 * 返回 {checked, missing, extra}；missing 才是致命的（会 ReferenceError）。
 */
const scanAll = (write) => {
    let checked = 0;
    const missingHits = [], extraHits = [];
    for (const file of targets) {
        const src = readFileSync(file, 'utf8');
        const imports = [...src.matchAll(IMPORT_RE)];
        if (imports.length === 0) continue;

        // 剥掉所有具名 import 之后再算"用到了什么"（否则老 import 里的名字会自证还在用）
        let body = src;
        for (const m of imports) body = body.replace(m[0], ' ');
        const used = new Set(identifiers(stripComments(body)));

        let next = src;
        const label = file.replace(staticDir, '') || 'index.html';
        for (const m of imports) {
            const names = m[1].split(',').map(s => s.trim()).filter(Boolean);
            const spec = m[2];
            if (!spec.startsWith('.')) continue;                  // 裸标识符（vue）交给 importmap
            const target = resolve(dirname(file), spec);
            if (!existsSync(target)) continue;                     // 路径不存在由结构检查报
            const provided = new Set(exportsOf(readFileSync(target, 'utf8')));
            const needed = [...provided].filter(n => used.has(n)).sort();
            const missing = needed.filter(n => !names.includes(n));
            const extra = names.filter(n => !needed.includes(n));
            checked++;
            if (missing.length > 0) missingHits.push({label, spec, names: missing});
            if (extra.length > 0) extraHits.push({label, spec, names: extra});
            if ((missing.length > 0 || extra.length > 0) && write) {
                // 需要集为空 → 整条删掉，别留 `import {} from '...'` 这种空语句
                if (needed.length === 0) {
                    const esc = m[0].replace(new RegExp('([.*+?^${}()|[\\]\\\\])', 'g'), '\\$1');
                    next = next.replace(new RegExp('[ \\t]*' + esc + '\\n?'), '');
                } else {
                    next = next.replace(m[0], editStatement(m[0], names, needed));
                }
            }
        }
        if (next !== src && write) writeFileSync(file, next, 'utf8');
    }
    return {checked, missingHits, extraHits};
};

const report = ({checked, missingHits, extraHits}) => {
    for (const h of missingHits) {
        check(false, '', h.label + ' → ' + h.spec + ' 少 import：' + h.names.join(', ')
            + '（运行时 ReferenceError；Vue 会吞掉它，表现成"没有红条的白屏"）');
    }
    for (const h of extraHits) {
        console.log('WARN: ' + h.label + ' → ' + h.spec + ' import 了但没用：' + h.names.join(', '));
    }
    check(missingHits.length === 0, `所有具名 import 行都没有漏（检查了 ${checked} 条，漏 0 条）`,
        `有 ${missingHits.length} 条 import 行漏了名字（加 write 参数可自动补齐）`);
    console.log(`（另有 ${extraHits.length} 条 import 引了没用到的名字，只是噪音，不算失败）`);
};

let result = scanAll(write);
if (write && (result.missingHits.length || result.extraHits.length)) {
    // 写完必须复核：否则"修好了却报红"或者"没修干净却报绿"两种都会发生
    const recheck = scanAll(false);
    console.log('已按口径重写 ' + (result.missingHits.length + result.extraHits.length) + ' 条 import；复核：'
        + (recheck.missingHits.length === 0 ? '无遗漏' : '仍有 ' + recheck.missingHits.length + ' 条遗漏'));
    result = recheck;
}
report(result);

console.log(failed ? '\nSOME CHECKS FAILED' : '\nALL CHECKS PASSED');
process.exit(failed ? 1 : 0);
