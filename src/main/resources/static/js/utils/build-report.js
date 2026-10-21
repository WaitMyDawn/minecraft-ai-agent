/** 历史消息保存可重建的 JSON 数据，展示时校验边界，不执行伪代码或消息中的 HTML。 */
export const readBuildReport = (content) => {
    const match = String(content || '').match(/<build_report>([\s\S]*?)<\/build_report>/);
    if (!match || match[1].length > 2_000_000) return null;
    try {
        const report = JSON.parse(match[1]);
        if (report.schemaVersion !== 1 || !Number.isInteger(report.total) || report.total < 0 || report.total > 500
            || !report.categories || typeof report.categories !== 'object' || Array.isArray(report.categories)
            || !Array.isArray(report.issues)) return null;
        const categories = Object.entries(report.categories).filter(([name, count]) => name.length <= 64
            && Number.isInteger(count) && count >= 0 && count <= report.total)
            .map(([name, count]) => ({name, count, percentage: report.total ? count * 100 / report.total : 0}))
            .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name));
        const issues = report.issues.slice(0, 100).filter(i => i && ['mod', 'dependency', 'versionId', 'reason']
            .every(key => typeof i[key] === 'string' && i[key].length <= 128) && typeof i.dropped === 'boolean').map(i => ({...i,
                detail: typeof i.detail === 'string' && i.detail.length <= 1000 ? i.detail : '',
                codes: Array.isArray(i.codes) ? i.codes.filter(v => typeof v === 'string' && /^[A-Z_]{1,64}$/.test(v)).slice(0, 16) : [i.reason],
                requiredVersions: Array.isArray(i.requiredVersions) ? i.requiredVersions.filter(v => typeof v === 'string'
                    && /^[A-Za-z0-9_.-]{1,128}$/.test(v)).slice(0, 32) : i.versionId ? [i.versionId] : []}));
        return {...report, categories, issues, optimization: readOptimizationPortfolio(report.optimization)};
    } catch { return null; }
};

