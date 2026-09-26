// 与后端约定的两段文本/结构转换：Markdown 安全渲染、<ops_summary> 展开成可读行。
// 刻意不 import Vue：这三个都是纯函数，测试和复用都不需要跑起整个应用。

/** HTML 转义（防 XSS）。marked 渲染前必须先做这一步。 */
export const escapeHtml = (s) => String(s ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');

/** AI 回复的安全 Markdown 渲染：先转义再交给 marked；连续空行折叠成一段间距。 */
export const renderMarkdownSafe = (content) => {
    if (!content) return '';
    const text = String(content).replace(/\n{3,}/g, '\n\n').trim();
    try {
        if (typeof marked !== 'undefined' && marked.parse) {
            return marked.parse(escapeHtml(text), {gfm: true, breaks: false});
        }
    } catch (e) { /* fallthrough */ }
    return escapeHtml(text).replace(/\n/g, '<br>');
};

/**
 * 把后端 <ops_summary> 的结构化变更转成可读行（无变更时返回空数组）。
 *
 * <p>渲染走 Vue 插值（非 v-html），所以包名/模组名里的特殊字符不会变成注入点。
 *
 * @param ops   后端下发的变更对象
 * @param gloss 类别 → 中文释义（来自 /api/meta/categories）
 */
export const opsRows = (ops, gloss) => {
    if (!ops) return [];
    const rows = [];
    if (Array.isArray(ops.removed) && ops.removed.length) {
        rows.push({label: '移除模组', value: ops.removed.join('、')});
    }
    const entries = Object.entries(ops.categoryTargets || {});
    if (entries.length) {
        rows.push({
            label: '类别配比',
            value: entries.map(([cat, delta]) => {
                const zh = String((gloss || {})[cat] || '').split('/')[0];
                return cat + (zh ? `（${zh}）` : '') + (delta >= 0 ? ` +${delta}` : ` ${delta}`);
            }).join('、'),
            hint: '已并入本轮配额，最终配比见回复说明'
        });
    }
    if (typeof ops.targetCount === 'number') {
        rows.push({label: '目标数量', value: ops.targetCount + ' 个'});
    }
    if (typeof ops.maxDownloads === 'number') {
        rows.push({
            label: '热度上限',
            value: ops.maxDownloads.toLocaleString('en-US'),
            hint: ops.maxDownloads <= 500000 ? '冷门模式' : ''
        });
    }
    if (ops.name) rows.push({label: '包名', value: ops.name});
    return rows;
};