export const modProjectLink = slug => 'https://modrinth.com/mod/' + encodeURIComponent(slug);
const evidenceState = value => {
    const codes = ['VERIFIED','PROJECT_MISSING','VERSION_PENDING','RELATION_PENDING','NOT_SELECTED','QUOTE_MISMATCH',
        'FACTS_UNAVAILABLE','VETOED','NO_REVIEW','NO_EVIDENCE','PURPOSE_PENDING','REVIEW_GAP','EVIDENCE_REMOVED','DISPLAY_TRUNCATED','CORE_GENERATION_PENDING'];
    if (!value || !codes.includes(value.code) || !Array.isArray(value.projects)) return null;
    return {code: value.code, projects: value.projects.filter(s => typeof s === 'string' && /^[a-zA-Z0-9_.-]{1,128}$/.test(s)).slice(0, 24),
        countsComplete: value.countsComplete !== false,
        generation: Array.isArray(value.generation) ? value.generation.slice(0, 4).filter(g => g && typeof g.dimension === 'string'
            && g.dimension.length <= 64 && typeof g.label === 'string' && g.label.length <= 64 && Array.isArray(g.projects))
            .map(g => ({dimension: g.dimension, label: g.label, projects: g.projects.filter(s => typeof s === 'string'
                && /^[a-zA-Z0-9_.-]{1,128}$/.test(s)).slice(0, 24)})) : []};
};
const readQuantity = value => {
    if (!value || !['RETAINED_ROOTS', 'ADDED_ROOTS'].includes(value.scope) || typeof value.complete !== 'boolean'
        || ![value.min, value.max, value.actual].every(n => Number.isInteger(n) && n >= 0 && n <= 500)
        || value.min > value.max || !Array.isArray(value.projects) || value.projects.length !== value.actual
        || new Set(value.projects).size !== value.projects.length
        || !value.projects.every(s => typeof s === 'string' && /^[a-zA-Z0-9_.-]{1,128}$/.test(s))) return null;
    return value;
};
export const quantitySummary = row => {
    const q = row.quantity;
    return q ? `${q.scope === 'ADDED_ROOTS' ? '本轮新增' : '全包保留'}有依据的内容模组：${q.actual} / ${q.min}～${q.max} 个（不含前置）${q.complete ? '' : '，计数待核实'}` : '';
};
const readAssessment = (value, status) => {
    const labels = {MET:'已满足', PARTIAL:'部分满足', NOT_MET:'未满足', UNKNOWN:'暂无法核实', WAITING:'待选择', CONTEXT:'上下文'};
    const projects = list => Array.isArray(list) && list.length <= 500 && list.every(s => typeof s === 'string' && /^[a-zA-Z0-9_.-]{1,128}$/.test(s));
    const identity = status === 'VERIFIED_PROJECTS' && value?.outcome === 'MET' && value.label === '点名项目已保留';
    if (!value || value.schemaVersion !== 1 || !Object.hasOwn(labels, value.outcome) || (!identity && value.label !== labels[value.outcome])
        || typeof value.evidenceCode !== 'string' || !/^[A-Z_]{1,64}$/.test(value.evidenceCode)
        || typeof value.evidenceLabel !== 'string' || value.evidenceLabel.length > 200
        || !projects(value.userRemoved) || !projects(value.cleanupRemoved) || !projects(value.automaticRemoved || [])) return null;
    return value;
};
export const readCompletion = value => {
    const labels = {MET:'已核对的构建要求均满足', PARTIAL:'尚未完全满足构建要求', UNKNOWN:'整体结果仍待核实',
        WAITING:'等待确认，尚非最终结果', CONTEXT:'仅完成基础信息核对'};
    const quantityLabels = {MET:'数量已达标', NOT_MET:'数量未达标', UNVERIFIED:'数量待核实', NO_TARGET:'无额外数量要求'};
    const bounded = (s, max) => typeof s === 'string' && s.length <= max;
    const number = n => Number.isInteger(n) && n >= 0 && n <= 500;
    const q = value?.quantity, counts = value?.targets;
    if (!value || value.schemaVersion !== 1 || !Object.hasOwn(labels,value.outcome) || value.label !== labels[value.outcome]
        || !bounded(value.note,1000) || !q || !Object.hasOwn(quantityLabels,q.status) || q.label !== quantityLabels[q.status]
        || !['','TOTAL_FILES','ROOT_MODS','ADDED_ROOTS'].includes(q.scope) || !['','EXACT','APPROXIMATE'].includes(q.precision)
        || !bounded(q.display,500) || !['target','min','max','actual','acceptedMin','acceptedMax'].every(k => q[k] === null || number(q[k]))
        || (['MET','NOT_MET'].includes(q.status) && (!q.scope || !q.precision || !['target','min','max','actual','acceptedMin','acceptedMax'].every(k => number(q[k]))))
        || !counts || !['met','partial','notMet','unknown','waiting','context'].every(k => number(counts[k]) && counts[k] <= 192)
        || !number(counts.parentIssues) || counts.parentIssues > 64
        || ['met','partial','notMet','unknown','waiting','context'].reduce((n,k) => n + counts[k],0) > 192) return null;
    return value;
};
export const requirementEvidence = row => row.assessment?.evidenceLabel || '历史消息未包含统一评估结论，以原始依据与备注为准';
export const selectionSummary = row => [row.assessment?.userRemoved?.length ? '按用户选择取消：' + row.assessment.userRemoved.join('、') : '',
    row.assessment?.cleanupRemoved?.length ? '随选择清理：' + row.assessment.cleanupRemoved.join('、') : '',
    row.assessment?.automaticRemoved?.length ? '按设置采用推荐组合，未保留：' + row.assessment.automaticRemoved.join('、') : ''].filter(Boolean).join('；');
export const evidenceTypeLabel = code => ({PROJECT_IDENTITY:'点名身份及选版', REVIEW_QUOTE:'项目用途原文',
    VERSION_RELATION:'所选版本关系', DESCRIPTION_RELATION:'项目声明关系', PROJECT_GENERATION:'维度生成原文'}[code] || '历史用途依据');
export const readRequirementReport = content => {
    const match = String(content || '').match(/<requirement_report>([\s\S]*?)<\/requirement_report>/);
    if (!match || match[1].length > 1_000_000) return null;
    const text = (s, max) => typeof s === 'string' && s.length <= max;
    try {
        const data = JSON.parse(match[1]);
        if (data.schemaVersion !== 1 || !Array.isArray(data.requirements) || data.requirements.length > 64
            || !text(data.repairStatus, 80) || !Array.isArray(data.repaired)) return null;
        const validRow = r => r && /^r-[a-f0-9]{16}$/.test(r.id) && text(r.source, 4000) && text(r.status, 40)
            && text(r.note, 4000) && Array.isArray(r.mods) && Array.isArray(r.missing);
        const items = mods => mods.slice(0, 24).filter(m => m && text(m.slug, 128) && /^[a-zA-Z0-9_.-]{1,128}$/.test(m.slug) && text(m.purpose, 500)
            && text(m.quote, m.evidenceType === 'PROJECT_GENERATION' ? 1000 : 280));
        const ledger = Array.isArray(data.ledger) ? data.ledger.slice(0,4096).filter(p => p && /^r-[a-f0-9]{16}$/.test(p.targetId)
            && text(p.slug,128) && /^[a-zA-Z0-9_.-]{1,128}$/.test(p.slug) && text(p.versionId,128)
            && /^[a-zA-Z0-9_.-]*$/.test(p.versionId) && text(p.status,64) && text(p.evidenceType,64)) : [];
        const enrich = (mods, id) => items(mods).map(m => {
            const proof = ledger.find(p => p.targetId === id && p.slug === m.slug && p.evidenceType === m.evidenceType && p.status === 'VERIFIED');
            return {...m, versionId: proof?.versionId || '', partners: Array.isArray(m.partners)
                ? m.partners.filter(s => text(s,128) && /^[a-zA-Z0-9_.-]{1,128}$/.test(s)).slice(0,24) : []};
        });
        const requirements = data.requirements.filter(r => r && /^r-[a-f0-9]{16}$/.test(r.id)
            && text(r.source, 4000) && text(r.status, 40) && text(r.note, 4000)
            && Array.isArray(r.mods) && Array.isArray(r.missing))
            .filter(r => r.status !== 'CONTEXT').map(r => ({...r,
                assessment: readAssessment(r.assessment,r.status), quantity: readQuantity(r.quantity), evidenceState: evidenceState(r.evidenceState),
                mods: enrich(r.mods,r.id),
                missing: r.missing.filter(s => text(s, 128)).slice(0, 100),
                criteria: Array.isArray(r.criteria) ? r.criteria.slice(0, 16).filter(validRow).filter(c => c.status !== 'CONTEXT').map(c => ({...c,
                    assessment: readAssessment(c.assessment,c.status), quantity: readQuantity(c.quantity), evidenceState: evidenceState(c.evidenceState),
                    mods: enrich(c.mods,c.id), missing: c.missing.filter(s => text(s, 128)).slice(0, 24)})) : []}));
        if (requirements.reduce((total, r) => total + r.criteria.length, 0) > 128) return null;
        return {...data, requirements, ledger, completion:readCompletion(data.completion)};
    } catch { return null; }
};
export const requirementStatus = value => {
    const row = typeof value === 'string' ? {status: value} : value || {};
    if (row.assessment) return row.status === 'VERIFIED_PROJECTS' && row.assessment.outcome === 'MET' ? '点名项目已保留' : row.assessment.label;
    const state = row.evidenceState?.code;
    if (state === 'PROJECT_MISSING') return '点名项目未入包';
    if (state === 'VERSION_PENDING') return '版本待核实';
    if (state === 'RELATION_PENDING') return '关系待核实';
    if (state === 'CORE_GENERATION_PENDING') return '核心生成待核实';
    if (row.quantity && !row.quantity.complete) return '数量待核实';
    if (row.quantity && row.quantity.actual < row.quantity.min) return '数量不足';
    if (row.quantity && row.quantity.actual > row.quantity.max) return '超出本项数量范围';
    return {VERIFIED_PROJECTS: '点名项目已保留', SUPPORTING_EVIDENCE: '有用途依据', PARTIAL: '部分目标待核实',
        UNVERIFIED: '用途待核实', NOT_RETAINED: '项目待复核'}[row.status] || '待核实';
};
export const requirementTone = row => row.assessment ? ({MET:'bg-indigo-50 text-indigo-700', NOT_MET:'bg-rose-50 text-rose-800',
    PARTIAL:'bg-amber-50 text-amber-800', WAITING:'bg-amber-50 text-amber-800'}[row.assessment.outcome] || 'bg-slate-100 text-slate-600') : row.evidenceState?.code === 'PROJECT_MISSING' ? 'bg-rose-50 text-rose-800'
    : ['VERIFIED_PROJECTS','SUPPORTING_EVIDENCE'].includes(row.status) ? 'bg-indigo-50 text-indigo-700' : 'bg-slate-100 text-slate-600';
export const requirementNote = row => {
    const note = String(row.note || '');
    if (note.startsWith('用途尚未取得可核对的审核证据')) return '本项目标的用途仍待核实。';
    return note;
};
export const readReplacementChoices = content => {
    const match = String(content || '').match(/<replacement_choices>([\s\S]*?)<\/replacement_choices>/);
    if (!match || match[1].length > 50_000) return [];
    try {
        const data = JSON.parse(match[1]);
        if (data.schemaVersion !== 1 || !data.choices || typeof data.choices !== 'object' || Array.isArray(data.choices)) return [];
        const valid = slug => typeof slug === 'string' && /^[a-zA-Z0-9_.-]{1,128}$/.test(slug);
        return Object.entries(data.choices).slice(0, 64).filter(([original, selected]) => valid(original)
            && Array.isArray(selected) && selected.length <= 16 && selected.every(valid))
            .map(([original, selected]) => ({original, selected: [...new Set(selected)]}));
    } catch { return []; }
};
export const issueReason = code => ({
    PROJECT_NOT_FOUND: '项目未找到', NO_VERSION_FOR_ENV: '前置没有适配当前环境的版本',
    LOADER_MISMATCH: '加载器不匹配', UPSTREAM_UNAVAILABLE: '取数失败，尚未核实',
    VERSION_CONFLICT: '需要的精确版本存在冲突', VERSION_MISMATCH: '已选版本与要求不一致',
    MISSING_REQUIRED: '缺少必需前置', DEPENDENT_OF_DROPPED: '依赖的前置已被移除',
    CONFLICT_WITH_KEPT: '与保留模组存在互斥声明', CONFLICT_BETWEEN_ROOTS: '核心模组之间存在互斥声明',
    MULTILOADER_DEPENDENCY_UNVERIFIED: '多加载器版本的依赖适用范围待核实',
    BUDGET_EXCEEDED: '依赖检查未完成', AUDIT_BUDGET_EXCEEDED: '依赖检查未完成'
}[code] || '依赖信息待核实');

export const optimizationRole = role => ({RENDERER: '渲染引擎', ENTITY_CULLING: '实体裁剪', BLOCK_CULLING: '方块面裁剪',
    LEAF_CULLING: '树叶裁剪', MEMORY: '内存', CPU: 'CPU 与计算开销', TICK: '游戏逻辑与 tick',
    LOADING: '启动与加载', CHUNKS: '区块处理', NETWORK: '网络处理', RENDER_SUPPORT: '渲染辅助'}[role] || role);
export const optimizationSide = side => ({CLIENT: '客户端', SERVER: 'Minecraft 服务端', BOTH: '客户端及服务端', UNKNOWN: '侧别未核实'}[side] || '侧别未核实');
const readOptimizationPortfolio = data => {
    const text = (value, max) => typeof value === 'string' && value.length <= max;
    const slug = value => text(value, 128) && /^[a-zA-Z0-9_.-]+$/.test(value);
    const sides = ['CLIENT', 'SERVER', 'BOTH', 'UNKNOWN'];
    const roles = ['RENDERER', 'ENTITY_CULLING', 'BLOCK_CULLING', 'LEAF_CULLING', 'MEMORY', 'CPU', 'TICK', 'LOADING', 'CHUNKS', 'NETWORK', 'RENDER_SUPPORT'];
    const roleList = value => Array.isArray(value) && value.length <= roles.length && new Set(value).size === value.length && value.every(v => roles.includes(v));
    if (!data || data.schemaVersion !== 1 || !Number.isInteger(data.totalFiles) || data.totalFiles < 0 || data.totalFiles > 500
        || !Number.isInteger(data.modCount) || data.modCount < 0 || data.modCount > data.totalFiles
        || !Array.isArray(data.entries) || data.entries.length !== data.modCount || !Array.isArray(data.targets) || data.targets.length > 256
        || typeof data.referenceActive !== 'boolean' || typeof data.rootsKnown !== 'boolean'
        || !roleList(data.covered) || !roleList(data.suggestedMissing)) return null;
    if (!data.entries.every(e => e && slug(e.slug) && slug(e.versionId) && sides.includes(e.side)
        && e.roles && typeof e.roles === 'object' && !Array.isArray(e.roles)
        && Object.keys(e.roles).length > 0 && Object.keys(e.roles).length <= roles.length
        && Object.entries(e.roles).every(([role, quote]) => roles.includes(role) && text(quote, 160)))) return null;
    if (new Set(data.entries.map(e => e.slug)).size !== data.modCount) return null;
    if (!data.targets.every(t => t && text(t.id, 80) && text(t.source, 400) && sides.includes(t.side)
        && roleList(t.desired) && roleList(t.covered) && roleList(t.missing)
        && Number.isInteger(t.modCount) && t.modCount >= 0 && t.modCount <= data.modCount)) return null;
    // 数量比例由最终文件数重算；历史中的参考字段不能改变展示口径。
    return {...data, percentage: data.totalFiles ? data.modCount * 100 / data.totalFiles : 0,
        referenceMin: Math.max(1, Math.ceil(data.totalFiles * .05)), referenceMax: Math.max(1, Math.ceil(data.totalFiles * .10))};
};
